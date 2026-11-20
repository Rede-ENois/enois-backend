package com.socialcoisa.rede.network.adapters.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.socialcoisa.rede.network.application.port.out.StorageCatalogRepository;

/**
 * O prazo ({@code expires_at}) contra o SQL de verdade, e o que ele faz com uma <b>versão
 * substituída</b>. A versão é um objeto com id próprio (doc 17 §11.1), e o prazo é dela — não da
 * chave. A varredura que procura vencidos, porém, pedia {@code superseded_by is null}, então o prazo
 * de qualquer versão que deixou de ser corrente era simplesmente ignorado.
 *
 * <p>O caso concreto: um objeto é gravado com prazo de sete dias (uma foto de campanha, digamos), e
 * dois dias depois a chave recebe um arquivo novo. A versão antiga continua com prazo que vence, mas
 * não é corrente — e sem política de retenção no bucket ela nunca é liberada (§11.3: sem política, o
 * padrão é guardar). O resultado: conteúdo que o dono marcou para expirar fica para sempre, ocupa a
 * conta dele e — por ser lápide que nunca vira — ocupa o disco dos nós.
 *
 * <p>O discriminante: a versão substituída com prazo vencido <b>aparece</b> em {@code expired()}. Um
 * teste de serviço com mock não pega isto, porque o filtro mora no {@code where}. E a corrente vencida
 * continua aparecendo, para a correção não ser "tirar o filtro do corrente".</p>
 */
@SpringBootTest
class StorageLifecycleExpiryPersistenceTests {

	private static final Instant AGORA = Instant.now();

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private StorageCatalogRepository catalog;

	@BeforeEach
	void setUp() {
		jdbc.update("delete from network_storage_placements");
		jdbc.update("delete from network_storage_objects");
	}

	/** {@code supersededBy} nulo é a corrente; senão é o id da versão que tomou a frente. */
	private void objeto(String id, String estado, Instant expiresAt, String supersededBy) {
		jdbc.update("""
			insert into network_storage_objects
				(id, bucket, object_key, content_type, bytes, sha256, state, created_by, created_at, updated_at,
				 expires_at, superseded_by)
			values (?, 'fotos', ?, 'image/jpeg', 10, ?, ?, 'user_1', ?, ?, ?, ?)
			""", id, id + ".jpg", "sha-" + id, estado, java.sql.Timestamp.from(AGORA),
			java.sql.Timestamp.from(AGORA), expiresAt == null ? null : java.sql.Timestamp.from(expiresAt),
			supersededBy);
	}

	@Test
	void versao_substituida_com_prazo_vencido_tambem_entra_na_varredura() {
		objeto("sob_antiga", "STORED", AGORA.minusSeconds(60), "sob_nova");
		objeto("sob_nova", "STORED", AGORA.plusSeconds(3600), null);

		var vencidos = catalog.expired(AGORA, 10);

		assertThat(vencidos).extracting(v -> v.id()).contains("sob_antiga");
	}

	@Test
	void a_versao_corrente_vencida_continua_aparecendo() {
		objeto("sob_corrente", "STORED", AGORA.minusSeconds(60), null);

		assertThat(catalog.expired(AGORA, 10)).extracting(v -> v.id()).containsExactly("sob_corrente");
	}

	@Test
	void versao_substituida_sem_prazo_nao_entra_por_expiry() {
		objeto("sob_antiga", "STORED", null, "sob_nova");
		objeto("sob_nova", "STORED", AGORA.plusSeconds(3600), null);

		assertThat(catalog.expired(AGORA, 10)).isEmpty();
	}

	@Test
	void versao_substituida_ja_lapidada_nao_volta() {
		objeto("sob_antiga", "DELETED", AGORA.minusSeconds(60), "sob_nova");
		objeto("sob_nova", "STORED", AGORA.plusSeconds(3600), null);

		assertThat(catalog.expired(AGORA, 10)).isEmpty();
	}
}
