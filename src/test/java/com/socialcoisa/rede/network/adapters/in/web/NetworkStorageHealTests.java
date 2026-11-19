package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
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
 * Healing pela rota de operador: a lacuna que o {@code /storage/scrub} aponta vira designação, o
 * nó a puxa e confirma, e a cópia passa a contar como durabilidade.
 *
 * <p><b>Por que este teste tem de ser de rota.</b> O defeito que o healing fecha acontece
 * <b>entre</b> serviços: o upload só designa réplica no instante em que grava, e um objeto que
 * ficou sem cópia (cluster sem nó naquele momento, ou réplica confirmada {@code STALE}) não tem
 * quem o designe depois. Só o caminho completo — catálogo real, colocação real, confirmação real
 * do nó — mostra a lacuna e depois o fechamento dela.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class NetworkStorageHealTests {

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
		jdbc.update("delete from network_storage_placements");
		jdbc.update("delete from network_storage_objects");
		jdbc.update("delete from network_node_keys");
		jdbc.update("delete from network_provision_tokens");
		jdbc.update("delete from network_nodes");
	}

	record Node(String id, String key) {
	}

	/** Nó completo pelo caminho real: token → enroll → aprovação → chave → heartbeat. */
	private Node node(String... profiles) throws Exception {
		String admin = adminToken();
		String name = "heal-" + UUID.randomUUID().toString().substring(0, 8);
		var tokenResult = mockMvc
			.perform(post("/admin/network/nodes/provision-token").header("X-Session-Token", admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(
					Map.of("displayName", name, "ownerUserId", "owner-" + name, "profiles", List.of(profiles)))))
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
		mockMvc.perform(post("/network/nodes/heartbeat").header("X-Node-Id", nodeId).header("X-Node-Key", key)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("runtimeVersion", "0.1.0", "observed",
				Map.of("cpuPercent", 5.0, "memoryMb", 1024, "runningTasks", 0, "diskFreeMb", 51200)))))
			.andExpect(status().isOk());
		return new Node(nodeId, key);
	}

	/**
	 * Objeto servível com a cópia de origem no Control Plane e <b>nenhuma</b> linha para outro nó:
	 * o estado de um upload feito quando não havia nó vivo.
	 *
	 * <p>O corpo tem de existir de verdade no CP para o nó poder puxá-lo — catálogo prometendo um
	 * arquivo que não está lá é exatamente o que o healing NÃO pode curar. Como a rota de PUT só
	 * grava por um nó com o perfil, a origem é um nó de armazenamento próprio, tirado da disputa
	 * logo depois: o teste prova o corpo real sem deixar uma segunda réplica disponível.</p>
	 */
	private String storedObjectWithoutReplica(String bucket, String key, byte[] content) throws Exception {
		var admin = adminToken();
		String objectId = "sob_heal_" + UUID.randomUUID().toString().substring(0, 8);
		jdbc.update("""
			insert into network_storage_objects (id, bucket, object_key, content_type, bytes, sha256, state,
			  created_by, created_at, updated_at)
			values (?, ?, ?, 'image/jpeg', null, null, 'PENDING', 'u', now(3), now(3))
			""", objectId, bucket, key);
		// Quem mede é o CP: a rota de PUT grava bytes/sha e a colocação da réplica 0 num só lugar.
		Node origem = node("NODE_STORAGE");
		mockMvc
			.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.put("/network/storage/objects/" + objectId)
				.header("X-Node-Id", origem.id())
				.header("X-Node-Key", origem.key())
				.contentType(MediaType.APPLICATION_OCTET_STREAM)
				.content(content))
			.andExpect(status().isOk());
		// O corpo continua no CP (é a réplica 0); a origem sai da disputa para o teste saber QUEM
		// o healing escolhe, em vez de aceitar qualquer nó vivo.
		jdbc.update("update network_nodes set status = 'SUSPENDED' where id = ?", origem.id());
		return objectId;
	}

	private static String sha256(byte[] content) {
		try {
			return java.util.HexFormat.of()
				.formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content));
		}
		catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private List<Map<String, Object>> placements(String objectId) {
		return jdbc.queryForList(
			"select replica, node_id, state from network_storage_placements where object_id = ? order by replica",
			objectId);
	}

	private String adminToken() throws Exception {
		var session = auth.authenticate("net_heal_admin");
		TestDatabase.setAdmin(session.userId());
		return session.token();
	}

	@Test
	void oHealingExigeAdmin() throws Exception {
		mockMvc.perform(post("/admin/network/nodes/storage/heal")).andExpect(status().isUnauthorized());
	}

	@Test
	void dryRunApontaONoEscolhidoSemGravarDesignacao() throws Exception {
		String admin = adminToken();
		String objectId = storedObjectWithoutReplica("heal", "foto.jpg", "conteudo".getBytes());
		Node storage = node("NODE_STORAGE");

		mockMvc.perform(post("/admin/network/nodes/storage/heal").header("X-Session-Token", admin)
			.param("dryRun", "true"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.dryRun").value(true))
			.andExpect(jsonPath("$.replicasExpected").value(2))
			.andExpect(jsonPath("$.designated.length()").value(1))
			.andExpect(jsonPath("$.designated[0].objectId").value(objectId))
			.andExpect(jsonPath("$.designated[0].nodeId").value(storage.id()));

		// Nada gravado: ver o efeito não pode custar o tráfego.
		assertThat(placements(objectId)).hasSize(1);
		assertThat(placements(objectId).get(0).get("state")).isEqualTo("OK");
	}

	@Test
	void healingDesignaOnoEeleFazACopiaAteContarbeComoDurabilidade() throws Exception {
		String admin = adminToken();
		byte[] content = "conteudo-do-objeto".getBytes();
		String objectId = storedObjectWithoutReplica("heal", "foto.jpg", content);
		Node storage = node("NODE_STORAGE");

		// Antes: o catálogo promete 2 cópias e só tem 1 -- a lacuna que o scrub aponta.
		mockMvc.perform(get("/admin/network/nodes/storage/scrub").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.replicasExpected").value(2))
			.andExpect(jsonPath("$.underReplicated.length()").value(1));

		mockMvc.perform(post("/admin/network/nodes/storage/heal").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.dryRun").value(false))
			.andExpect(jsonPath("$.designated.length()").value(1))
			.andExpect(jsonPath("$.designated[0].nodeId").value(storage.id()))
			.andExpect(jsonPath("$.skipped.length()").value(0));

		// A designação é PENDENTE: o catálogo ainda não tem cópia a mais, só um pedido.
		var depois = placements(objectId);
		assertThat(depois).hasSize(2);
		assertThat(depois.get(1).get("state")).isEqualTo("PENDING_REMOTE");
		assertThat(depois.get(1).get("node_id")).isEqualTo(storage.id());
		assertThat(scrrubStillShows(objectId)).as("designacao nao e durabilidade").isTrue();

		// O nó busca o corpo e confirma com o que mediu -- agora a lacuna some.
		mockMvc
			.perform(post("/network/storage/objects/" + objectId + "/replica").header("X-Node-Id", storage.id())
				.header("X-Node-Key", storage.key()).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("bytes", content.length, "sha256", sha256(content)))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("OK"));

		assertThat(scrrubStillShows(objectId)).as("depois da confirmacao a lacuna fecha").isFalse();
	}

	private boolean scrrubStillShows(String objectId) throws Exception {
		String admin = adminToken();
		String json = mockMvc.perform(get("/admin/network/nodes/storage/scrub").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return json.contains(objectId);
	}

	/**
	 * A cópia que existe e <b>não confere</b> é lacuna para a cura, e não só para o relatório.
	 *
	 * <p>Antes, a cura varria sem reler corpo e sem olhar o catálogo: a cópia ficava {@code STALE}
	 * para sempre, o espaço dela continuava contando como ocupado e cada rodada designava uma
	 * réplica nova ao lado da queimada.</p>
	 */
	@Test
	void copiaDivergenteViraEspacoLivreNaCura() throws Exception {
		String admin = adminToken();
		byte[] content = "conteudo-divergente".getBytes();
		String objectId = storedObjectWithoutReplica("heal", "foto.jpg", content);
		Node storage = node("NODE_STORAGE");
		mockMvc.perform(post("/admin/network/nodes/storage/heal").header("X-Session-Token", admin))
			.andExpect(status().isOk());
		// O nó grava a cópia com OUTRO conteúdo e confirma: o catálogo registra divergência e a
		// colocação vai para STALE.
		mockMvc
			.perform(post("/network/storage/objects/" + objectId + "/replica").header("X-Node-Id", storage.id())
				.header("X-Node-Key", storage.key()).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(
					Map.of("bytes", content.length, "sha256", sha256("outro-conteudo".getBytes())))))
			.andExpect(status().isConflict());
		assertThat(placements(objectId).stream().filter(p -> "STALE".equals(p.get("state")))).hasSize(1);

		mockMvc.perform(post("/admin/network/nodes/storage/heal").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.repostos.length()").value(1))
			.andExpect(jsonPath("$.repostos[0].action").value("SERIALIZED"))
			.andExpect(jsonPath("$.designated.length()").value(1));
		// A queimada saiu do caminho: o espaço é devolvido e a réplica nova sai depois da maior.
		var depois = placements(objectId);
		assertThat(depois.stream().filter(p -> "STALE".equals(p.get("state")))).isEmpty();
		assertThat(depois.stream().filter(p -> "GONE".equals(p.get("state")))).hasSize(1);
		assertThat(depois.stream().filter(p -> "PENDING_REMOTE".equals(p.get("state")))).hasSize(1);
	}

	@Test
	void objetoQueJaTemDesignacaoPendenteNaoEReescolhido() throws Exception {
		String admin = adminToken();
		String objectId = storedObjectWithoutReplica("heal", "foto.jpg", "abc".getBytes());
		Node storage = node("NODE_STORAGE");
		mockMvc.perform(post("/admin/network/nodes/storage/heal").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.designated.length()").value(1));

		// Segunda rodada sem o nó confirmar: a lacuna continua, mas já tem dono. Designar de novo
		// só reiniciaria o relógio do retry e contaria como trabalho feito.
		mockMvc.perform(post("/admin/network/nodes/storage/heal").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.designated.length()").value(0))
			.andExpect(jsonPath("$.skipped[0].reason").value("ALREADY_DESIGNATED"));

		assertThat(placements(objectId)).hasSize(2);
	}
}