package com.socialcoisa.rede.network.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
import com.socialcoisa.rede.network.application.testsupport.CatalogoEmMemoria;
import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.network.domain.NodeProfile;
import com.socialcoisa.rede.network.domain.NodeStatus;
import com.socialcoisa.rede.network.domain.StorageObject;
import com.socialcoisa.rede.shared.audit.AuditTrail;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Upload numa chave que <b>venceu</b> sem ter sido coletada (brief §14, "expiração automática").
 *
 * <p>A leitura já tratava o vencido como inexistente ({@code requireDownloadable} responde 404). Falta
 * o outro lado: o upload na <b>mesma chave</b>. Um objeto cujo prazo venceu tem a linha viva
 * ({@code PENDING}/{@code STORED}) até a coleta passar, e a chave é única por
 * {@code bucket + object_key}: o {@code createPending} é inserir-ou-ler, então a gravação nova era
 * engolida pela linha velha.</p>
 *
 * <p>Duas armadilhas escondidas aí, e é o que estes testes seguram:</p>
 * <ul>
 * <li><b>gravar no objeto vencido</b> — o prazo antigo continua na linha, e o download da gravação nova
 * responde 404 no instante seguinte, pelo prazo de outro objeto;</li>
 * <li><b>a gravação some</b> — o {@code finishUpload} acha o objeto {@code STORED} e responde
 * {@code 409 STORAGE_ALREADY_STORED}, enquanto quem listou o bucket já não via o objeto. O cliente
 * recebe "já existe" para algo que não existe.</li>
 * </ul>
 *
 * <p><b>Agora:</b> o vencido recebe lápide ali mesmo (parcial, sem apagar o corpo de quem tem cópia
 * — a coleta continua dona disso) e o upload segue numa linha nova, com o prazo novo. A decisão é
 * condicional no estado: se outra gravação venceu a corrida entre a leitura e a marcação, a segunda
 * gravação não lapida a primeira e recebe {@code 409} — nunca duas linhas vivas para a mesma chave.</p>
 */
class StorageExpiredKeyReuseTests {

	private static final Instant AGORA = Instant.parse("2026-11-19T12:00:00Z");
	private static final Instant ONTEM = AGORA.minusSeconds(24 * 3600);

	@TempDir
	Path root;

	private Catalogo catalogo;
	private StorageCatalogService service;

	@BeforeEach
	void setUp() {
		catalogo = new Catalogo();
		NetworkNodeRepository nodes = mock(NetworkNodeRepository.class);
		when(nodes.findCandidates(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt()))
			.thenReturn(List.of());
		service = new StorageCatalogService(catalogo, mock(AuditTrail.class), Clock.fixed(AGORA, ZoneOffset.UTC),
			10_000, root.toString(), "", new StoragePlacementService(nodes, catalogo, 0, 90), null);
	}

	private static byte[] body(String texto) {
		return texto.getBytes(StandardCharsets.UTF_8);
	}

	private static NetworkNode storageNode() {
		return new NetworkNode("nd_store", "vps-casa", "user_1", NodeStatus.ACTIVE, Set.of(NodeProfile.NODE_STORAGE),
			"sa-east-1", "BR", "1.0.0", null, 2, 4096, 102400, 100, null, null, null, null, null, null, null, 0,
			null, null);
	}

	/** O objeto já gravado e vencido: é o estado que a coleta ainda não passou para limpar. */
	private StorageObject vencido(String chave) {
		String __x = "";
		StorageObject object = service.beginUpload("user_1", "fotos", chave, "image/jpeg");
		service.store(storageNode(), object.id(), new ByteArrayInputStream(body("antigo")));
		catalogo.setExpiresAt(object.id(), ONTEM);
		return catalogo.findById(object.id()).orElseThrow();
	}

	@Test
	void a_chave_vencida_recebe_upload_novo_e_o_novo_nao_herda_o_prazo_antigo() throws Exception {
		StorageObject velho = vencido("gato.jpg");
		assertThat(catalogo.findById(velho.id()).orElseThrow().expired(AGORA)).isTrue();

		StorageObject novo = service.beginUpload("user_1", "fotos", "gato.jpg", "image/jpeg");

		assertThat(novo.id()).as("a chave vencida não pode ser reaproveitada: é uma linha nova").isNotEqualTo(velho.id());
		assertThat(novo.expiresAt()).as("o prazo é de quem fez a gravação, não do objeto anterior").isNull();
		// O antigo sai da chave (aposentado, superseded_by apontando para o novo): a chave passou a
		// resolver para o novo, e o antigo NÃO vira lápide — lápide é remoção, e o objeto só venceu.
		StorageObject aposentado = catalogo.findById(velho.id()).orElseThrow();
		assertThat(aposentado.state().name()).as("aposentar não é apagar").isEqualTo("STORED");
		assertThat(aposentado.supersededBy()).isEqualTo(novo.id());
		assertThat(aposentado.currentVersion()).as("o vencido não pode ficar vivo ao lado do novo").isFalse();
		assertThat(catalogo.find("fotos", "gato.jpg").orElseThrow().id()).isEqualTo(novo.id());
	}

