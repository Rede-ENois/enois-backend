package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
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

import com.socialcoisa.rede.network.application.NetworkModeService;
import com.socialcoisa.rede.network.application.NetworkTaskService;
import com.socialcoisa.rede.network.domain.DataClass;
import com.socialcoisa.rede.network.domain.NetworkMode;
import com.socialcoisa.rede.network.domain.NetworkTask;
import com.socialcoisa.rede.network.domain.TaskType;
import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestDatabase;

import tools.jackson.databind.ObjectMapper;

/**
 * Fase 4, modos operacionais: o modo deixou de ser um enum sem leitor.
 *
 * <p>Cada teste fixa um jeito de errar que a contingência do doc 06 promete evitar:</p>
 * <ul>
 * <li>{@code DEGRADED}/{@code EMERGENCY} só existiam no enum — nenhuma rota os acionava;</li>
 * <li>o nó não sabia o modo, então executaria no escuro durante um incidente;</li>
 * <li>o modo não mudava nada no despacho, então "tarefas adiadas" era narrativa;</li>
 * <li>{@code RECOVERY} sem fim viraria modo esquecido, com a fila pela metade para sempre;</li>
 * <li>a automação não pode desfazer a decisão humana de um incidente.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class NetworkModesTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private NetworkTaskService tasks;

	@Autowired
	private NetworkModeService modes;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
		jdbc.update("delete from network_tasks");
		jdbc.update("delete from network_state");
		jdbc.update("delete from network_node_keys");
		jdbc.update("delete from network_provision_tokens");
		jdbc.update("delete from network_nodes");
		// Só as linhas de modo: outras classes também auditam, e estas são as únicas com este alvo.
		jdbc.update("delete from audit_logs where target_id = 'global' and action like 'NETWORK_MODE_%'");
	}

	// ---------------------------------------------------------------- acionamento humano

	@Test
	void adminDeclaraEmergencyEAuditoriaGuardaOMotivoEOModoAnterior() throws Exception {
		var admin = adminSession();

		mockMvc
			.perform(post("/admin/network/mode").header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(
					Map.of("mode", "EMERGENCY", "reason", "banco principal fora as 14h"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.mode").value("EMERGENCY"))
			.andExpect(jsonPath("$.acceptsNewTasks").value(false));

		assertThat(modes.current()).isEqualTo(NetworkMode.EMERGENCY);
		String metadata = jdbc.queryForObject(
			"select metadata from audit_logs where action = 'NETWORK_MODE_EMERGENCY' and target_id = 'global'",
			String.class);
		assertThat(metadata).contains("\"from\":\"NORMAL\"").contains("banco principal fora");
	}

	@Test
	void motivoComAspasNaoQuebraOMetadataDaAuditoria() throws Exception {
		var admin = adminSession();

		mockMvc
			.perform(post("/admin/network/mode").header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(
					Map.of("mode", "DEGRADED", "reason", "S3 responde \"503\" intermitente"))))
			.andExpect(status().isOk());

		String metadata = jdbc.queryForObject(
			"select metadata from audit_logs where action = 'NETWORK_MODE_DEGRADED' and target_id = 'global'",
			String.class);
		// O metadata é JSON: se as aspas não fossem escapadas, isto não seria parseável.
		assertThat(objectMapper.readTree(metadata).get("reason").asString()).contains("503");
	}

	@Test
	void transicaoManualSemMotivoERecusada() throws Exception {
		var admin = adminSession();

		mockMvc
			.perform(post("/admin/network/mode").header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("mode", "EMERGENCY", "reason", "   "))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("NETWORK_MODE_REASON_REQUIRED"));

		assertThat(modes.current()).isEqualTo(NetworkMode.NORMAL);
	}

	@Test
	void normalEHighLoadNaoSaoAcionaveisAMao() throws Exception {
		var admin = adminSession();

		mockMvc
			.perform(post("/admin/network/mode").header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("mode", "NORMAL", "reason", "porque sim"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("NETWORK_MODE_NOT_MANUAL"));
	}

	@Test
	void modoDesconhecidoERecusado() throws Exception {
		var admin = adminSession();

		mockMvc
			.perform(post("/admin/network/mode").header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("mode", "PANICO", "reason", "teste"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("NETWORK_MODE_UNKNOWN"));
	}

	@Test
	void membroComumNaoDeclaraModo() throws Exception {
		var member = auth.authenticate("net_mode_member");

		mockMvc
			.perform(post("/admin/network/mode").header("X-Session-Token", member.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("mode", "EMERGENCY", "reason", "teste"))))
			.andExpect(status().isForbidden());

		mockMvc.perform(get("/admin/network/mode").header("X-Session-Token", member.token()))
			.andExpect(status().isForbidden());
		assertThat(modes.current()).isEqualTo(NetworkMode.NORMAL);
	}

	// ---------------------------------------------------------------- efeito no despacho

	@Test
	void emergencyParaODespachoMasNaoDerrubaOTrabalhoEmAndamento() throws Exception {
		var admin = adminSession();
		Node node = activeNode(admin.token(), "no-a", List.of("NODE_WORKER"));
		NetworkTask running = tasks.enqueue(new NetworkTaskService.EnqueueCommand(TaskType.ASYNC_JOB,
			"job-em-curso", "{}", DataClass.PUBLIC, null));
		assertThat(tasks.dispatchPending()).isEqualTo(1);
		assertThat(assignedNode(running.id())).isEqualTo(node.id());

		modes.declare(admin.userId(), NetworkMode.EMERGENCY, "infra principal fora");

		NetworkTask queued = tasks.enqueue(new NetworkTaskService.EnqueueCommand(TaskType.ASYNC_JOB,
			"job-na-fila", "{}", DataClass.PUBLIC, null));
		assertThat(tasks.dispatchPending()).isZero();
		assertThat(statusOf(queued.id())).isEqualTo("PENDING");

		// A tarefa em curso continua sendo do nó: parar o despacho não é cancelar o que já roda.
		assertThat(statusOf(running.id())).isEqualTo("DISPATCHED");
		assertThat(assignedNode(running.id())).isEqualTo(node.id());
	}

	@Test
	void degradadoAdiaTarefaNova() throws Exception {
		var admin = adminSession();
		activeNode(admin.token(), "no-a", List.of("NODE_WORKER"));
		modes.declare(admin.userId(), NetworkMode.DEGRADED, "dependencia critica instavel");

		NetworkTask queued = tasks.enqueue(new NetworkTaskService.EnqueueCommand(TaskType.ASYNC_JOB,
			"job-degradado", "{}", DataClass.PUBLIC, null));
		assertThat(tasks.dispatchPending()).isZero();
		assertThat(statusOf(queued.id())).isEqualTo("PENDING");
	}

	@Test
	void recuperacaoDespachaPelaMetadeEDepoisONumeroDevolveNormal() throws Exception {
		var admin = adminSession();
		// Capacidade escolhida para o tema deste teste ser o freio do modo, e não a vaga do nó: com
		// quatro núcleos a fila esbarraria no limite de concorrência (quatro por rodada) e nunca se
		// mediria a metade do teto. Sessenta e quatro vagas ficam acima do teto de varredura, então
		// quem limita cada rodada é o modo.
		activeNode(admin.token(), "no-local-1", List.of("NODE_LOCAL"), 64);
		enqueue("rec", 60);

		modes.declare(admin.userId(), NetworkMode.RECOVERY, "voltando do incidente");
		// RECOVERY despacha metade do teto: retomar com a fila inteira é repetir o pico.
		assertThat(tasks.dispatchPending()).isEqualTo(NetworkTaskService.SCAN_LIMIT / 2);

		// Com a fila ainda acima do limiar de saída, a recuperação continua: sair cedo seria repetir
		// o pico no meio dele.
		assertThat(modes.evaluate()).isEqualTo(NetworkMode.RECOVERY);

		// Segunda rodada de RECOVERY, e aí o número fecha o ciclo sozinho — sem ninguém declarar
		// NORMAL, que aliás não é acionável à mão.
		assertThat(tasks.dispatchPending()).isEqualTo(NetworkTaskService.SCAN_LIMIT / 2);
		assertThat(modes.evaluate()).isEqualTo(NetworkMode.NORMAL);

		// Em NORMAL a varredura volta ao teto cheio.
		enqueue("nrm", NetworkTaskService.SCAN_LIMIT);
		assertThat(tasks.dispatchPending()).isEqualTo(NetworkTaskService.SCAN_LIMIT);
	}

	@Test
	void normalDespachaOTetoInteiroDaVarredura() throws Exception {
		var admin = adminSession();
		activeNode(admin.token(), "no-normal", List.of("NODE_LOCAL"), NetworkTaskService.SCAN_LIMIT * 2);
		enqueue("nrm", NetworkTaskService.SCAN_LIMIT);
		// Sem linha de estado o modo é NORMAL: o freio só existe em RECOVERY/DEGRADED/EMERGENCY.
		assertThat(tasks.dispatchPending()).isEqualTo(NetworkTaskService.SCAN_LIMIT);
	}

	@Test
	void recuperacaoTerminaPorNumeroSemNinguemEncerrar() throws Exception {
		jdbc.update("delete from network_tasks");
		jdbc.update("insert into network_state (id, mode, reason, since) values ('global', 'RECOVERY', ?, ?)",
			"manual", java.sql.Timestamp.from(Instant.now()));

		// Fila vazia e capacidade > 0: a razão é 0, abaixo do limiar de saída.
		assertThat(modes.evaluate()).isEqualTo(NetworkMode.NORMAL);
	}

	@Test
	void automacaoNaoDesfazEmergencia() throws Exception {
		jdbc.update("insert into network_state (id, mode, reason, since) values ('global', 'EMERGENCY', ?, ?)",
			"manual", java.sql.Timestamp.from(Instant.now()));

		// Mesmo com a fila vazia (razão 0, que sairia de RECOVERY), o modo de incidente fica.
		assertThat(modes.evaluate()).isEqualTo(NetworkMode.EMERGENCY);
	}

	// ---------------------------------------------------------------- o nó sabe o modo

	/**
	 * O nó no teto de memória declarado não é só excluído do despacho: ele também não conta vaga. Se
	 * contasse, a razão fila/vagas pareceria folgada e a rede demoraria a entrar em {@code HIGH_LOAD}
	 * com metade da capacidade de fato indisponível.
	 */
	@Test
	void noNoTetoDeMemoriaNaoContaVagaNaCapacidade() throws Exception {
		var admin = adminSession();
		Node node = activeNode(admin.token(), "no-cheio", List.of("NODE_LOCAL"), 1);
		// Um núcleo, uma vaga. O nó reporta 8192 MB de 8192 MB declarados: a vaga não existe.
		mockMvc
			.perform(post("/network/nodes/heartbeat").header("X-Node-Id", node.id())
				.header("X-Node-Key", node.key()).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("runtimeVersion", "0.1.0", "observed",
					Map.of("cpuPercent", 5.0, "memoryMb", 8192, "runningTasks", 0)))))
			.andExpect(status().isOk());

		enqueue("mem", 2);
		assertThat(tasks.dispatchPending()).isZero();
		assertThat(modes.evaluate()).isEqualTo(NetworkMode.HIGH_LOAD);
	}

	@Test
	void heartbeatDevolveOModoEOFreioDoDespacho() throws Exception {
		var admin = adminSession();
		Node node = activeNode(admin.token(), "no-a", List.of("NODE_WORKER"));

		mockMvc
			.perform(post("/network/nodes/heartbeat").header("X-Node-Id", node.id())
				.header("X-Node-Key", node.key()).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("runtimeVersion", "0.1.0"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.mode").value("NORMAL"))
			.andExpect(jsonPath("$.acceptTasks").value(true));

		modes.declare(admin.userId(), NetworkMode.EMERGENCY, "infra principal fora");

		mockMvc
			.perform(post("/network/nodes/heartbeat").header("X-Node-Id", node.id())
				.header("X-Node-Key", node.key()).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("runtimeVersion", "0.1.0"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.mode").value("EMERGENCY"))
			.andExpect(jsonPath("$.acceptTasks").value(false));
	}

	// ---------------------------------------------------------------- helpers

	private record Node(String id, String key) {
	}

	private record Session(String token, String userId) {
	}

	private Session adminSession() throws Exception {
		var session = auth.authenticate("net_mode_admin");
		TestDatabase.setAdmin(session.userId());
		return new Session(session.token(), session.userId());
	}

	private Node activeNode(String admin, String name, List<String> profiles) throws Exception {
		return activeNode(admin, name, profiles, 4);
	}

	private Node activeNode(String admin, String name, List<String> profiles, int cpuCores) throws Exception {
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
					Map.of("cpuCores", cpuCores, "memoryMb", 8192, "diskMb", 51200, "bandwidthMbps", 100)))))
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

	private void enqueue(String prefix, int count) {
		for (int i = 0; i < count; i++) {
			tasks.enqueue(new NetworkTaskService.EnqueueCommand(TaskType.ASYNC_JOB, prefix + "-" + i, "{}",
				DataClass.PUBLIC, null));
		}
	}

	private String assignedNode(String taskId) {
		return jdbc.queryForObject("select assigned_node_id from network_tasks where id = ?", String.class, taskId);
	}

	private String statusOf(String taskId) {
		return jdbc.queryForObject("select status from network_tasks where id = ?", String.class, taskId);
	}
}
