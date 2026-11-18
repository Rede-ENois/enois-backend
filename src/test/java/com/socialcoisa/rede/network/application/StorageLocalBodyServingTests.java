package com.socialcoisa.rede.network.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.socialcoisa.rede.network.application.port.out.StorageCatalogRepository;
import com.socialcoisa.rede.network.domain.StorageObject;
import com.socialcoisa.rede.network.domain.StorageObjectState;
import com.socialcoisa.rede.shared.audit.AuditTrail;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * De onde o Control Plane serve o corpo de um objeto: dos discos <b>desta instalação</b>, sem pedir
 * nada a outro nó.
 *
 * <p>Há duas formas de o corpo estar aqui: a raiz do Control Plane (réplica 0, gravada pelo
 * upload) e o diretório do nó que roda na mesma máquina ({@code rede.storage.node-body-root}, onde
 * o {@code OBJECT_PUT} deixa a cópia). Antes desta rodada só a primeira era lida: o objeto
 * replicado para o nó local respondia 409 mesmo com os bytes a um diretório de distância.</p>
 *
 * <p>O que estes testes seguram, e por quê:</p>
 * <ul>
 * <li><b>corpo no diretório do nó local é servido</b> — é o "guardar no próprio disco" virando
 * download de verdade, e não só uma cópia que ninguém lê;</li>
 * <li><b>sem corpo em lugar nenhum o 409 continua</b>, com o digest: o chamador designa a réplica
 * que tem o objeto. Um 200 vazio seria a pior forma de falha;</li>
 * <li><b>a raiz do Control Plane tem prioridade</b> — é a réplica 0, a que o upload mediu;</li>
 * <li><b>arquivo truncado não é servido</b> com o {@code Content-Length} do catálogo: um 200 que se
 * rompe no fim não diz ao cliente se perdeu bytes da rede ou do disco;</li>
 * <li><b>a varredura não cai para o diretório do nó</b> — se caísse, o sumiço da réplica 0 ficaria
 * escondido atrás de um corpo que o catálogo não atribui a esta instalação.</li>
 * </ul>
 */
class StorageLocalBodyServingTests {

	private static final Instant NOW = Instant.parse("2026-11-16T12:00:00Z");

	@TempDir
	Path root;

	private StorageCatalogRepository catalog;
	private NodeBodyStore nodeBodies;
	private StorageCatalogService service;

	@BeforeEach
	void setUp() {
		catalog = mock(StorageCatalogRepository.class);
		nodeBodies = new NodeBodyStore(root.resolve("no-local/objects").toString());
		service = new StorageCatalogService(catalog, mock(AuditTrail.class), Clock.fixed(NOW, ZoneOffset.UTC),
			10_000, root.resolve("cp").toString(), mock(StoragePlacementService.class), nodeBodies);
	}

	private static byte[] bytes(String text) {
		return text.getBytes(StandardCharsets.UTF_8);
	}

