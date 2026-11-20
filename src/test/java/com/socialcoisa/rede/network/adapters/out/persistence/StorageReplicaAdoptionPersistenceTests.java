package com.socialcoisa.rede.network.adapters.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.socialcoisa.rede.network.application.port.out.StorageCatalogRepository;
import com.socialcoisa.rede.network.domain.StorageObject;

/**
 * A consulta do healing de "réplica única" (doc 13 §5.10) contra o SQL de verdade: quem é candidato
 * a receber a primeira cópia de nó.
 *
 * <p>O teste do serviço prova a decisão; este prova o <b>filtro</b>. O erro fácil aqui é o
 * {@code not exists} virar {@code count = 1} (e excluir o objeto sem colocação nenhuma, que também
 * está só na réplica 0), ou a colocação de réplica 0 contar como "tem cópia de nó" — a réplica 0 é o
 * disco do Control Plane, e é exatamente o que a adoção quer deixar de ter sozinho.</p>
 */
@SpringBootTest
class StorageReplicaAdoptionPersistenceTests {

	/**
	 * O "agora" da consulta é o relógio do processo, não um instante fixo do teste: os prazos daqui
	 * são relativos a ele, senão um prazo "no passado do teste" seria futuro para o banco.
	 */
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

	private void objeto(String id, String estado, Long bytes, Instant expiresAt, Integer superseded) {
		jdbc.update("""
			insert into network_storage_objects
				(id, bucket, object_key, content_type, bytes, sha256, state, created_by, created_at, updated_at,
				 expires_at, superseded_by)
			values (?, 'fotos', ?, 'image/jpeg', ?, ?, ?, 'user_1', ?, ?, ?, ?)
			""", id, id + ".jpg", bytes, bytes == null ? null : "sha-" + id, estado,
			java.sql.Timestamp.from(AGORA), java.sql.Timestamp.from(AGORA),
			expiresAt == null ? null : java.sql.Timestamp.from(expiresAt),
			superseded == null ? null : "sob_outro");
	}

	private void colocacao(String objectId, int replica, String estado) {
		jdbc.update("""
			insert into network_storage_placements (object_id, replica, node_id, bytes, sha256, state, verified_at)
			values (?, ?, ?, 1000, 'sha', ?, ?)
			""", objectId, replica, "nd_" + replica, estado, java.sql.Timestamp.from(AGORA));
	}

	private java.util.List<String> candidatos() {
		return catalog.withOnlyReplicaZero(50).stream().map(StorageObject::id).toList();
	}

	@Test
	void o_objeto_sem_colocacao_nenhuma_e_candidato() {
		objeto("sob_sem", "STORED", 1000L, null, null);

		// `count = 1` excluiria este: ele tem UM objeto e ZERO colocações de nó.
		assertThat(candidatos()).containsExactly("sob_sem");
	}

	@Test
	void a_colocacao_da_replica_zero_nao_conta_como_copia_de_no() {
		objeto("sob_zero", "STORED", 1000L, null, null);
		colocacao("sob_zero", 0, "OK");

		assertThat(candidatos()).as("a réplica 0 é o disco do Control Plane").containsExactly("sob_zero");
	}

	@Test
	void qualquer_colocacao_de_no_tira_o_objeto_da_lista() {
		objeto("sob_ok", "STORED", 1000L, null, null);
		colocacao("sob_ok", 1, "OK");
		objeto("sob_pendente", "STORED", 1000L, null, null);
		colocacao("sob_pendente", 1, "PENDING_REMOTE");
		objeto("sob_stale", "STORED", 1000L, null, null);
		colocacao("sob_stale", 1, "STALE");

		assertThat(candidatos()).as("transferência já pedida não é pedida de novo")
			.doesNotContain("sob_ok", "sob_pendente", "sob_stale");
	}

	@Test
	void vencido_lapidado_e_substituido_ficam_fora() {
		objeto("sob_bom", "STORED", 1000L, null, null);
		objeto("sob_vencido", "STORED", 1000L, AGORA.minusSeconds(3600), null);
		objeto("sob_futuro", "STORED", 1000L, AGORA.plusSeconds(3600), null);
		objeto("sob_lapide", "DELETED", 1000L, null, null);
		objeto("sob_pendente", "PENDING", null, null, null);

		// O prazo no futuro ainda vale; os outros três não têm o que replicar.
		assertThat(candidatos()).containsExactlyInAnyOrder("sob_bom", "sob_futuro");
	}

	@Test
	void o_limite_e_respeitado() {
		for (int i = 0; i < 5; i++) {
			objeto("sob_" + i, "STORED", 1000L, null, null);
		}

		assertThat(catalog.withOnlyReplicaZero(3)).hasSize(3);
	}
}