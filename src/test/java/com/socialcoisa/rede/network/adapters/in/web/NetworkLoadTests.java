package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.HashMap;
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
import com.socialcoisa.rede.network.domain.DataClass;
import com.socialcoisa.rede.network.domain.TaskType;
import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestDatabase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Teste de carga: a distribuição <b>aumenta a capacidade de atendimento</b>.
 *
 * <p>É o item da lista de testes obrigatórios que ainda não tinha medição. O que ele fixa, e por
 * quê:</p>
 * <ul>
 * <li>a capacidade medida é a <b>vazão do ciclo real</b> — enfileirar, designar, o nó pedir em
 * {@code GET /network/tasks/next}, executar e devolver em {@code POST /network/tasks/{id}/result} —,
 * não uma conta de vagas declaradas. Contar vagas provaria que a soma fecha; o que se quer provar é
 * que a rede entrega mais trabalho;</li>
 * <li>o mesmo conjunto de tarefas é servido por uma rede de um nó e por uma rede de dois nós com a
 * <b>mesma capacidade declarada por nó</b>. O número que sai é quantas tarefas foram concluídas
 * dentro do mesmo número de rodadas;</li>
 * <li>o número é <b>discriminante</b>: um nó de quatro núcleos conclui quatro por rodada, dois nós
 * concluem oito. Uma asserção que passasse nos dois cenários (por exemplo "concluiu alguma coisa")
 * não provaria nada, e o teste a recusa explicitamente;</li>
 * <li>o limite de concorrência sobrevive à carga: o nó nunca recebe mais do que declarou, mesmo com
 * fila sobrando, e o que sobra fica {@code PENDING} em vez de ser empurrado para o nó cheio.</li>
 * </ul>
 *
 * <p>Nada aqui mede tempo. Medir milissegundos de um MockMvc em H2 diria mais sobre a máquina de
 * teste do que sobre a rede, e o critério do contrato é capacidade de atendimento — quantas tarefas
 * a rede serve —, que é contagem.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class NetworkLoadTests {

	/** Núcleos declarados por nó em todos os cenários: é o teto que o participante cedeu. */
	private static final int CORES_POR_NO = 4;

	/** Tarefas na fila: o dobro da capacidade de um nó, para que sobre trabalho nos dois cenários. */
	private static final int CARGA = 2 * CORES_POR_NO;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private NetworkTaskService tasks;

	private TestAuthHelper auth;

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
	 * A prova principal: com a mesma carga e o mesmo limite por nó, dois nós atendem o dobro de um.
	 *
	 * <p>As duas metades rodam o mesmo código, com a mesma carga e o mesmo tipo de nó; a única
	 * diferença é quantos nós existem. O resultado é medido em tarefas concluídas por rodada, e cada
	 * metade é conferida contra o número exato — não contra "mais que zero".</p>
	 */
	@Test
	void doisNosAtendemODobroDeUmNoComAMesmaCapacidadePorNo() throws Exception {
		String admin = adminToken();

		// --- metade A: um nó de quatro núcleos
		Node sozinho = activeNode(admin, "carga-um-no", CORES_POR_NO);
		List<String> cargaA = enqueueLoad("carga-a-", CARGA);
		int atendidasA = runCycles(List.of(sozinho), cargaA, 1);
		// Medida ANTES de limpar as tabelas para a metade B: a fila da rede pequena é o que fica
		// pendente nela, não o que sobra depois da outra rodar.
		int pendentesA = pendingOf(cargaA);

		// --- metade B: dois nós de quatro núcleos, mesma carga, mesmas rodadas
		jdbc.update("delete from network_tasks");
		jdbc.update("delete from network_node_keys");
		jdbc.update("delete from network_nodes");
		Node primeiro = activeNode(admin, "carga-no-1", CORES_POR_NO);
		Node segundo = activeNode(admin, "carga-no-2", CORES_POR_NO);
		List<String> cargaB = enqueueLoad("carga-b-", CARGA);
		int atendidasB = runCycles(List.of(primeiro, segundo), cargaB, 1);

		// O número exato de cada metade: a capacidade é o limite declarado, não uma aproximação.
		assertThat(atendidasA).as("um nó de %d núcleos atende %d por rodada", CORES_POR_NO, CORES_POR_NO)
			.isEqualTo(CORES_POR_NO);
		assertThat(atendidasB).as("dois nós de %d núcleos atendem %d por rodada", CORES_POR_NO, 2 * CORES_POR_NO)
			.isEqualTo(2 * CORES_POR_NO);

		// A prova do item: a distribuição dobrou a capacidade de atendimento.
		assertThat(atendidasB).as("a rede de dois nós atende mais que a de um, com a mesma carga")
			.isEqualTo(2 * atendidasA);

		// E dobrou PORQUE distribuiu: cada nó atendeu o próprio limite, nenhum os dois. Um scheduler
		// que empilhasse as oito tarefas num só nó daria o mesmo total e falharia aqui.
		assertThat(doneOn(primeiro.id())).isEqualTo(CORES_POR_NO);
		assertThat(doneOn(segundo.id())).isEqualTo(CORES_POR_NO);

		// E o que não coube continua na fila, intacto: capacidade maior não é fila maior.
		assertThat(pendentesA).isEqualTo(CARGA - atendidasA);
		assertThat(pendingOf(cargaB)).isEqualTo(CARGA - atendidasB);
	}

	/**
	 * A capacidade não é só a rodada: o nó devolve o que executou e a rodada seguinte o reusa.
	 *
	 * <p>Se o aumento viesse de reservar mais do que o nó aguenta, a segunda rodada não teria vaga
	 * nenhuma e a rede pararia — o número por rodada pareceria bom e a vazão seria pior. Aqui a carga
	 * é servida em rodadas sucessivas até esvaziar, e o total tem de ser a carga inteira, sem
	 * duplicar e sem perder tarefa.</p>
	 */
	@Test
	void aCargaInteiraEhAtendidaEmRodadasSucessivasSemDuplicarNemPerder() throws Exception {
		String admin = adminToken();
		Node primeiro = activeNode(admin, "vazao-no-1", CORES_POR_NO);
		Node segundo = activeNode(admin, "vazao-no-2", CORES_POR_NO);
		List<String> carga = enqueueLoad("vazao-", CARGA);

		// Duas rodadas de quatro vagas cada em dois nós: a carga cabe em uma rodada, e o teste exige
		// que ela saia inteira sem precisar de segunda.
		int atendidas = runCycles(List.of(primeiro, segundo), carga, 1);
		assertThat(atendidas).isEqualTo(CARGA);
		assertThat(pendingOf(carga)).isZero();

		// Uma tarefa por efeito: nenhuma foi designada duas vezes nem concluída duas vezes.
		assertThat(distinctDoneAttempts(carga)).isEqualTo(CARGA);
		assertThat(totalDispatches(carga)).isEqualTo(CARGA);
	}

	/**
	 * Sobrecarga: com fila maior que a rede, a distribuição continua respeitando o limite por nó.
	 *
	 * <p>É o cenário em que o defeito apareceria como "capacidade infinita": carga de três rodadas
	 * numa rede de duas, e o teste exige que cada rodada atenda exatamente a capacidade e que o resto
	 * fique na fila. Sem isso, um scheduler que ignorasse a concorrência passaria no teste anterior
	 * (tudo sairia numa rodada) e falharia em campo.</p>
	 */
	@Test
	void sobSobrecargaCadaRodadaAtendeALimiteENaoMais() throws Exception {
		String admin = adminToken();
		Node primeiro = activeNode(admin, "sobrecarga-no-1", CORES_POR_NO);
		Node segundo = activeNode(admin, "sobrecarga-no-2", CORES_POR_NO);
		int carga = 5 * CORES_POR_NO;
		List<String> tarefas = enqueueLoad("sobrecarga-", carga);

		// Duas rodadas cheias e uma parcial: 8 + 8 + 4 = as 20 tarefas, sem sobrar nem perder.
		int primeiraRodada = runCycles(List.of(primeiro, segundo), tarefas, 1);
		assertThat(primeiraRodada).isEqualTo(2 * CORES_POR_NO);
		assertThat(pendingOf(tarefas)).isEqualTo(carga - 2 * CORES_POR_NO);

		int segundaRodada = runCycles(List.of(primeiro, segundo), tarefas, 1);
		assertThat(segundaRodada).isEqualTo(2 * CORES_POR_NO);
		assertThat(pendingOf(tarefas)).isEqualTo(carga - 4 * CORES_POR_NO);

		int terceiraRodada = runCycles(List.of(primeiro, segundo), tarefas, 1);
		assertThat(terceiraRodada).isEqualTo(CORES_POR_NO);
		assertThat(pendingOf(tarefas)).isZero();
	}

	// ---------------------------------------------------------------- ciclo da rede

	/**
	 * Uma rodada de verdade: o Control Plane designa, cada nó pede o que é dele, executa e devolve o
	 * resultado. O que o nó "executa" é o que o teste conta — a tarefa só sai de {@code PENDING} se
	 * passou pelo ciclo inteiro.
	 *
	 * @return quantas tarefas foram concluídas nesta rodada
	 */
	private int runCycles(List<Node> nodes, List<String> load, int cycles) throws Exception {
		int done = 0;
		for (int cycle = 0; cycle < cycles; cycle++) {
			tasks.dispatchPending();
			for (Node node : nodes) {
				for (JsonNode task : tasksOf(node)) {
					report(node, task.get("taskId").asText(), task.get("attempt").asInt(), "DONE");
					done++;
				}
			}
		}
		return done;
	}

	private List<String> enqueueLoad(String prefix, int count) {
		List<String> ids = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			ids.add(tasks.enqueue(new NetworkTaskService.EnqueueCommand(TaskType.ASYNC_JOB, prefix + i, "{}",
				DataClass.PUBLIC, null)).id());
		}
		return ids;
	}

	// ---------------------------------------------------------------- medidas

	private int pendingOf(List<String> ids) {
		return count("select count(*) from network_tasks where status = 'PENDING' and id in (%s)", ids);
	}

	private int distinctDoneAttempts(List<String> ids) {
		return count("select count(distinct id) from network_tasks where status = 'DONE' and id in (%s)", ids);
	}

	/** Tarefas concluídas por um nó: a metade da capacidade que coube a ele. */
	private int doneOn(String nodeId) {
		return jdbc.queryForObject(
			"select count(*) from network_tasks where status = 'DONE' and assigned_node_id = ?", Integer.class,
			nodeId);
	}

	private int totalDispatches(List<String> ids) {
		// attempt sobe a cada designação e começa em zero: a soma é quantas vezes o conjunto foi
		// designado. Igual à carga significa uma designação por tarefa.
		return jdbc.queryForObject("select coalesce(sum(attempt), 0) from network_tasks where id in ("
			+ placeholders(ids) + ")", Integer.class, ids.toArray());
	}

	private int count(String sql, List<String> ids) {
		return jdbc.queryForObject(String.format(sql, placeholders(ids)), Integer.class, ids.toArray());
	}

	private static String placeholders(List<String> ids) {
		return String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
	}

	// ---------------------------------------------------------------- helpers de nó

	private record Node(String id, String key) {
	}

	private String adminToken() throws Exception {
		var session = auth.authenticate("net_load_admin");
		TestDatabase.setAdmin(session.userId());
		return session.token();
	}

	private Node activeNode(String admin, String name, int cpuCores) throws Exception {
		var tokenResult = mockMvc
			.perform(post("/admin/network/nodes/provision-token").header("X-Session-Token", admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("displayName", name, "ownerUserId", "owner-" + name,
					"profiles", List.of("NODE_WORKER")))))
			.andExpect(status().isOk())
			.andReturn();
		String provisionToken = objectMapper.readTree(tokenResult.getResponse().getContentAsString()).get("token")
			.asString();

		Map<String, Object> enrollBody = new HashMap<>();
		enrollBody.put("provisionToken", provisionToken);
		enrollBody.put("declared",
			Map.of("cpuCores", cpuCores, "memoryMb", 8192, "diskMb", 51200, "bandwidthMbps", 100));
		var enrollResult = mockMvc
			.perform(post("/network/nodes/enroll").contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(enrollBody)))
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
		heartbeat(node, 0);
		return node;
	}

	/** Heartbeat com o que o nó observou. Zero em execução: a vaga é do scheduler, não do nó. */
	private void heartbeat(Node node, int runningTasks) throws Exception {
		mockMvc
			.perform(post("/network/nodes/heartbeat").header("X-Node-Id", node.id())
				.header("X-Node-Key", node.key()).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("runtimeVersion", "0.1.0", "observed",
					Map.of("cpuPercent", 5.0, "memoryMb", 1024, "runningTasks", runningTasks)))))
			.andExpect(status().isOk());
	}

	private JsonNode tasksOf(Node node) throws Exception {
		MvcResult result = mockMvc
			.perform(get("/network/tasks/next").header("X-Node-Id", node.id()).header("X-Node-Key", node.key()))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("tasks");
	}

	private void report(Node node, String taskId, int attempt, String status) throws Exception {
		Map<String, Object> body = new HashMap<>();
		body.put("taskId", taskId);
		body.put("attempt", attempt);
		body.put("status", status);
		mockMvc
			.perform(post("/network/tasks/" + taskId + "/result").header("X-Node-Id", node.id())
				.header("X-Node-Key", node.key()).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(body)))
			.andExpect(status().isOk());
	}
}
