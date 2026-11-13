package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.network.application.NetworkTaskService;
import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestDatabase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Fase 5: a entrega de borda ({@code EDGE_DELIVER}) ponta a ponta.
 *
 * <p>O que estes testes fixam, porque cada um é um jeito de errar:</p>
 * <ul>
 * <li>o admin pede a entrega de um conteúdo por um nó específico e a tarefa nasce <b>com alvo</b>:
 * o outro nó de borda não a vê — o corpo verificado é o do cache daquele nó;</li>
 * <li>o payload carrega só o que o executor lê ({@code kind} e {@code contentId}) — campo que o
 * executor não lê é contrato mentiroso;</li>
 * <li>o veredicto positivo só conclui quando confere com o que o nó <b>confirmou</b> em
 * {@code /synced}: veredicto sem confirmação é recusado e a tarefa não conclui;</li>
 * <li>perfil inexistente é 404 na hora do pedido — não vira tarefa fadada a veredicto negativo;</li>
 * <li>quem não é admin não pede entrega, nem vê o que está em aberto.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class NetworkEdgeDeliverTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private NetworkTaskService tasks;

	private TestAuthHelper auth;

	private record Node(String id, String key) {
	}

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
		jdbc.update("delete from network_tasks");
		jdbc.update("delete from network_state");
		jdbc.update("delete from network_content_snapshots");
		jdbc.update("delete from network_content_versions");
		jdbc.update("delete from network_node_keys");
		jdbc.update("delete from network_provision_tokens");
		jdbc.update("delete from network_nodes");
	}

	/**
	 * O caminho inteiro: pedido do admin → tarefa com alvo → despacho → só o alvo recebe → o nó
	 * revalida, confirma e devolve veredicto → o veredicto só vale com confirmação por trás.
	 */
	@Test
	void veredictoSóConcluiComConfirmacaoPorTras() throws Exception {
		String admin = adminToken();
		Node target = activeNode(admin, "borda-deliver", List.of("NODE_EDGE"));
		Node other = activeNode(admin, "borda-outro", List.of("NODE_EDGE"));
		// O alvo é o menos folgado dos dois: a asserção "só o alvo recebe" não pode passar por sorteio.
		jdbc.update("update network_nodes set last_seen_at = current_timestamp, declared_cpu_cores = 2, "
			+ "observed_running_tasks = 1 where id = ?", target.id());
		jdbc.update("update network_nodes set last_seen_at = current_timestamp, declared_cpu_cores = 8, "
			+ "observed_running_tasks = 0 where id = ?", other.id());
		String username = usernameOf(auth.authenticate("edge_deliver_1"));

		// Primeiro o conteúdo existe: o próprio nó alvo lê e confirma (é o snapshot da conferência).
		MvcResult served = mockMvc
			.perform(get("/network/content/PROFILE/" + username).header("X-Node-Id", target.id())
				.header("X-Node-Key", target.key()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.changed").value(true))
			.andReturn();
		JsonNode content = objectMapper.readTree(served.getResponse().getContentAsString());
		long version = content.get("version").asLong();
		String hash = content.get("contentHash").asText();
		mockMvc
			.perform(post("/network/content/PROFILE/" + username + "/synced").header("X-Node-Id", target.id())
				.header("X-Node-Key", target.key()).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("sourceVersion", version, "contentHash", hash))))
			.andExpect(status().isOk());

		MvcResult requested = mockMvc
			.perform(post("/admin/network/nodes/" + target.id() + "/edge-deliver")
				.header("X-Session-Token", admin)
				.param("kind", "PROFILE").param("contentId", username))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.nodeId").value(target.id()))
			.andExpect(jsonPath("$.kind").value("PROFILE"))
			.andExpect(jsonPath("$.contentId").value(username))
			.andExpect(jsonPath("$.status").value("PENDING"))
			.andReturn();
		String taskId = objectMapper.readTree(requested.getResponse().getContentAsString()).get("taskId").asString();

		assertThat(jdbc.queryForObject("select task_type from network_tasks where id = ?", String.class, taskId))
			.isEqualTo("EDGE_DELIVER");
		assertThat(jdbc.queryForObject("select target_node_id from network_tasks where id = ?", String.class,
			taskId)).isEqualTo(target.id());
		// A chave de idempotência é hasheada: kind + contentId + nó podem passar de 80 caracteres
		// juntos, e o digest é o que garante que a chave caiba sempre.
		String idem = jdbc.queryForObject("select idempotency_key from network_tasks where id = ?", String.class,
			taskId);
		assertThat(idem).startsWith("deliver:").hasSize(72);

		assertThat(tasks.dispatchPending()).isEqualTo(1);

		JsonNode mine = tasksOf(target);
		assertThat(mine.size()).isEqualTo(1);
		assertThat(mine.get(0).get("type").asString()).isEqualTo("EDGE_DELIVER");
		String payload = mine.get(0).get("payload").asString();
		assertThat(payload).startsWith("{").endsWith("}");
		assertThat(payload).contains("\"kind\":\"PROFILE\"").contains("\"contentId\":\"" + username + "\"");
		assertThat(payload).doesNotContain("version").doesNotContain("origin");
		// O vínculo: o outro nó de borda não recebe a entrega que é do cache do alvo.
		assertThat(tasksOf(other).size()).isZero();

		// Veredicto verdadeiro: o executor devolve o que ele acabou de revalidar.
		String verdict = "{\"kind\":\"PROFILE\",\"contentId\":\"" + username + "\",\"version\":" + version
			+ ",\"contentHash\":\"" + hash + "\",\"deliverable\":true,\"source\":\"cache\"}";
		mockMvc
			.perform(post("/network/tasks/" + taskId + "/result").header("X-Node-Id", target.id())
				.header("X-Node-Key", target.key()).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("taskId", taskId, "attempt", 1, "status", "DONE",
					"output", verdict))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.applied").value(true))
			.andExpect(jsonPath("$.status").value("DONE"));

		// Em aberto sumiu: a entrega concluiu.
		assertThat(openOf(admin, target).size()).isZero();
	}

	/**
	 * O nó devolve veredicto positivo para conteúdo que ele <b>nunca confirmou</b> em
	 * {@code /synced}. A conferência compara com o snapshot: sem confirmação não há entrega —
	 * aceitar seria contar capacidade que ninguém provou.
	 */
	@Test
	void veredictoSemConfirmacaoNaoConcluiEntrega() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "borda-mentira", List.of("NODE_EDGE"));
		jdbc.update("update network_nodes set last_seen_at = current_timestamp where id = ?", node.id());
		String username = usernameOf(auth.authenticate("edge_deliver_2"));
		String taskId = requestDeliver(admin, node, username);

		assertThat(tasks.dispatchPending()).isEqualTo(1);
		assertThat(tasksOf(node).size()).isEqualTo(1);

		String verdict = "{\"kind\":\"PROFILE\",\"contentId\":\"" + username
			+ "\",\"version\":7,\"contentHash\":\"ab0000000000\",\"deliverable\":true,\"source\":\"cache\"}";
		mockMvc
			.perform(post("/network/tasks/" + taskId + "/result").header("X-Node-Id", node.id())
				.header("X-Node-Key", node.key()).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("taskId", taskId, "attempt", 1, "status", "DONE",
					"output", verdict))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.applied").value(false))
			.andExpect(jsonPath("$.status").value("FAILED"))
			.andExpect(jsonPath("$.reason").value("EDGE_DELIVER_UNCONFIRMED"));

		// A recusa ficou no painel: a tarefa não passou por concluída.
		assertThat(jdbc.queryForObject("select status from network_tasks where id = ?", String.class, taskId))
			.isEqualTo("FAILED");
	}

	/**
	 * O veredicto que não corresponde ao confirmado (outra versão) também não conclui: versão
	 * declarada diferente da confirmada é declaração sem lastro.
	 */
	@Test
	void veredictoComVersaoDiferenteDaConfirmadaERecusado() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "borda-versao", List.of("NODE_EDGE"));
		jdbc.update("update network_nodes set last_seen_at = current_timestamp where id = ?", node.id());
		String username = usernameOf(auth.authenticate("edge_deliver_3"));

		// O nó confirmou a versão 1; o veredicto fala da 2.
		MvcResult served = mockMvc
			.perform(get("/network/content/PROFILE/" + username).header("X-Node-Id", node.id())
				.header("X-Node-Key", node.key()))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode content = objectMapper.readTree(served.getResponse().getContentAsString());
		mockMvc
			.perform(post("/network/content/PROFILE/" + username + "/synced").header("X-Node-Id", node.id())
				.header("X-Node-Key", node.key()).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("sourceVersion", content.get("version").asLong(),
					"contentHash", content.get("contentHash").asText()))))
			.andExpect(status().isOk());
		String taskId = requestDeliver(admin, node, username);
		assertThat(tasks.dispatchPending()).isEqualTo(1);

		String verdict = "{\"kind\":\"PROFILE\",\"contentId\":\"" + username
			+ "\",\"version\":" + (content.get("version").asLong() + 1)
			+ ",\"contentHash\":\"" + content.get("contentHash").asText()
			+ "\",\"deliverable\":true,\"source\":\"cache\"}";
		mockMvc
			.perform(post("/network/tasks/" + taskId + "/result").header("X-Node-Id", node.id())
				.header("X-Node-Key", node.key()).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("taskId", taskId, "attempt", 1, "status", "DONE",
					"output", verdict))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.reason").value("EDGE_DELIVER_MISMATCH"));
	}

	/** Entregar conteúdo que não existe é impossível em qualquer nó: o pedido é 404, não tarefa. */
	@Test
	void conteudoInexistenteERecusadoSemTarefa() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "borda-fantasma", List.of("NODE_EDGE"));
		jdbc.update("update network_nodes set last_seen_at = current_timestamp where id = ?", node.id());

		mockMvc
			.perform(post("/admin/network/nodes/" + node.id() + "/edge-deliver").header("X-Session-Token", admin)
				.param("kind", "PROFILE").param("contentId", "nunca-existiu"))
			.andExpect(status().isNotFound())
			// O 404 é do agregado, com o código da projeção (o mesmo de GET /users/{username}): inventar
			// um código genérico aqui esconderia a causa de quem chamou.
			.andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
		assertThat(jdbc.queryForObject("select count(*) from network_tasks", Long.class)).isZero();
	}

	/** Autorização do painel: quem não é admin não pede entrega nem vê o que está em aberto. */
	@Test
	void naoAdminNaoPedeEntrega() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "borda-auth", List.of("NODE_EDGE"));
		String session = auth.authenticate("edge_deliver_nao_admin").token();

		mockMvc
			.perform(post("/admin/network/nodes/" + node.id() + "/edge-deliver").header("X-Session-Token", session)
				.param("kind", "PROFILE").param("contentId", "alguem"))
			.andExpect(status().isForbidden());
		mockMvc
			.perform(get("/admin/network/nodes/" + node.id() + "/edge-deliver").header("X-Session-Token", session))
			.andExpect(status().isForbidden());
	}

	/** Nó de mídia não entrega conteúdo de borda, mesmo com a chave certa: capacidade não dá permissão. */
	@Test
	void noSemPerfilDeBordaERecusado() throws Exception {
		String admin = adminToken();
		Node media = activeNode(admin, "midia-sem-entrega", List.of("NODE_MEDIA"));

		mockMvc
			.perform(post("/admin/network/nodes/" + media.id() + "/edge-deliver").header("X-Session-Token", admin)
				.param("kind", "PROFILE").param("contentId", "alguem"))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.code").value("NODE_PROFILE_REQUIRED"));
	}

	// ---------------------------------------------------------------- helpers

	private String adminToken() throws Exception {
		var session = auth.authenticate("net_edge_deliver_admin");
		TestDatabase.setAdmin(session.userId());
		return session.token();
	}

	private String requestDeliver(String admin, Node node, String username) throws Exception {
		MvcResult requested = mockMvc
			.perform(post("/admin/network/nodes/" + node.id() + "/edge-deliver").header("X-Session-Token", admin)
				.param("kind", "PROFILE").param("contentId", username))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(requested.getResponse().getContentAsString()).get("taskId").asString();
	}

	private String usernameOf(TestAuthHelper.AuthSession session) throws Exception {
		MvcResult me = mockMvc.perform(get("/me").header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(me.getResponse().getContentAsString()).get("username").asText();
	}

	private JsonNode tasksOf(Node node) throws Exception {
		MvcResult result = mockMvc
			.perform(get("/network/tasks/next").header("X-Node-Id", node.id()).header("X-Node-Key", node.key()))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("tasks");
	}

	private JsonNode openOf(String admin, Node node) throws Exception {
		MvcResult result = mockMvc
			.perform(get("/admin/network/nodes/" + node.id() + "/edge-deliver").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}

	private Node activeNode(String admin, String name, List<String> profiles) throws Exception {
		var tokenResult = mockMvc
			.perform(post("/admin/network/nodes/provision-token").header("X-Session-Token", admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(
					Map.of("displayName", name, "ownerUserId", "owner-" + name, "profiles", profiles))))
			.andExpect(status().isOk())
			.andReturn();
		String provisionToken = objectMapper.readTree(tokenResult.getResponse().getContentAsString()).get("token")
			.asString();

		var enrollResult = mockMvc
			.perform(post("/network/nodes/enroll").contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("provisionToken", provisionToken, "declared",
					Map.of("cpuCores", 2, "memoryMb", 2048, "diskMb", 10240, "bandwidthMbps", 50)))))
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
		return new Node(nodeId, key);
	}
}