	@Test
	void a_gravacao_nova_pela_chave_vencida_termina_e_o_download_a_devolve() throws Exception {
		vencido("gato.jpg");

		StorageObject novo = service.beginUpload("user_1", "fotos", "gato.jpg", "image/jpeg");
		service.store(storageNode(), novo.id(), new ByteArrayInputStream(body("novo")));

		StorageObject baixado = service.requireDownloadable("fotos", "gato.jpg");
		assertThat(baixado.id()).isEqualTo(novo.id());
		assertThat(baixado.state().name()).isEqualTo("STORED");
	}

	@Test
	void a_lapide_do_vencido_nao_apaga_o_corpo_de_quem_tem_copia() throws Exception {
		StorageObject velho = vencido("gato.jpg");
		Path corpoAntigo = service.bodyPath(velho.id());
		assertThat(Files.exists(corpoAntigo)).isTrue();

		service.beginUpload("user_1", "fotos", "gato.jpg", "image/jpeg");

		// O corpo continua no disco: quem coleta é a coleta, com a cópia de cada nó na mão. Apagar aqui
		// faria o upload de um nome apagar bytes que um nó de outro participante ainda serve.
		assertThat(Files.exists(corpoAntigo)).as("o corpo do vencido não é apagado por um upload de mesma chave").isTrue();
	}

	@Test
	void o_reenvio_de_um_objeto_vivo_nao_lapida_nem_aposenta_o_objeto() {
		StorageObject vivo = service.beginUpload("user_1", "fotos", "gato.jpg", "image/jpeg");
		service.store(storageNode(), vivo.id(), new ByteArrayInputStream(body("vivo")));

		// O caso comum do cliente: a resposta do primeiro PUT se perdeu e ele reenvia. A abertura
		// devolve o MESMO objeto; nada é removido por causa de uma requisição que não traz corpo novo.
		StorageObject deNovo = service.beginUpload("user_1", "fotos", "gato.jpg", "image/jpeg");

		assertThat(deNovo.id()).isEqualTo(vivo.id());
		assertThat(catalogo.findById(vivo.id()).orElseThrow().state().name()).isEqualTo("STORED");
		assertThat(catalogo.findById(vivo.id()).orElseThrow().currentVersion()).isTrue();
		assertThat(service.requireDownloadable("fotos", "gato.jpg").id()).isEqualTo(vivo.id());
	}

	@Test
	void a_chave_lapidada_recebe_upload_novo_sem_ressuscitar_o_removido() throws Exception {
		StorageObject removido = service.beginUpload("user_1", "fotos", "gato.jpg", "image/jpeg");
		service.store(storageNode(), removido.id(), new ByteArrayInputStream(body("antigo")));
		service.delete("user_1", "fotos", "gato.jpg");

		StorageObject novo = service.beginUpload("user_1", "fotos", "gato.jpg", "image/jpeg");
		service.store(storageNode(), novo.id(), new ByteArrayInputStream(body("novo")));

		assertThat(novo.id()).isNotEqualTo(removido.id());
		assertThat(service.requireDownloadable("fotos", "gato.jpg").id())
			.as("o download entrega a gravação nova, nunca o conteúdo dele").isEqualTo(novo.id());
	}

	@Test
	void objeto_vivo_nao_e_lapidado_por_outro_upload_e_a_gravacao_nova_recusa() {
		StorageObject vivo = service.beginUpload("user_1", "fotos", "gato.jpg", "image/jpeg");
		service.store(storageNode(), vivo.id(), new ByteArrayInputStream(body("vivo")));

		// Sem vencimento, o comportamento é o de sempre: `beginUpload` devolve o mesmo objeto (o retry
		// do cliente é o caso comum) e quem recusa é a gravação, porque já há cópia confirmada.
		StorageObject deNovo = service.beginUpload("user_1", "fotos", "gato.jpg", "image/jpeg");
		assertThat(deNovo.id()).as("objeto vivo não é duplicado").isEqualTo(vivo.id());
		assertThatThrownBy(() -> service.store(storageNode(), deNovo.id(), new ByteArrayInputStream(body("outro"))))
			.isInstanceOfSatisfying(ApiException.class, erro -> {
				assertThat(erro.status()).isEqualTo(409);
				assertThat(erro.code()).isEqualTo("STORAGE_ALREADY_STORED");
			});
		assertThat(catalogo.findById(vivo.id()).orElseThrow().state().name()).isEqualTo("STORED");
	}

	/** O catálogo em memória dos testes de serviço, sem nada a mais. */
	private static final class Catalogo extends CatalogoEmMemoria {
	}
}