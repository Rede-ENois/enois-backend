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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestDatabase;

import tools.jackson.databind.ObjectMapper;

/**
 * A fase 3 do Storage: réplica designada, confirmada pelo nó, servida por digest e encontrada pela
 * varredura quando falta.
 *
 * <p>O que estes testes fixam, e por quê:</p>
 * <ul>
 * <li><b>designação não é durabilidade</b> — a linha {@code PENDING_REMOTE} existe, mas a varredura
 * a ignora e o objeto continua contando uma cópia. É o erro que um painel que conta linhas
 * cometeria: dizer que o objeto está em três lugares quando está em um;</li>
 * <li><b>só a réplica designada confirma</b>, e só com o digest que o Control Plane mediu —
 * confirmação de cópia que ninguém pediu, ou com outro conteúdo, não vira durabilidade;</li>
 * <li><b>o download por digest só serve a quem tem a cópia</b> — a réplica referencia pelo sha-256
 * (o id lógico é do Control Plane), e um nó sem a cópia não recebe os bytes;</li>
 * <li><b>a varredura aponta a lacuna</b> com número, e não repara nada: reparo custa disco e banda
 * de quem opera.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class NetworkStorageReplicationTests {

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
		var session = auth.authenticate("net_repl_admin_" + System.nanoTime());
		TestDatabase.setAdmin(session.userId());
		return session.token();
	}

	/** Objeto no catálogo já STORED, com digest conhecido — o que a fase 1 teria gravado. */
	private StoredObject storedObject(String bucket, String key, byte[] content, String nodeId) throws Exception {
		String id = "sob_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
		String sha = sha256(content);
		Instant now = Instant.now();
		jdbc.update("""
			insert into network_storage_objects (id, bucket, object_key, content_type, bytes, sha256, state,
			  created_by, created_at, updated_at)
			values (?, ?, ?, 'application/octet-stream', ?, ?, 'STORED', 'user-test', ?, ?)
			""", id, bucket, key, (long) content.length, sha, now, now);
		jdbc.update("""
			insert into network_storage_placements (object_id, replica, node_id, bytes, sha256, state, verified_at)
			values (?, 0, ?, ?, ?, 'OK', ?)
			""", id, nodeId, (long) content.length, sha, now);
		return new StoredObject(id, sha, content.length);
	}

	/** Nó completo pelo caminho real: token → enroll → aprovação → chave → heartbeat. */
	private Node node(List<String> profiles) throws Exception {
		String admin = adminToken();
		String name = "repl-" + UUID.randomUUID().toString().substring(0, 8);
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

		// Heartbeat com disco livre: é o que autoriza o nó a ser escolhido para réplica.
		// O status do nó depois da aprovação precisa ser ACTIVE: o log abaixo falha alto se não for.
		mockMvc.perform(post("/network/nodes/heartbeat").header("X-Node-Id", nodeId).header("X-Node-Key", key)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("runtimeVersion", "0.1.0", "observed",
				Map.of("cpuPercent", 5.0, "memoryMb", 1024, "runningTasks", 0, "diskFreeMb", 51200)))))
			.andExpect(status().isOk());
		// Estado real do nó depois do fluxo: o teste falha aqui, com o valor, em vez de num 403
		// adiante que só diria "alguma condição de perfil/status não bateu".
		String status = jdbc.queryForObject("select status from network_nodes where id = ?", String.class, nodeId);
		String profilesNoBanco = jdbc.queryForObject("select profiles from network_nodes where id = ?",
			String.class, nodeId);
		assertThat(status).as("status do no %s apos aprovacao", nodeId).isEqualTo("ACTIVE");
		assertThat(profilesNoBanco).as("perfis do no %s", nodeId).contains("NODE_STORAGE");
		return new Node(nodeId, key);
	}

	@Test
	void designacaoNaoContaComoDurabilidadeENaoTornaOObjetoRecuperavel() throws Exception {
		String admin = adminToken();
		Node replica = node(List.of("NODE_STORAGE"));
		String bucket = "repl-" + UUID.randomUUID().toString().substring(0, 8);
		StoredObject object = storedObject(bucket, "foto.jpg", body("conteudo"), "nd_control_plane");

		// O CP designa o nó; a linha nasce PENDING_REMOTE e não é cópia.
		designate(object.id(), 1, replica.id());

		// O nó tenta confirmar com um digest que não é o medido: cópia corrompida vira STALE.
		mockMvc
			.perform(post("/network/storage/objects/" + object.id() + "/replica").header("X-Node-Id", replica.id())
				.header("X-Node-Key", replica.key()).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("bytes", object.bytes(), "sha256", "0".repeat(64)))))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("STORAGE_REPLICA_MISMATCH"));
		assertThat(jdbc.queryForList("select replica, node_id, state from network_storage_placements where object_id = ?",
			object.id())).as("linhas de colocacao do objeto").hasSizeGreaterThan(0);
		assertThat(placementState(object.id(), replica.id())).as("linha do no replica").isEqualTo("STALE");

		// E depois de STALE não confirma mais: a designação foi gasta.
		mockMvc
			.perform(post("/network/storage/objects/" + object.id() + "/replica").header("X-Node-Id", replica.id())
				.header("X-Node-Key", replica.key()).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("bytes", object.bytes(), "sha256", object.sha256()))))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("STORAGE_REPLICA_ALREADY_CONFIRMED"));
	}

	@Test
	void aReplicaConfirmaComODigestMedidoEViraCopiaServivel() throws Exception {
		String admin = adminToken();
		Node replica = node(List.of("NODE_STORAGE"));
		String bucket = "repl-" + UUID.randomUUID().toString().substring(0, 8);
		StoredObject object = storedObject(bucket, "foto.jpg", body("conteudo"), "nd_control_plane");
		designate(object.id(), 1, replica.id());

		var resultado = mockMvc
			.perform(post("/network/storage/objects/" + object.id() + "/replica").header("X-Node-Id", replica.id())
				.header("X-Node-Key", replica.key()).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("bytes", object.bytes(), "sha256", object.sha256()))))
			.andReturn();
		assertThat(resultado.getResponse().getStatus()).as("corpo: %s",
			resultado.getResponse().getContentAsString()).isEqualTo(200);
		assertThat(objectMapper.readTree(resultado.getResponse().getContentAsString()).get("state").asString())
			.isEqualTo("OK");

		assertThat(placementState(object.id(), replica.id())).isEqualTo("OK");
	}

	@Test
	void soAReplicaDesignadaConfirma() throws Exception {
		String admin = adminToken();
		Node intruso = node(List.of("NODE_STORAGE"));
		String bucket = "repl-" + UUID.randomUUID().toString().substring(0, 8);
		StoredObject object = storedObject(bucket, "foto.jpg", body("conteudo"), "nd_control_plane");

		// Nenhuma designação para este nó: ele não pode entrar no catálogo de durabilidade de um
		// objeto que o Control Plane não mandou ele guardar.
		mockMvc
			.perform(post("/network/storage/objects/" + object.id() + "/replica").header("X-Node-Id", intruso.id())
				.header("X-Node-Key", intruso.key()).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("bytes", object.bytes(), "sha256", object.sha256()))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("STORAGE_REPLICA_NOT_DESIGNATED"));
	}

	@Test
	void downloadPorDigestSoServeQuemTemACopia() throws Exception {
		String admin = adminToken();
		Node dono = node(List.of("NODE_STORAGE"));
		Node semCopia = node(List.of("NODE_STORAGE"));
		String bucket = "repl-" + UUID.randomUUID().toString().substring(0, 8);
		StoredObject object = storedObject(bucket, "foto.jpg", body("conteudo"), dono.id());

		// A colocação é o que autoriza: o objeto existe (o dono tem a réplica 0), mas este nó não tem.
		// O corpo está no Control Plane, não no nó do dono — por isso o dono também não serve os bytes.
		mockMvc
			.perform(get("/network/storage/objects/by-digest/" + object.sha256()).header("X-Node-Id", semCopia.id())
				.header("X-Node-Key", semCopia.key()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("STORAGE_NOT_HELD"));
		// E o dono da réplica 0 também não serve: a colocação dele existe, mas o corpo não está com
		// ele (está no Control Plane). A resposta é 403 NOT_HELD e não um 200 vazio: "tem a linha"
		// e "tem os bytes" são coisas diferentes, e é o bytes que o download entrega.
		mockMvc
			.perform(get("/network/storage/objects/by-digest/" + object.sha256()).header("X-Node-Id", dono.id())
				.header("X-Node-Key", dono.key()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("STORAGE_NOT_HELD"));
	}

	@Test
	void digestInvalidoNaoVarreOCatalogo() throws Exception {
		String admin = adminToken();
		Node replica = node(List.of("NODE_STORAGE"));

		mockMvc
			.perform(get("/network/storage/objects/by-digest/nao-e-digest").header("X-Node-Id", replica.id())
				.header("X-Node-Key", replica.key()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("STORAGE_DIGEST_INVALID"));
	}

	@Test
	void varreduraApontaObjetoComMenosCopiasQueOPedido() throws Exception {
		String admin = adminToken();
		String bucket = "repl-scrub-" + UUID.randomUUID().toString().substring(0, 8);
		StoredObject soUma = storedObject(bucket, "uma.jpg", body("a"), "nd_control_plane");
		StoredObject duas = storedObject(bucket, "duas.jpg", body("b"), "nd_control_plane");
		// A segunda cópia do `duas` está confirmada; a do `uma` está só designada.
		jdbc.update("""
			insert into network_storage_placements (object_id, replica, node_id, bytes, sha256, state, verified_at)
			values (?, 1, 'nd_outro', ?, ?, 'OK', ?)
			""", duas.id(), duas.bytes(), duas.sha256(), Instant.now());
		jdbc.update("""
			insert into network_storage_placements (object_id, replica, node_id, bytes, sha256, state, verified_at)
			values (?, 1, 'nd_terceiro', ?, ?, 'PENDING_REMOTE', ?)
			""", soUma.id(), soUma.bytes(), soUma.sha256(), Instant.now());

		// O alvo padrão é 2 (CP + 1). `uma` tem uma confirmada; `duas` tem duas.
		var result = mockMvc
			.perform(get("/admin/network/nodes/storage/scrub").header("X-Session-Token", admin)
				.param("bucket", bucket))
			.andExpect(status().isOk())
			.andReturn();
		var report = objectMapper.readTree(result.getResponse().getContentAsString());

		assertThat(report.get("replicasExpected").asInt()).isEqualTo(2);
		var under = report.get("underReplicated");
		assertThat(under).hasSize(1);
		assertThat(under.get(0).get("objectId").asString()).isEqualTo(soUma.id());
		assertThat(under.get(0).get("confirmed").asInt()).isEqualTo(1);
		assertThat(under.get(0).get("missing").asInt()).isEqualTo(1);
	}

	private void designate(String objectId, int replica, String nodeId) {
		jdbc.update("""
			insert into network_storage_placements (object_id, replica, node_id, bytes, sha256, state, verified_at)
			values (?, ?, ?, 0, null, 'PENDING_REMOTE', ?)
			""", objectId, replica, nodeId, Instant.now());
	}

	private String placementState(String objectId, String nodeId) {
		return jdbc.queryForObject(
			"select state from network_storage_placements where object_id = ? and node_id = ?", String.class, objectId,
			nodeId);
	}

	private static byte[] body(String text) {
		return text.getBytes(StandardCharsets.UTF_8);
	}

	private static String sha256(byte[] content) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
	}

	private record Node(String id, String key) {
	}

	private record StoredObject(String id, String sha256, long bytes) {
	}

}