	private static String sha(String text) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes(text)));
		}
		catch (Exception impossible) {
			throw new IllegalStateException(impossible);
		}
	}

	private StorageObject objeto(String id, String content) {
		StorageObject object = StorageObject.current(id, "fotos", "gato.jpg", "image/jpeg",
			(long) bytes(content).length, sha(content), StorageObjectState.STORED, "user_1", NOW, NOW, null);
		when(catalog.findById(id)).thenReturn(Optional.of(object));
		return object;
	}

	/** O corpo do nó local, gravado pelo mesmo store que o processo usa quando configurado. */
	private void corpoDoNoLocal(String objectId, String content) throws Exception {
		nodeBodies.write(objectId, new ByteArrayInputStream(bytes(content)));
	}

	private String ler(String objectId) throws Exception {
		try (var body = service.readBody(objectId)) {
			return new String(body.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	@Test
	void corpo_que_so_esta_no_disco_do_no_local_e_servido() throws Exception {
		StorageObject object = objeto("sob_a", "conteudo do objeto");
		corpoDoNoLocal(object.id(), "conteudo do objeto");

		assertThat(ler(object.id())).isEqualTo("conteudo do objeto");
	}

	@Test
	void sem_corpo_em_lugar_nenhum_o_409_continua_apontando_o_digest() {
		StorageObject object = objeto("sob_a", "conteudo do objeto");

		assertThatThrownBy(() -> service.readBody(object.id()))
			.isInstanceOf(ApiException.class)
			.satisfies(erro -> {
				ApiException api = (ApiException) erro;
				assertThat(api.status()).isEqualTo(409);
				assertThat(api.code()).isEqualTo("STORAGE_REPLICA_ELSEWHERE");
				assertThat(api.getMessage()).contains(object.sha256());
			});
	}

	@Test
	void a_raiz_do_control_plane_tem_prioridade_sobre_o_disco_do_no() throws Exception {
		StorageObject object = objeto("sob_a", "AAAAAAA");
		Files.write(service.bodyPath(object.id()), bytes("AAAAAAA"));
		// Mesmo tamanho, conteúdo diferente: a escolha tem de ser pelo lugar, não pelo tamanho.
		corpoDoNoLocal(object.id(), "BBBBBBB");

		assertThat(ler(object.id())).isEqualTo("AAAAAAA");
	}

	@Test
	void corpo_truncado_no_no_nao_e_servido_com_o_tamanho_do_catalogo() throws Exception {
		StorageObject object = objeto("sob_a", "conteudo do objeto");
		corpoDoNoLocal(object.id(), "conteudo");

		assertThatThrownBy(() -> service.readBody(object.id()))
			.isInstanceOf(ApiException.class)
			.satisfies(erro -> {
				ApiException api = (ApiException) erro;
				assertThat(api.status()).isEqualTo(410);
				assertThat(api.code()).isEqualTo("STORAGE_BODY_SIZE_MISMATCH");
				assertThat(api.getMessage()).contains("tem 8 bytes").contains("registra 18");
			});
	}

	@Test
	void sem_no_local_o_servico_le_a_raiz_do_control_plane() throws Exception {
		StorageObject object = objeto("sob_a", "conteudo");
		Files.write(service.bodyPath(object.id()), bytes("conteudo"));
		StorageCatalogService semNo = new StorageCatalogService(catalog, mock(AuditTrail.class),
			Clock.fixed(NOW, ZoneOffset.UTC), 10_000, root.resolve("cp").toString(),
			mock(StoragePlacementService.class), null);

		try (var body = semNo.readBody(object.id())) {
			assertThat(new String(body.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("conteudo");
		}
	}

	@Test
	void leitura_nao_cria_o_diretorio_do_control_plane() {
		// GET é leitura. `root()` cria o diretório na primeira escrita, e chamá-lo no caminho de
		// leitura faria um download num Control Plane que nunca recebeu upload escrever em disco —
		// e uma falha de disco viraria 500 num download em vez de "não está aqui".
		StorageObject object = objeto("sob_a", "conteudo");
		Path raizCp = root.resolve("cp");
		assertThat(Files.exists(raizCp)).isFalse();

		assertThatThrownBy(() -> service.readBody(object.id())).isInstanceOf(ApiException.class);

		assertThat(Files.exists(raizCp)).as("a leitura criou o diretório de escrita").isFalse();
	}

	@Test
	void a_replica_zero_nao_cai_para_o_corpo_do_no_local() throws Exception {
		StorageObject object = objeto("sob_a", "conteudo do objeto");
		corpoDoNoLocal(object.id(), "conteudo do objeto");

		// A varredura relê a réplica 0: o corpo do nó local está aqui e é do MESMO objeto, mas não é
		// a cópia que o catálogo atribui a esta instalação. Devolvê-lo esconderia o sumiço real.
		assertThatThrownBy(() -> service.openReplicaZeroBody(object.id()))
			.isInstanceOf(ApiException.class)
			.satisfies(erro -> assertThat(((ApiException) erro).code()).isEqualTo("STORAGE_BODY_MISSING"));
	}
}