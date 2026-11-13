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
 * Fase 5: a rota admin que manda um nó de API chamar uma rota da rede.
 *
 * <p>O contrato que o painel consome, ponta a ponta: o admin pede pelo <b>nome</b> da rota, a
 * tarefa nasce com alvo, o nó designado a vê em {@code GET /network/tasks/next} e <b>o outro nó de
 * API não a vê</b>. A última asserção é a que fixa o vínculo: sem o alvo, os dois nós de API são
 * elegíveis e a tarefa cai no que tiver mais folga — o outro ficaria frio.</p>
 *
 * <p>Também fixa a autorização: quem não é admin não pede, e rota fora da lista assinada é
 * recusada antes de qualquer task nascer.</p>
 */
@SpringBootTest(properties = {
	"rede.network.config.allowed-routes=catalogo=https://catalogo.exemplo/api"
})
@AutoConfigureMockMvc
class NetworkApiRequestTests {

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
		jdbc.update("delete from network_node_keys");
		jdbc.update("delete from network_provision_tokens");
		jdbc.update("delete from network_nodes");
	}

	/**
	 * O caminho inteiro: pedido do admin → tarefa com alvo → despacho → o nó alvo recebe.
	 *
	 * <p>O nó alvo é o <b>menos</b> folgado dos dois, para que a asserção "só o alvo recebe" não
	 * passe por sorteio. Ele continua elegível (folga 0.5), então o filtro é o que decide.</p>
	 */
	@Test
	void noAlvoRecebeARequisicaoEOutroNoDeApiNao() throws Exception {
		String admin = adminToken();
		Node target = activeNode(admin, "api-alvo", List.of("NODE_API"));
		Node other = activeNode(admin, "api-outra", List.of("NODE_API"));
		jdbc.update("update network_nodes set last_seen_at = current_timestamp, declared_cpu_cores = 2, "
			+ "observed_running_tasks = 1 where id = ?", target.id());
		jdbc.update("update network_nodes set last_seen_at = current_timestamp, declared_cpu_cores = 8, "
			+ "observed_running_tasks = 0 where id = ?", other.id());

		MvcResult requested = mockMvc
			.perform(post("/admin/network/nodes/" + target.id() + "/api-request").param("route", "catalogo")
				.header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.nodeId").value(target.id()))
			.andExpect(jsonPath("$.status").value("PENDING"))
			.andReturn();
		String taskId = objectMapper.readTree(requested.getResponse().getContentAsString()).get("taskId")
			.asString();

		assertThat(jdbc.queryForObject("select task_type from network_tasks where id = ?", String.class, taskId))
			.isEqualTo("API_REQUEST");

		// Designa agora, sem esperar a varredura periódica (ela está desligada na suíte).
		assertThat(tasks.dispatchPending()).isEqualTo(1);

		JsonNode mine = tasksOf(target);
		assertThat(mine.size()).isEqualTo(1);
		assertThat(mine.get(0).get("taskId").asString()).isEqualTo(taskId);
		assertThat(mine.get(0).get("type").asString()).isEqualTo("API_REQUEST");
		// O contrato que o executor do nó lê: payload JSON em string, com { e } que sobrevivem ao
		// recorte do `task_payload` (ele corta até `","dataClass":"`). Só o nome e o método: o
		// destino vem da lista assinada, e nunca da fila.
		String payload = mine.get(0).get("payload").asString();
		assertThat(payload).startsWith("{").endsWith("}");
		assertThat(payload).contains("\"route\":\"catalogo\"").contains("\"method\":\"GET\"");
		assertThat(payload).doesNotContain("https://");

		// O outro nó de API não recebeu nada: a tarefa não é dele.
		assertThat(tasksOf(other).size()).isZero();
	}

	/** O que está em aberto aparece no painel; depois de concluído, some. */
	@Test
	void pedidoAbertoApareceESomeAoConcluir() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "api-painel", List.of("NODE_API"));
		jdbc.update("update network_nodes set last_seen_at = current_timestamp where id = ?", node.id());

		MvcResult requested = mockMvc
			.perform(post("/admin/network/nodes/" + node.id() + "/api-request").param("route", "catalogo")
				.header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andReturn();
		String taskId = objectMapper.readTree(requested.getResponse().getContentAsString()).get("taskId")
			.asString();

		JsonNode open = openOf(admin, node);
		assertThat(open.size()).isEqualTo(1);
		assertThat(open.get(0).get("taskId").asString()).isEqualTo(taskId);

		assertThat(tasks.dispatchPending()).isEqualTo(1);
		mockMvc
			.perform(post("/network/tasks/" + taskId + "/result").header("X-Node-Id", node.id())
				.header("X-Node-Key", node.key()).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("taskId", taskId, "attempt", 1, "status",
					"DONE", "output", "{\"route\":\"catalogo\",\"status\":200,\"bytes\":12,\"digest\":\"abc\"}"))))
			.andExpect(status().isOk());

		assertThat(openOf(admin, node).size()).isZero();
	}

	/** As rotas assinadas que a rede oferece — o painel pede um nome que exista. */
	@Test
	void oPainelListaAsRotasAssinadas() throws Exception {
		String admin = adminToken();

		mockMvc.perform(get("/admin/network/api-routes").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0]").value("catalogo"));
	}

	/**
	 * Rota fora da lista assinada é recusada com o motivo, e nada nasce na fila — o pedido não
	 * vira uma task fadada ao {@code API_ROUTE_NOT_ALLOWED}.
	 */
	@Test
	void rotaNaoAssinadaERecusadaSemCriarTarefa() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "api-sem-rota", List.of("NODE_API"));

		mockMvc
			.perform(post("/admin/network/nodes/" + node.id() + "/api-request").param("route", "interna")
				.header("X-Session-Token", admin))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.code").value("API_ROUTE_NOT_ALLOWED"));

		assertThat(jdbc.queryForObject("select count(*) from network_tasks", Long.class)).isEqualTo(0L);
	}

	/** Quem não é admin não pede requisição a API, nem vê o que o nó tem em aberto. */
	@Test
	void naoAdminNaoPedeRequisicao() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "api-auth", List.of("NODE_API"));
		String session = auth.authenticate("api_request_nao_admin").token();

		mockMvc
			.perform(post("/admin/network/nodes/" + node.id() + "/api-request").param("route", "catalogo")
				.header("X-Session-Token", session))
			.andExpect(status().isForbidden());
		mockMvc.perform(get("/admin/network/nodes/" + node.id() + "/api-request").header("X-Session-Token", session))
			.andExpect(status().isForbidden());
		mockMvc.perform(get("/admin/network/api-routes").header("X-Session-Token", session))
			.andExpect(status().isForbidden());
	}

	/**
	 * Nó de borda não faz requisição a API: o pedido é recusado com o motivo, em vez de criar uma
	 * tarefa que nunca termina porque o alvo nunca vai executá-la.
	 */
	@Test
	void noSemPerfilDeApiERecusado() throws Exception {
		String admin = adminToken();
		Node edge = activeNode(admin, "borda-sem-api", List.of("NODE_EDGE"));

		mockMvc
			.perform(post("/admin/network/nodes/" + edge.id() + "/api-request").param("route", "catalogo")
				.header("X-Session-Token", admin))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.code").value("NODE_PROFILE_REQUIRED"));
	}

	// ---------------------------------------------------------------- helpers

	private String adminToken() throws Exception {
		var session = auth.authenticate("net_api_request_admin");
		TestDatabase.setAdmin(session.userId());
		return session.token();
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
			.perform(get("/admin/network/nodes/" + node.id() + "/api-request").header("X-Session-Token", admin))
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
		String provisionToken = objectMapper.readTree(tokenResult.getResponse().getContentAsString())
			.get("token")
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
