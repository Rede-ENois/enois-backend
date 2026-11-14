package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.media.application.MediaFfmpegRunner;
import com.socialcoisa.rede.media.application.StubFfmpegRunner;
import com.socialcoisa.rede.media.application.port.out.MediaObjectStore;
import com.socialcoisa.rede.network.application.MediaTranscodeService;
import com.socialcoisa.rede.network.application.NetworkTaskService;
import com.socialcoisa.rede.network.domain.MediaTranscodeSpec;
import com.socialcoisa.rede.network.domain.TaskType;
import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestDatabase;

import tools.jackson.databind.ObjectMapper;

/**
 * Rede cooperativa, fase 3: o transcode de mídia roda no nó {@code NODE_MEDIA} e o resultado é
 * conferido por hash e tamanho.
 *
 * <p>O que estes testes fixam, porque cada um é um jeito de errar:</p>
 * <ul>
 * <li>o nó só lê a origem e só escreve o resultado da tarefa <b>designada a ele</b> — perfil não é
 * permissão;</li>
 * <li>o Control Plane <b>mede</b> o arquivo entregue; a declaração do nó só vale se for idêntica ao
 * medido, então um hash inventado não conclui a tarefa;</li>
 * <li>resultado idêntico à origem é recusado: nada foi transcodificado;</li>
 * <li>a receita do nó é a mesma do pod — provado com ffmpeg real dos dois lados, comparando bytes;</li>
 * <li>o artefato fica em staging, fora do proxy público.</li>
 * </ul>
 */
@SpringBootTest(properties = { "rede.media.storage=s3", "rede.media.s3.bucket=bucket-de-teste",
		"rede.media.s3.publicBaseUrl=https://enois.exemplo.com/api/storage" })
@AutoConfigureMockMvc
class NetworkMediaTests {

	static {
		System.setProperty("aws.accessKeyId", "test");
		System.setProperty("aws.secretAccessKey", "test");
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private NetworkTaskService tasks;

	@Autowired
	private MediaTranscodeService media;

	@Autowired
	private MediaObjectStore store;

	@TempDir
	Path dir;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
		jdbc.update("delete from network_media_jobs");
		jdbc.update("delete from network_tasks");
		jdbc.update("delete from network_state");
		jdbc.update("delete from network_node_keys");
		jdbc.update("delete from network_provision_tokens");
		jdbc.update("delete from network_nodes");
		jdbc.update("delete from media_assets");
	}

	@TestConfiguration
	static class FakeStore {

		@Bean
		@Primary
		FakeObjectStore fakeObjectStore() {
			return new FakeObjectStore();
		}

		@Bean
		@Primary
		StubFfmpegRunner stubFfmpegRunner() {
			return new StubFfmpegRunner();
		}
	}

	/** Bucket em memória: o teste controla o que a origem contém e o que o nó entregou. */
	static class FakeObjectStore implements MediaObjectStore {

		final Map<String, byte[]> objetos = new java.util.concurrent.ConcurrentHashMap<>();

		@Override
		public StoredObject read(String key, String range) {
			byte[] conteudo = objetos.get(key);
			if (conteudo == null) {
				throw new IllegalStateException("objeto ausente: " + key);
			}
			return new StoredObject(new ByteArrayInputStream(conteudo), conteudo.length, "application/octet-stream",
				null, "\"etag\"");
		}

		@Override
		public void write(String key, String contentType, long length, InputStream body) {
			try {
				byte[] bytes = body.readAllBytes();
				if (length >= 0 && bytes.length != length) {
					throw new IllegalStateException("tamanho declarado " + length + " != lido " + bytes.length);
				}
				objetos.put(key, bytes);
			}
			catch (IOException failure) {
				throw new IllegalStateException(failure);
			}
		}
	}

	// ---------------------------------------------------------------- cenário central

