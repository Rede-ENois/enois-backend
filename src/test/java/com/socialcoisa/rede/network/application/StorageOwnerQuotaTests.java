package com.socialcoisa.rede.network.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.socialcoisa.rede.network.application.port.out.NetworkNodeRepository;
import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.network.domain.NodeProfile;
import com.socialcoisa.rede.network.domain.NodeStatus;
import com.socialcoisa.rede.shared.audit.AuditTrail;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * A cota de <b>quem usa</b> o Storage (brief §10 "quotas"; doc 20 §10).
 *
 * <p>O teto de espaço que existia era o de quem <b>cede</b>: disco livre e espaço declarado do nó.
 * Faltava o outro lado — quantos bytes um participante pode ocupar no catálogo. Sem ele, quem
 * primeiro enche o disco com credencial S3 faz o upload dos outros responderem {@code 507}, e o
 * vizinho paga pelo vizinho.</p>
 *
 * <p>O que estes testes seguram:</p>
 * <ul>
 * <li><b>a conta é por participante</b> — {@code created_by}, e não por bucket: quem tem dois
 * buckets não tem o dobro de espaço;</li>
 * <li><b>cota zero desliga</b> — é como a instalação que confia no limite do nó opera hoje, sem
 * flag própria;</li>
 * <li><b>o número que acabou aparece na mensagem</b> — um 507 sem número manda o operador procurar
 * no escuro;</li>
 * <li><b>a recusa é antes do corpo</b> — o objeto nem nasce no catálogo, então não fica um PENDING
 * órfão ocupando a chave do mesmo jeito.</li>
 * </ul>
 */
class StorageOwnerQuotaTests {

	private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

	@TempDir
	Path root;

	private CatalogoEmMemoria catalog;
	private NetworkNodeRepository nodes;
	private StorageCatalogService service;

	/** O mesmo catálogo em memória dos testes de serviço — a cota não é assunto do SQL. */
	private static final class CatalogoEmMemoria
			extends com.socialcoisa.rede.network.application.testsupport.CatalogoEmMemoria {
	}

	@BeforeEach
	void setUp() {
		catalog = new CatalogoEmMemoria();
		nodes = mock(NetworkNodeRepository.class);
		when(nodes.findCandidates(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt()))
			.thenReturn(List.of());
		// Mesma forma do serviço: teto de objeto generoso, colocação de uma cópia, cota no fim.
		service = comCota(12);
	}

	/**
	 * A cota com partes abertas de multipart exige banco; aqui o que se prova é a conta do
	 * <b>espaço gravado</b>, e o reservado é zero (o caso do banco tem teste de rota próprio).
	 */
	/** O serviço com a cota pedida, resto igual: colocação de uma cópia, sem nó local nem métrica. */
	private StorageCatalogService comCota(long maxBytesPerOwner) {
		return new StorageCatalogService(catalog, mock(AuditTrail.class), Clock.fixed(NOW, ZoneOffset.UTC),
			10_000, root.toString(), "", new StoragePlacementService(nodes, catalog, 0, 90), null, null,
			maxBytesPerOwner, null);
	}

	private static ByteArrayInputStream body(String text) {
		return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
	}

	private static NetworkNode storageNode() {
		return new NetworkNode("nd_store", "vps-casa", "user_1", NodeStatus.ACTIVE, Set.of(NodeProfile.NODE_STORAGE),
			"sa-east-1", "BR", "1.0.0", null, 2, 4096, 102400, 100, null, null, null, null, null, null, null, 0,
			null, null);
	}

	@Test
	void participante_cheio_e_recusado_antes_de_qualquer_corpo() {
		String primeiro = service.beginUpload("user_1", "fotos", "a.jpg", null).id();
		service.store(storageNode(), primeiro, body("123456789012"));

		assertThatThrownBy(() -> service.beginUpload("user_1", "fotos", "b.jpg", null))
			.isInstanceOfSatisfying(ApiException.class, erro -> {
				assertThat(erro.status()).isEqualTo(507);
				assertThat(erro.code()).isEqualTo("STORAGE_OWNER_QUOTA_EXCEEDED");
				assertThat(erro.getMessage()).contains("ja ocupa 12 bytes").contains("cota desta instalacao e 12 bytes");
			});
		// Recusou antes de nascer: a chave continua livre e nenhum PENDING fica no catálogo.
		assertThat(catalog.find("fotos", "b.jpg")).isEmpty();
	}

	@Test
	void o_outro_participante_nao_paga_pela_cota_do_primeiro() {
		String dele = service.beginUpload("user_1", "fotos", "a.jpg", null).id();
		service.store(storageNode(), dele, body("123456789012"));

		// Cota cheia para `user_1`; `user_2` começa do zero.
		String outro = service.beginUpload("user_2", "docs", "contrato.pdf", null).id();
		assertThat(outro).isNotBlank();
	}

	@Test
	void cota_zero_desliga_a_conta() {
		StorageCatalogService semCota = comCota(0);
		String id = semCota.beginUpload("user_1", "fotos", "a.jpg", null).id();
		semCota.store(storageNode(), id, body("123456789012345678901234567890"));

		String segundo = semCota.beginUpload("user_1", "fotos", "b.jpg", null).id();
		assertThat(segundo).isNotBlank();
	}

	/**
	 * A leitura do painel: o que o participante vê é o que a recusa vai usar.
	 *
	 * <p>O discriminante que importa é o do limite desligado ({@code 0}, o padrão da instalação):
	 * {@code livre = null}, nunca {@code 0} — "sem limite" e "cheio" são coisas opostas, e um painel
	 * que mostrasse zero mandaria o participante apagar arquivos que não precisam sair.</p>
	 */
	@Test
	void a_leitura_da_cota_acompanha_o_que_a_recusa_usa() {
		StorageCatalogService semCota = comCota(0);
		var leitura = semCota.cotaDe("user_1");
		assertThat(leitura.limite()).isZero();
		assertThat(leitura.livre()).as("sem limite nao e zero livre").isNull();

		StorageCatalogService apertado = comCota(40);
		String id = apertado.beginUpload("user_1", "fotos", "a.jpg", null).id();
		apertado.store(storageNode(), id, body("123456789012345678901234567890"));

		var depois = apertado.cotaDe("user_1");
		assertThat(depois.limite()).isEqualTo(40);
		assertThat(depois.usado()).isEqualTo(30);
		assertThat(depois.livre()).isEqualTo(10);
		// A mesma conta da recusa: o que cabe é o que o próximo corpo pode ter.
		assertThat(apertado.cotaDe("user_1").usado() + 11).isGreaterThan(apertado.cotaDe("user_1").limite());
	}

	@Test
	void acima_do_teto_a_mensagem_diz_o_que_acabou() {
		StorageCatalogService apertado = comCota(5);
		String id = apertado.beginUpload("user_1", "fotos", "a.jpg", null).id();

		// 12 bytes num teto de 5: a gravação recusa já na conta do tamanho medido, com os três
		// números — e o objeto fica PENDING, porque o retry depois de liberar espaço vale.
		assertThatThrownBy(() -> apertado.store(storageNode(), id, body("123456789012")))
			.isInstanceOf(ApiException.class)
			.hasMessageContaining("ja ocupa 0 bytes")
			.hasMessageContaining("soma 12")
			.hasMessageContaining("cota desta instalacao e 5 bytes");
	}
}