package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestDatabase;

import tools.jackson.databind.ObjectMapper;

/**
 * A coleta de lápides do Storage (brief §14) pela rota real.
 *
 * <p>O que estes testes seguram:</p>
 * <ul>
 * <li><b>lápide com cópia some da durabilidade</b> — cópia de objeto removido vira {@code GONE}, e
 * a varredura deixa de contar o objeto na lacuna uma vez que a lápide é recolhida;</li>
 * <li><b>a janela protege</b> — lápide recente não é tocada, porque a remoção pode ser desfeita;</li>
 * <li><b>{@code dryRun} não grava</b> — o relatório diz o que sairia sem apagar nada.</li>
 * </ul>
 */
@SpringBootTest(properties = "rede.storage.node-body-root=target/test-tombstone-node/objects")
@AutoConfigureMockMvc
class NetworkStorageTombstoneTests {

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
		var session = auth.authenticate("net_tomb_admin_" + System.nanoTime());
		TestDatabase.setAdmin(session.userId());
		return session.token();
	}

	/**
	 * Objeto no catálogo com uma cópia OK e uma pendente. {@code deletedAt} decide se é lápide:
	 * quando não é nulo, o objeto nasce {@code DELETED}, como a remoção deixa.
	 */
	private String object(String bucket, String key, Instant deletedAt) throws Exception {
		return object(bucket, key, deletedAt, "nd_outro_no");
	}

	private String object(String bucket, String key, Instant deletedAt, String nodeId) throws Exception {
		String id = "sob_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
		byte[] content = ("corpo " + id).getBytes(StandardCharsets.UTF_8);
		String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
		Instant now = Instant.now();
		jdbc.update("""
			insert into network_storage_objects (id, bucket, object_key, content_type, bytes, sha256, state,
			  created_by, created_at, updated_at, deleted_at)
			values (?, ?, ?, 'application/octet-stream', ?, ?, ?, 'user-test', ?, ?, ?)
			""", id, bucket, key, (long) content.length, sha, deletedAt == null ? "STORED" : "DELETED", now, now,
			deletedAt);
		jdbc.update("""
			insert into network_storage_placements (object_id, replica, node_id, bytes, sha256, state, verified_at)
			values (?, 0, ?, ?, ?, 'OK', ?)
			""", id, "nd_control_plane", (long) content.length, sha, now);
		jdbc.update("""
			insert into network_storage_placements (object_id, replica, node_id, bytes, sha256, state, verified_at)
			values (?, 1, ?, ?, ?, 'PENDING_REMOTE', ?)
			""", id, nodeId, (long) content.length, sha, now);
		return id;
	}

	/**
	 * Nó completo pelo fluxo real (token → enroll → aprovação → chave → heartbeat). O despacho
	 * consulta o cadastro: designar a um id que não existe é "nó revogado", e a tarefa não nasce.
	 */
	private String activeStorageNode(String admin) throws Exception {
		String name = "tomb-" + UUID.randomUUID().toString().substring(0, 8);
		var tokenResult = mockMvc
			.perform(post("/admin/network/nodes/provision-token").header("X-Session-Token", admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(
					Map.of("displayName", name, "ownerUserId", "owner-" + name, "profiles", List.of("NODE_STORAGE")))))
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
		String nodeId = objectMapper.readTree(enroll.getResponse().getContentAsString()).get("nodeId")
			.asString();
		mockMvc.perform(post("/admin/network/nodes/" + nodeId + "/approve").header("X-Session-Token", admin))
			.andExpect(status().isOk());
		var keyResult = mockMvc
			.perform(post("/admin/network/nodes/" + nodeId + "/keys").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andReturn();
		String key = objectMapper.readTree(keyResult.getResponse().getContentAsString()).get("apiKey").asString();
		mockMvc.perform(post("/network/nodes/heartbeat").header("X-Node-Id", nodeId).header("X-Node-Key", key)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("runtimeVersion", "0.1.0", "observed",
				Map.of("cpuPercent", 5.0, "memoryMb", 1024, "runningTasks", 0, "diskFreeMb", 51200)))))
			.andExpect(status().isOk());
		return nodeId;
	}

	private String placementState(String objectId, int replica) {
		return jdbc.queryForObject("select state from network_storage_placements where object_id = ? and replica = ?",
			String.class, objectId, replica);
	}

	@Test
	void a_lapide_vencida_tem_as_copias_recolhidas() throws Exception {
		String admin = adminToken();
		String bucket = "tomb-" + UUID.randomUUID().toString().substring(0, 8);
		String id = object(bucket, "apagado.jpg", Instant.now().minusSeconds(4 * 24 * 3600));

		var resposta = mockMvc
			.perform(post("/admin/network/nodes/storage/tombstones/collect").param("dryRun", "false")
				.header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andReturn();
		var rel = objectMapper.readTree(resposta.getResponse().getContentAsString());
		// O número é de nós que deixaram de prometer o objeto, não de linhas novas.
		assertThat(rel.get("collected").get(0).get("copiesCollected").asInt()).isEqualTo(2);

		assertThat(placementState(id, 0)).as("copia da replica 0").isEqualTo("GONE");
		assertThat(placementState(id, 1)).as("copia pendente do outro no").isEqualTo("GONE");

		// A linha do objeto continua: a lápide é rastro, não remoção de linha. O que sai é a cópia.
		assertThat(jdbc.queryForObject("select state from network_storage_objects where id = ?", String.class, id))
			.isEqualTo("DELETED");
	}

	@Test
	void lapide_recente_nao_e_tocada() throws Exception {
		String admin = adminToken();
		String bucket = "tomb-" + UUID.randomUUID().toString().substring(0, 8);
		// Dentro da janela de retenção: o cliente pode desfazer, e apagar a cópia agora tornaria o
		// desfazer impossível sem um novo upload.
		String id = object(bucket, "agorinha.jpg", Instant.now().minusSeconds(60));

		mockMvc
			.perform(post("/admin/network/nodes/storage/tombstones/collect").param("dryRun", "false")
				.header("X-Session-Token", admin))
			.andExpect(status().isOk());
		assertThat(placementState(id, 0)).isEqualTo("OK");
		assertThat(placementState(id, 1)).isEqualTo("PENDING_REMOTE");
	}

	@Test
	void dry_run_relata_sem_gravar() throws Exception {
		String admin = adminToken();
		String bucket = "tomb-" + UUID.randomUUID().toString().substring(0, 8);
		String id = object(bucket, "seca.jpg", Instant.now().minusSeconds(4 * 24 * 3600));

		mockMvc.perform(post("/admin/network/nodes/storage/tombstones/collect").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.dryRun").value(true))
			.andExpect(jsonPath("$.collected[0].objectId").value(id));
		assertThat(placementState(id, 0)).as("dryRun nao pode mexer no catalogo").isEqualTo("OK");
		assertThat(placementState(id, 1)).isEqualTo("PENDING_REMOTE");
	}

	/**
	 * O despacho de réplica da fase 3 lia só cópias {@code OK} e procurava {@code PENDING_REMOTE} na
	 * lista — que nunca traz pendente. A designação gravada pelo upload e pelo healing nunca virava
	 * tarefa, e o objeto ficava com uma cópia para sempre sem que nada falhasse. O teste tem uma
	 * cópia OK (o CP) e uma pendente (o nó): a leitura errada devolve lista vazia e nada é despachado.
	 */
	@Test
	void o_despacho_envia_a_replica_pendente() throws Exception {
		String admin = adminToken();
		String bucket = "tomb-" + UUID.randomUUID().toString().substring(0, 8);
		String no = activeStorageNode(admin);
		String id = object(bucket, "pendente.jpg", null, no);

		mockMvc.perform(post("/admin/network/nodes/storage/objects/" + id + "/dispatch").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.dispatched[0].nodeId").value(no));

		assertThat(jdbc.queryForObject("""
			select count(*) from network_tasks where target_node_id = ? and task_type = 'OBJECT_PUT'
			""", Integer.class, no)).isPositive();
	}

	@Test
	void objeto_vivo_nao_entra_na_coleta() throws Exception {
		String admin = adminToken();
		String bucket = "tomb-" + UUID.randomUUID().toString().substring(0, 8);
		String id = object(bucket, "vivo.jpg", null);

		var resultado = mockMvc
			.perform(post("/admin/network/nodes/storage/tombstones/collect").param("dryRun", "false")
				.header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andReturn();
		var relatorio = objectMapper.readTree(resultado.getResponse().getContentAsString());
		boolean tocou = false;
		for (var coletado : relatorio.get("collected")) {
			if (id.equals(coletado.get("objectId").asString())) {
				tocou = true;
			}
		}
		assertThat(tocou).as("objeto STORED nao pode ser coletado").isFalse();
		assertThat(placementState(id, 0)).isEqualTo("OK");
	}

	@Test
	void a_coleta_exige_admin() throws Exception {
		mockMvc.perform(post("/admin/network/nodes/storage/tombstones/collect")).andExpect(status().isUnauthorized());
	}
}
