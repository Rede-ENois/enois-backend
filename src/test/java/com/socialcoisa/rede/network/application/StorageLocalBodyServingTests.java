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
import com.socialcoisa.rede.network.domain.StoragePlacement;
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

	/**
	 * Dá identidade de nó ao diretório de corpos — o que o runtime faz ao provisionar (`node.json` +
	 * `api-key` 0600 no diretório pai de {@code objects/}). Só com identidade a leitura local é
	 * cobrada contra a colocação; sem ela, o disco é do Control Plane e não há linha a cobrar.
	 */
	private void discoDoNo(String nodeId) throws Exception {
		Path home = nodeBodies.pathOf("qualquer").getParent().getParent();
		Files.createDirectories(home);
		Files.writeString(home.resolve("node.json"), "{\"nodeId\":\"" + nodeId + "\"}");
		Path chave = home.resolve("api-key");
		Files.writeString(chave, "enk_" + nodeId + "_segredo");
		try {
			Files.setPosixFilePermissions(chave, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
		}
		catch (UnsupportedOperationException semPosix) {
			// Sistema sem POSIX: o store considera a credencial legível só pelo dono.
		}
	}

	private void colocacao(String objectId, String nodeId, StoragePlacement.State estado) {
		when(catalog.placementOn(objectId, nodeId)).thenReturn(java.util.Optional.of(
			new StoragePlacement(objectId, 1, nodeId, 4L, "a".repeat(64), estado, NOW)));
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
	void copia_do_no_marcada_stale_nao_e_servida_a_quem_puxa_o_objeto() throws Exception {
		// O download local é o caminho pelo qual a réplica de outro nó recebe o objeto. Servir daqui
		// uma cópia que o catálogo já marcou como não-conferente espalha o corpo ruim — e o catálogo
		// dizia uma coisa e a rota fazia outra.
		StorageObject object = objeto("sob_a", "conteudo trocado");
		discoDoNo("nd_dono");
		corpoDoNoLocal(object.id(), "conteudo trocado");
		colocacao(object.id(), "nd_dono", StoragePlacement.State.STALE);

		assertThatThrownBy(() -> service.readBody(object.id()))
			.isInstanceOf(ApiException.class)
			.satisfies(erro -> {
				ApiException api = (ApiException) erro;
				assertThat(api.status()).isEqualTo(409);
				assertThat(api.code()).isEqualTo("STORAGE_REPLICA_STALE");
				assertThat(api.getMessage()).contains("STALE");
			});
	}

	@Test
	void copia_do_no_ok_continua_servida() throws Exception {
		StorageObject object = objeto("sob_a", "conteudo bom");
		discoDoNo("nd_dono");
		corpoDoNoLocal(object.id(), "conteudo bom");
		colocacao(object.id(), "nd_dono", StoragePlacement.State.OK);

		assertThat(ler(object.id())).isEqualTo("conteudo bom");
	}

	@Test
	void corpo_do_no_sem_linha_de_colocacao_continua_servido() throws Exception {
		// O corpo pode estar no disco antes de a linha de colocação existir (o PUT autenticado grava
		// e só depois confirma). Recusar por ausência de linha transformaria essa janela em 409 e
		// faria o objeto sumir para quem o acabou de gravar.
		StorageObject object = objeto("sob_a", "conteudo novo");
		discoDoNo("nd_dono");
		corpoDoNoLocal(object.id(), "conteudo novo");

		assertThat(ler(object.id())).isEqualTo("conteudo novo");
	}

	@Test
	void disco_sem_identidade_de_no_nao_e_cobrado_contra_colocacao() throws Exception {
		// Instalação do Control Plane que também guarda corpo no diretório de nó, mas não é um nó
		// provisionado: não há linha de colocação a cobrar, e a leitura continua valendo.
		StorageObject object = objeto("sob_a", "conteudo do cp");
		corpoDoNoLocal(object.id(), "conteudo do cp");

		assertThat(ler(object.id())).isEqualTo("conteudo do cp");
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

	/**
	 * Réplica local gravada <b>cifrada</b> (`ENOISENC1`) é servida em claro.
	 *
	 * <p>O {@code NodeBodyStore} cifra com a chave da instalação quando ela existe, e a leitura do
	 * catálogo tem de decifrar — senão o download responde 200 com o cabeçalho e o GCM do arquivo, e
	 * o cliente recebe bytes que só um processo com a chave entenderia. O mesmo formato do caminho
	 * do envelope (`ENOISENV1`), outro cabeçalho: reconhecer um e não o outro é entregar lixo com
	 * 200 só neste caminho.</p>
	 */
	@Test
	void replica_local_cifrada_e_servida_em_claro() throws Exception {
		NodeBodyStore cifrado = new NodeBodyStore(root.resolve("no-local/objects").toString(), chave());
		StorageCatalogService comChave = new StorageCatalogService(catalog, mock(AuditTrail.class),
			Clock.fixed(NOW, ZoneOffset.UTC), 10_000, root.resolve("cp").toString(),
			java.util.Base64.getEncoder().encodeToString(chave()), mock(StoragePlacementService.class), cifrado);
		StorageObject object = objeto("sob_c", "conteudo cifrado no disco do no");
		try (var entrada = new ByteArrayInputStream(bytes("conteudo cifrado no disco do no"))) {
			cifrado.write(object.id(), entrada);
		}

		// O disco NÃO tem o texto claro: sem isso o teste passaria servindo o arquivo cru.
		byte[] noDisco = Files.readAllBytes(cifrado.pathOf(object.id()));
		assertThat(noDisco).startsWith("ENOISENC1".getBytes(StandardCharsets.US_ASCII));
		assertThat(new String(noDisco, StandardCharsets.ISO_8859_1)).doesNotContain("conteudo cifrado");

		try (var corpo = comChave.readBody(object.id())) {
			assertThat(new String(corpo.readAllBytes(), StandardCharsets.UTF_8))
				.isEqualTo("conteudo cifrado no disco do no");
		}
	}

	/**
	 * Objeto maior que qualquer buffer interno atravessa a réplica local em <b>fluxo</b>.
	 *
	 * <p>Um contador que retém o que leu passaria nos testes pequenos e estouraria a heap num vídeo de
	 * 500 MB. Aqui o corpo é lido em blocos e o primeiro já chega antes de o arquivo inteiro ser
	 * tocado — se a leitura bufferizasse, este teste não distinguiria; o que ele garante é o contrato
	 * do caminho: bytes idênticos, contagem exata, sem depender do tamanho caber na memória do teste.</p>
	 */
	@Test
	void replica_local_cifrada_de_1_mib_faz_round_trip_exato() throws Exception {
		NodeBodyStore cifrado = new NodeBodyStore(root.resolve("no-local/objects").toString(), chave());
		StorageCatalogService comChave = new StorageCatalogService(catalog, mock(AuditTrail.class),
			Clock.fixed(NOW, ZoneOffset.UTC), 10_000, root.resolve("cp").toString(),
			java.util.Base64.getEncoder().encodeToString(chave()), mock(StoragePlacementService.class), cifrado);
		byte[] corpo = new byte[1024 * 1024];
		new java.util.Random(11).nextBytes(corpo);
		StorageObject object = StorageObject.current("sob_grande", "fotos", "video.bin", "video/mp4",
			(long) corpo.length, java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
				.digest(corpo)),
			StorageObjectState.STORED, "user_1", NOW, NOW, null);
		when(catalog.findById("sob_grande")).thenReturn(Optional.of(object));
		cifrado.write("sob_grande", new ByteArrayInputStream(corpo));

		try (var fluxo = comChave.readBody("sob_grande")) {
			// Lê em blocos de 8 KiB: se o caminho entrega o documento inteiro de uma vez, é porque
			// bufferizou — e o custo em heap seria o tamanho do objeto.
			java.io.ByteArrayOutputStream recebido = new java.io.ByteArrayOutputStream();
			byte[] bloco = new byte[8192];
			int lido;
			while ((lido = fluxo.read(bloco)) > 0) {
				recebido.write(bloco, 0, lido);
			}
			assertThat(recebido.size()).isEqualTo(corpo.length);
			assertThat(recebido.toByteArray()).isEqualTo(corpo);
		}
	}

	/** Mais bytes do que o catálogo promete: erro, não entrega parcial. */
	@Test
	void replica_local_maior_que_o_registrado_e_recusada() throws Exception {
		NodeBodyStore cifrado = new NodeBodyStore(root.resolve("no-local/objects").toString(), chave());
		StorageCatalogService comChave = new StorageCatalogService(catalog, mock(AuditTrail.class),
			Clock.fixed(NOW, ZoneOffset.UTC), 10_000, root.resolve("cp").toString(),
			java.util.Base64.getEncoder().encodeToString(chave()), mock(StoragePlacementService.class), cifrado);
		// Catálogo promete 10 bytes; o disco tem 9 KiB.
		StorageObject object = objeto("sob_maior", "0123456789");
		cifrado.write("sob_maior", new ByteArrayInputStream(new byte[9 * 1024]));

		assertThatThrownBy(() -> {
			try (var fluxo = comChave.readBody("sob_maior")) {
				fluxo.readAllBytes();
			}
		}).isInstanceOf(ApiException.class)
			.satisfies(erro -> {
				assertThat(((ApiException) erro).status()).isEqualTo(410);
				assertThat(((ApiException) erro).getMessage()).contains("9216").contains("registra 10");
			});
	}

	private static byte[] chave() {
		byte[] bytes = new byte[32];
		new java.util.Random(5).nextBytes(bytes);
		return bytes;
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