	/**
	 * O teste que o plano exige: o nó executa, entrega o arquivo, e o Control Plane aceita porque o
	 * que o nó declarou é exatamente o que o arquivo entregue contém.
	 */
	@Test
	void noExecutaOTranscodeEOResultadoEhAceitoPeloQueFoiMedido() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "no-media", List.of("NODE_MEDIA"));
		byte[] origem = "origem-do-video".getBytes(StandardCharsets.UTF_8);
		byte[] resultado = "resultado-transcodificado".getBytes(StandardCharsets.UTF_8);
		String mediaId = asset("video/mp4", origem);

		var job = media.request(mediaId);
		assertThat(job.status().name()).isEqualTo("PENDING");
		assertThat(tasks.dispatchPending()).isEqualTo(1);

		// O nó lê a origem: é o objeto da tarefa, e só ele.
		var lido = mockMvc.perform(get("/network/media/" + mediaId + "/source").header("X-Node-Id", node.id())
			.header("X-Node-Key", node.key())).andExpect(status().isOk()).andReturn();
		assertCorpoEmStreaming(lido, origem);

		// O nó entrega o resultado; a resposta traz o que o Control Plane MEDIU.
		var entregue = mockMvc
			.perform(put("/network/media/" + mediaId + "/result").header("X-Node-Id", node.id())
				.header("X-Node-Key", node.key()).contentType(MediaType.parseMediaType("video/mp4")).content(resultado))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("STORED"))
			.andReturn();
		var medido = objectMapper.readTree(entregue.getResponse().getContentAsString());
		assertThat(medido.get("resultBytes").asLong()).isEqualTo(resultado.length);
		assertThat(medido.get("resultSha256").asString()).isEqualTo(sha256(resultado));

		// O nó declara exatamente o medido: aceito, e a tarefa conclui.
		String taskId = taskIdOf(mediaId);
		mockMvc
			.perform(post("/network/tasks/" + taskId + "/result").header("X-Node-Id", node.id())
				.header("X-Node-Key", node.key()).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("taskId", taskId, "attempt", 1, "status", "DONE",
					"output", objectMapper.writeValueAsString(Map.of("resultKey", medido.get("resultKey").asString(),
						"resultBytes", resultado.length, "resultSha256", sha256(resultado)))))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.applied").value(true));

		assertThat(statusOf(taskId)).isEqualTo("DONE");
		assertThat(jdbc.queryForObject("select status from network_media_jobs where media_id = ?", String.class,
				mediaId)).isEqualTo("VERIFIED");
		// O artefato está no staging, não no caminho público do asset.
		assertThat(medido.get("resultKey").asString()).startsWith(mediaId + "/file.transcoded");
	}

	/**
	 * O nó declara um hash bonito que não é o do arquivo que ele entregou. A tarefa não conclui: o
	 * que vale é o medido, não o declarado.
	 */
	@Test
	void declaracaoQueNaoBateComOArquivoNaoConcluiATarefa() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "no-media", List.of("NODE_MEDIA"));
		String mediaId = asset("video/mp4", "origem".getBytes(StandardCharsets.UTF_8));
		media.request(mediaId);
		tasks.dispatchPending();

		mockMvc.perform(put("/network/media/" + mediaId + "/result").header("X-Node-Id", node.id())
			.header("X-Node-Key", node.key()).contentType(MediaType.parseMediaType("video/mp4"))
			.content("resultado-real".getBytes(StandardCharsets.UTF_8))).andExpect(status().isOk());

		String taskId = taskIdOf(mediaId);
		mockMvc
			.perform(post("/network/tasks/" + taskId + "/result").header("X-Node-Id", node.id())
				.header("X-Node-Key", node.key()).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("taskId", taskId, "attempt", 1, "status", "DONE",
					"output", objectMapper.writeValueAsString(Map.of("resultKey",
						mediaId + "/file.transcoded.mp4", "resultBytes", 13, "resultSha256", "0".repeat(64)))))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.applied").value(false))
			.andExpect(jsonPath("$.reason").value("MEDIA_RESULT_MISMATCH"));

		assertThat(statusOf(taskId)).isEqualTo("FAILED");
		assertThat(jdbc.queryForObject("select status from network_media_jobs where media_id = ?", String.class,
				mediaId)).isEqualTo("REJECTED");
	}

	/** Arquivo idêntico à origem: o nó não transcodificou nada, e isso não é sucesso. */
	@Test
	void resultadoIdenticoAOrigemEhRecusado() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "no-media", List.of("NODE_MEDIA"));
		byte[] origem = "mesmo-arquivo".getBytes(StandardCharsets.UTF_8);
		String mediaId = asset("video/mp4", origem);
		media.request(mediaId);
		tasks.dispatchPending();

		mockMvc.perform(put("/network/media/" + mediaId + "/result").header("X-Node-Id", node.id())
			.header("X-Node-Key", node.key()).contentType(MediaType.parseMediaType("video/mp4")).content(origem))
			.andExpect(status().isOk());

		String taskId = taskIdOf(mediaId);
		mockMvc
			.perform(post("/network/tasks/" + taskId + "/result").header("X-Node-Id", node.id())
				.header("X-Node-Key", node.key()).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("taskId", taskId, "attempt", 1, "status", "DONE",
					"output", objectMapper.writeValueAsString(Map.of("resultKey",
						mediaId + "/file.transcoded.mp4", "resultBytes", origem.length, "resultSha256",
						sha256(origem)))))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.applied").value(false))
			.andExpect(jsonPath("$.reason").value("MEDIA_NOT_TRANSCODED"));
	}

	// ---------------------------------------------------------------- permissão

	@Test
	void noSemPerfilDeMidiaNaoLeNemEscreve() throws Exception {
		String admin = adminToken();
		Node worker = activeNode(admin, "no-worker", List.of("NODE_WORKER"));
		String mediaId = asset("video/mp4", "origem".getBytes(StandardCharsets.UTF_8));
		media.request(mediaId);

		mockMvc.perform(get("/network/media/" + mediaId + "/source").header("X-Node-Id", worker.id())
			.header("X-Node-Key", worker.key())).andExpect(status().isForbidden());
		mockMvc.perform(put("/network/media/" + mediaId + "/result").header("X-Node-Id", worker.id())
			.header("X-Node-Key", worker.key()).contentType(MediaType.parseMediaType("video/mp4")).content("x".getBytes()))
			.andExpect(status().isForbidden());
	}

	/**
	 * Perfil certo, tarefa de outro nó: o nó de mídia não lê a origem nem escreve o resultado. Sem
	 * esta checagem, qualquer nó com o perfil leria e escreveria qualquer mídia.
	 */
	@Test
	void noDeMidiaNaoAcessaTarefaDesignadaAOutroNo() throws Exception {
		String admin = adminToken();
		Node first = activeNode(admin, "no-media-a", List.of("NODE_MEDIA"));
		Node second = activeNode(admin, "no-media-b", List.of("NODE_MEDIA"));
		String mediaId = asset("video/mp4", "origem".getBytes(StandardCharsets.UTF_8));
		media.request(mediaId);
		tasks.dispatchPending();

		String assigned = jdbc.queryForObject("select assigned_node_id from network_tasks where task_type = ?",
			String.class, TaskType.MEDIA_TRANSCODE.name());
		Node owner = assigned.equals(first.id()) ? first : second;
		Node other = assigned.equals(first.id()) ? second : first;

		mockMvc.perform(get("/network/media/" + mediaId + "/source").header("X-Node-Id", owner.id())
			.header("X-Node-Key", owner.key())).andExpect(status().isOk());
		mockMvc.perform(get("/network/media/" + mediaId + "/source").header("X-Node-Id", other.id())
			.header("X-Node-Key", other.key())).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("MEDIA_NOT_ASSIGNED"));
	}

	@Test
	void rotaDeMidiaSemCredencialDeNoEh401() throws Exception {
		mockMvc.perform(get("/network/media/media_x/source")).andExpect(status().isUnauthorized());
		mockMvc.perform(put("/network/media/media_x/result").contentType(MediaType.parseMediaType("video/mp4")).content("x".getBytes()))
			.andExpect(status().isUnauthorized());
	}

	// ---------------------------------------------------------------- receita e paridade

	/**
	 * A receita do nó é a do pod, argumento por argumento. Se divergir, o arquivo do nó deixa de ser
	 * o que o pod produziria — e o hash só provaria que o nó mandou o que ele mesmo fez.
	 */
	@Test
	void receitaDoNoEhIdenticaAReceitaDoPod() {
		Path entrada = dir.resolve("entrada.mp4");
		Path saida = dir.resolve("saida.mp4");
		for (String tipo : MediaTranscodeSpec.VIDEO_TYPES) {
			List<String> doNo = new MediaTranscodeSpec("media_1", "media_1/file.mp4", tipo, 10, "a".repeat(64),
				MediaTranscodeSpec.resultKeyFor("media_1", tipo), 1000).transcodeArgs("/usr/bin/ffmpeg",
					entrada.toString(), saida.toString());
			List<String> doPod = MediaFfmpegRunner.transcodeArgsFor("/usr/bin/ffmpeg", tipo, entrada, saida);
			assertThat(doNo).as("receita de " + tipo).isEqualTo(doPod);
		}
	}

	/**
	 * A prova do plano: "transcode real em nó; comparação do resultado com o local". Roda o ffmpeg de
	 * verdade com a receita do nó e com a do pod sobre o mesmo arquivo e compara os bytes.
	 *
	 * <p>Pulado quando o ffmpeg não está no PATH — o CI não o instala. A paridade de argumentos acima
	 * roda em todo ambiente e é o que impede a divergência de reaparecer.</p>
	 */
	@Test
	void transcodeRealDoNoProduzOsMesmosBytesQueOLocal() throws Exception {
		Assumptions.assumeTrue(ffmpegAvailable(),
			"ffmpeg não está no PATH desta máquina; a prova do transcode real exige o binário");

		Path origem = dir.resolve("origem.mp4");
		Process gerar = new ProcessBuilder("ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi",
			"-i", "testsrc=duration=2:size=320x240:rate=10", "-c:v", "libx264", "-pix_fmt", "yuv420p",
			origem.toString()).redirectErrorStream(true).start();
		assertThat(gerar.waitFor()).as("geração do vídeo de entrada").isZero();

		Path doNo = dir.resolve("no.mp4");
		Path doPod = dir.resolve("pod.mp4");
		MediaTranscodeSpec spec = new MediaTranscodeSpec("media_1", "media_1/file.mp4", "video/mp4",
			Files.size(origem), sha256(Files.readAllBytes(origem)), MediaTranscodeSpec.resultKeyFor("media_1",
				"video/mp4"), 150L * 1024 * 1024);
		rodar(spec.transcodeArgs("ffmpeg", origem.toString(), doNo.toString()));
		rodar(MediaFfmpegRunner.transcodeArgsFor("ffmpeg", "video/mp4", origem, doPod));

		assertThat(Files.size(doNo)).isPositive();
		assertThat(sha256(Files.readAllBytes(doNo))).as("o nó e o pod produzem o mesmo arquivo")
			.isEqualTo(sha256(Files.readAllBytes(doPod)));
	}

	// ---------------------------------------------------------------- painel

	@Test
	void painelMostraOsTranscodesEAsContagens() throws Exception {
		String admin = adminToken();
		activeNode(admin, "no-media", List.of("NODE_MEDIA"));
		String mediaId = asset("video/mp4", "origem".getBytes(StandardCharsets.UTF_8));
		media.request(mediaId);

		mockMvc.perform(get("/admin/network/media").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].mediaId").value(mediaId))
			.andExpect(jsonPath("$[0].status").value("PENDING"));
		mockMvc.perform(get("/admin/network/media/overview").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.pending").value(1));
	}

	@Test
	void apenasAdminVeOsTranscodes() throws Exception {
		var member = auth.authenticate("net_media_member");
		mockMvc.perform(get("/admin/network/media").header("X-Session-Token", member.token()))
			.andExpect(status().isForbidden());
	}

	// ---------------------------------------------------------------- helpers

	private record Node(String id, String key) {
	}

	private String adminToken() throws Exception {
		var session = auth.authenticate("net_media_admin");
		TestDatabase.setAdmin(session.userId());
		return session.token();
	}

	/** Cria o asset e o objeto de origem no bucket falso; devolve o id da mídia. */
	private String asset(String mimeType, byte[] conteudo) {
		String mediaId = "media_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		String objectKey = mediaId + "/file" + (mimeType.equals("video/mp4") ? ".mp4" : ".bin");
		jdbc.update("""
			insert into media_assets (id, owner_id, file_name, mime_type, size_bytes, purpose, object_key,
				cdn_url, status, delete_status, created_at, expires_at)
			values (?, 'owner-media', ?, ?, ?, 'POST_VIDEO', ?, ?, 'READY', 'NONE', ?, ?)
			""", mediaId, objectKey, mimeType, conteudo.length, objectKey, "https://cdn.test/" + objectKey,
			Instant.now(), Instant.now().plusSeconds(3600));
		((FakeObjectStore) store).objetos.put(objectKey, conteudo);
		return mediaId;
	}

	private String taskIdOf(String mediaId) {
		return jdbc.queryForObject("select task_id from network_media_jobs where media_id = ?", String.class, mediaId);
	}

	private String statusOf(String taskId) {
		return jdbc.queryForObject("select status from network_tasks where id = ?", String.class, taskId);
	}

	private Node activeNode(String admin, String name, List<String> profiles) throws Exception {
		var tokenResult = mockMvc
			.perform(post("/admin/network/nodes/provision-token").header("X-Session-Token", admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(
					Map.of("displayName", name, "ownerUserId", "owner-" + name, "profiles", profiles))))
			.andExpect(status().isOk())
			.andReturn();
		String provisionToken = objectMapper.readTree(tokenResult.getResponse().getContentAsString())
			.get("token")
			.asString();

		var enrollResult = mockMvc
			.perform(post("/network/nodes/enroll").contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("provisionToken", provisionToken, "declared",
					Map.of("cpuCores", 4, "memoryMb", 8192, "diskMb", 51200, "bandwidthMbps", 100)))))
			.andExpect(status().isOk())
			.andReturn();
		String nodeId = objectMapper.readTree(enrollResult.getResponse().getContentAsString()).get("nodeId")
			.asString();

		mockMvc.perform(post("/admin/network/nodes/" + nodeId + "/approve").header("X-Session-Token", admin))
			.andExpect(status().isOk());

		var keyResult = mockMvc
			.perform(post("/admin/network/nodes/" + nodeId + "/keys").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andReturn();
		String key = objectMapper.readTree(keyResult.getResponse().getContentAsString()).get("apiKey").asString();

		Node node = new Node(nodeId, key);
		mockMvc
			.perform(post("/network/nodes/heartbeat").header("X-Node-Id", node.id())
				.header("X-Node-Key", node.key()).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("runtimeVersion", "0.1.0", "observed",
					Map.of("cpuPercent", 5.0, "memoryMb", 1024, "runningTasks", 0)))))
			.andExpect(status().isOk());
		return node;
	}

	/**
	 * O corpo da leitura é escrito na thread de streaming ({@code StreamingResponseBody}): o
	 * {@code perform} pode voltar antes de o buffer estar completo, e na suíte inteira isso aparecia
	 * como falha intermitente de corpo vazio. Espera o buffer encher, como em
	 * {@code MediaStorageProxyTests.assertCorpoEmStreaming}.
	 */
	private static void assertCorpoEmStreaming(MvcResult resultado, byte[] esperado) throws InterruptedException {
		long limite = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
		byte[] corpo;
		do {
			corpo = resultado.getResponse().getContentAsByteArray();
			if (corpo.length == esperado.length || System.nanoTime() > limite) {
				break;
			}
			Thread.sleep(10);
		}
		while (true);
		assertThat(corpo).isEqualTo(esperado);
	}

	private static void rodar(List<String> comando) throws Exception {
		Process processo = new ProcessBuilder(comando).redirectErrorStream(true).start();
		String saida = new String(processo.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertThat(processo.waitFor()).as("ffmpeg: " + saida).isZero();
	}

	private static boolean ffmpegAvailable() {
		try {
			Process process = new ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start();
			process.getInputStream().readAllBytes();
			return process.waitFor() == 0;
		}
		catch (IOException | InterruptedException absent) {
			return false;
		}
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (java.security.NoSuchAlgorithmException impossible) {
			throw new IllegalStateException(impossible);
		}
	}
}