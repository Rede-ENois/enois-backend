package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestDatabase;

import tools.jackson.databind.ObjectMapper;

/**
 * O Énóis Storage pela rota real: catálogo, corpo binário e credencial de nó.
 *
 * <p>O que este teste fixa, acima da unidade do serviço:</p>
 * <ul>
 * <li>o upload passa pela <b>API key do nó</b>, não por sessão de usuário — o Storage não inventa
 * identidade (brief §2);</li>
 * <li>o tamanho e o hash que a rota devolve são os do <b>corpo recebido</b>, não um campo do
 * cliente — o mesmo critério que vale no serviço, aqui atravessando HTTP;</li>
 * <li>um nó sem o perfil {@code NODE_STORAGE} recebe 403 mesmo com a chave válida: disco não é
 * permissão;</li>
 * <li>o download de objeto pendente é 404, e o de objeto gravado devolve os bytes idênticos com
 * {@code ETag} igual ao sha-256 — é o contrato do gateway S3 básico da fase 1.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Tag("critico")
class NetworkStorageTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	private String adminToken() throws Exception {
		var session = auth.authenticate("net_storage_admin_" + System.nanoTime());
		TestDatabase.setAdmin(session.userId());
		return session.token();
	}

	/** Nó completo pelo caminho real: token → enroll → aprovação → chave → heartbeat ACTIVE. */
	private Node node(String admin, List<String> profiles) throws Exception {
		String name = "storage-" + UUID.randomUUID().toString().substring(0, 8);
		var tokenResult = mockMvc
			.perform(post("/admin/network/nodes/provision-token").header("X-Session-Token", admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(
					Map.of("displayName", name, "ownerUserId", "owner-" + name, "profiles", profiles))))
			.andExpect(status().isOk())
			.andReturn();
		String provisionToken = objectMapper.readTree(tokenResult.getResponse().getContentAsString()).get("token")
			.asString();

		var enroll = mockMvc
			.perform(post("/network/nodes/enroll").contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("provisionToken", provisionToken, "declared",
					Map.of("cpuCores", 2, "memoryMb", 4096, "diskMb", 102400, "bandwidthMbps", 100)))))
			.andExpect(status().isOk())
			.andReturn();
		String nodeId = objectMapper.readTree(enroll.getResponse().getContentAsString()).get("nodeId").asString();

		mockMvc.perform(post("/admin/network/nodes/" + nodeId + "/approve").header("X-Session-Token", admin))
			.andExpect(status().isOk());

		var keyResult = mockMvc
			.perform(post("/admin/network/nodes/" + nodeId + "/keys").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andReturn();
		String key = objectMapper.readTree(keyResult.getResponse().getContentAsString()).get("apiKey").asString();

		Node node = new Node(nodeId, key);
		heartbeat(node, null);
		return node;
	}

	/** Heartbeat com o disco livre medido; {@code null} = não reportou (runtime anterior a V321). */
	private void heartbeat(Node node, Long diskFreeMb) throws Exception {
		Map<String, Object> observed = new HashMap<>();
		observed.put("cpuPercent", 5.0);
		observed.put("memoryMb", 1024);
		observed.put("runningTasks", 0);
		if (diskFreeMb != null) {
			observed.put("diskFreeMb", diskFreeMb);
		}
		mockMvc.perform(post("/network/nodes/heartbeat").header("X-Node-Id", node.id())
			.header("X-Node-Key", node.key()).contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("runtimeVersion", "0.1.0", "observed", observed))))
			.andExpect(status().isOk());
	}

	/** O objeto no catálogo, criado como o app criaria: direto no repositório do catálogo. */
	private String catalogObject(String bucket, String key) {
		String id = "sob_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
		Instant now = Instant.now();
		jdbc.update("""
			insert into network_storage_objects (id, bucket, object_key, content_type, state, created_by, created_at, updated_at)
			values (?, ?, ?, 'application/octet-stream', 'PENDING', 'user-test', ?, ?)
			""", id, bucket, key, now, now);
		return id;
	}

	private static byte[] body(String text) {
		return text.getBytes(StandardCharsets.UTF_8);
	}

	private static String sha256(byte[] body) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
	}

	@Test
	void o_no_com_perfil_grava_o_corpo_e_o_catalogo_guarda_o_medido() throws Exception {
		String admin = adminToken();
		Node node = node(admin, List.of("NODE_STORAGE"));
		String bucket = "fotos-" + UUID.randomUUID().toString().substring(0, 8);
		String id = catalogObject(bucket, "gato.jpg");
		byte[] content = body("bytes do gato");

		mockMvc.perform(put("/network/storage/objects/" + id).header("X-Node-Id", node.id())
			.header("X-Node-Key", node.key()).contentType(MediaType.APPLICATION_OCTET_STREAM).content(content))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("STORED"))
			.andExpect(jsonPath("$.bytes").value(content.length))
			.andExpect(jsonPath("$.sha256").value(sha256(content)));

		mockMvc.perform(get("/network/storage/objects/" + id + "/location").header("X-Node-Id", node.id())
			.header("X-Node-Key", node.key()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("STORED"))
			.andExpect(jsonPath("$.bytes").value(content.length))
			.andExpect(jsonPath("$.nodes[0]").value(node.id()));
	}

	@Test
	void o_download_devolve_os_bytes_gravados_com_etag_do_sha256() throws Exception {
		String admin = adminToken();
		Node node = node(admin, List.of("NODE_STORAGE"));
		String bucket = "fotos-" + UUID.randomUUID().toString().substring(0, 8);
		String id = catalogObject(bucket, "cachorro.jpg");
		byte[] content = body("conteudo binario do cachorro");

		mockMvc.perform(put("/network/storage/objects/" + id).header("X-Node-Id", node.id())
			.header("X-Node-Key", node.key()).contentType(MediaType.APPLICATION_OCTET_STREAM).content(content))
			.andExpect(status().isOk());

		MvcResult download = mockMvc
			.perform(get("/network/storage/objects/" + id).header("X-Node-Id", node.id())
				.header("X-Node-Key", node.key()))
			.andExpect(status().isOk())
			.andReturn();

		// O corpo sai na thread de streaming: espera o buffer encher, como nos testes de mídia.
		byte[] received;
		long limit = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
		do {
			received = download.getResponse().getContentAsByteArray();
			if (received.length == content.length || System.nanoTime() > limit) {
				break;
			}
			Thread.sleep(10);
		}
		while (true);

		assertThat(received).isEqualTo(content);
		assertThat(download.getResponse().getHeader("ETag")).contains(sha256(content));
		assertThat(download.getResponse().getHeader("Content-Length")).isEqualTo(String.valueOf(content.length));
	}

	@Test
	void o_no_sem_perfil_de_storage_recebe_403_mesmo_com_a_chave_valida() throws Exception {
		String admin = adminToken();
		Node worker = node(admin, List.of("NODE_WORKER"));
		String bucket = "fotos-" + UUID.randomUUID().toString().substring(0, 8);
		String id = catalogObject(bucket, "proibido.jpg");

		mockMvc.perform(put("/network/storage/objects/" + id).header("X-Node-Id", worker.id())
			.header("X-Node-Key", worker.key()).contentType(MediaType.APPLICATION_OCTET_STREAM).content(body("x")))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NODE_PROFILE_REQUIRED"));
	}

	@Test
	void o_disco_livre_reportado_no_heartbeat_decide_se_o_no_recebe_o_corpo() throws Exception {
		String admin = adminToken();
		Node store = node(admin, List.of("NODE_STORAGE"));
		String bucket = "fotos-" + UUID.randomUUID().toString().substring(0, 8);
		String id = catalogObject(bucket, "apertado.jpg");

		// O nó MEDIU o disco e reportou menos que o piso. O `declared.diskMb` do enroll continua em
		// 102400 MB — a promessa não muda; o que decide é a medida, como no escalonamento de tarefas.
		heartbeat(store, 512L);

		mockMvc.perform(put("/network/storage/objects/" + id).header("X-Node-Id", store.id())
			.header("X-Node-Key", store.key()).contentType(MediaType.APPLICATION_OCTET_STREAM).content(body("abc")))
			.andExpect(status().is(507))
			.andExpect(jsonPath("$.code").value("STORAGE_NO_DISK_SPACE"));

		// E com espaço reportado o mesmo objeto passa — senão o teste estaria provando só que a rota
		// responde erro para qualquer coisa.
		heartbeat(store, 102400L);
		mockMvc.perform(put("/network/storage/objects/" + id).header("X-Node-Id", store.id())
			.header("X-Node-Key", store.key()).contentType(MediaType.APPLICATION_OCTET_STREAM).content(body("abc")))
			.andExpect(status().isOk());
	}

	@Test
	void sem_credencial_de_no_o_storage_nao_aceita_nada() throws Exception {
		String bucket = "fotos-" + UUID.randomUUID().toString().substring(0, 8);
		String id = catalogObject(bucket, "sem-chave.jpg");

		mockMvc.perform(put("/network/storage/objects/" + id).contentType(MediaType.APPLICATION_OCTET_STREAM)
			.content(body("x"))).andExpect(status().isUnauthorized());
	}

	@Test
	void objeto_pendente_nao_baixa_e_objeto_inexistente_tambem_nao() throws Exception {
		String admin = adminToken();
		Node node = node(admin, List.of("NODE_STORAGE"));
		String bucket = "fotos-" + UUID.randomUUID().toString().substring(0, 8);
		String id = catalogObject(bucket, "pendente.jpg");

		mockMvc.perform(get("/network/storage/objects/" + id).header("X-Node-Id", node.id())
			.header("X-Node-Key", node.key())).andExpect(status().isNotFound());

		mockMvc.perform(get("/network/storage/objects/sob_nao_existe").header("X-Node-Id", node.id())
			.header("X-Node-Key", node.key())).andExpect(status().isNotFound());
	}

	@Test
	void a_listagem_do_no_mostra_o_que_ele_hospeda_sem_o_conteudo() throws Exception {
		String admin = adminToken();
		Node node = node(admin, List.of("NODE_STORAGE"));
		String bucket = "fotos-" + UUID.randomUUID().toString().substring(0, 8);
		String id = catalogObject(bucket, "lista.jpg");
		byte[] content = body("listado");

		mockMvc.perform(put("/network/storage/objects/" + id).header("X-Node-Id", node.id())
			.header("X-Node-Key", node.key()).contentType(MediaType.APPLICATION_OCTET_STREAM).content(content))
			.andExpect(status().isOk());

		mockMvc.perform(get("/network/storage/placements").header("X-Node-Id", node.id())
			.header("X-Node-Key", node.key()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].objectId").value(id))
			.andExpect(jsonPath("$[0].bytes").value(content.length));
	}

	record Node(String id, String key) {
	}
}
