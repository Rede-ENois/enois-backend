package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import com.socialcoisa.rede.network.application.NetworkTaskService;
import com.socialcoisa.rede.network.domain.DataClass;
import com.socialcoisa.rede.network.domain.TaskType;
import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestDatabase;

import tools.jackson.databind.ObjectMapper;

/**
 * Pausar a participação (brief §18): o participante para de emprestar a máquina <b>sem sair da
 * rede</b>, e volta sozinho.
 *
 * <p>Até aqui só existia desconectar, que é {@code SUSPENDED} com as chaves revogadas — voltar
 * exigia um admin reaprovar. Pausa que precisa de admin não é pausa: é suspensão com outro nome,
 * e a tela do participante (§18, "o participante deverá conseguir controlar a utilização de sua
 * infraestrutura") prometia um controle que não existia.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class NetworkParticipantPauseTests {

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
		jdbc.update("delete from network_node_logs");
		jdbc.update("delete from network_node_samples");
		jdbc.update("delete from network_node_keys");
		jdbc.update("delete from network_provision_tokens");
		jdbc.update("delete from network_nodes");
	}

	/**
	 * O caminho principal: pausar tira o trabalho e mantém o canal; retomar devolve, sem admin.
	 *
	 * <p>Cada etapa é verificada pelo que o <b>nó</b> vê, não só pelo status no banco: é o nó que
	 * decidiria continuar pedindo tarefa.</p>
	 */
	@Test
	void pausar_para_o_trabalho_e_conserva_o_canal() throws Exception {
		var dono = auth.authenticate("net_dono_pausa");
		String admin = adminToken();
		String nodeId = storageNodeFor(admin, dono.userId());
		String key = issueKey(admin, nodeId);

		// Antes da pausa: o nó opera e pede trabalho como qualquer outro.
		assertThat(heartbeat(nodeId, key).path("acceptTasks").asBoolean()).isTrue();

		mockMvc.perform(post("/network/participant/nodes/" + nodeId + "/pause")
			.header("X-Session-Token", dono.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("PAUSED"));

		// Pausado, o heartbeat continua aceito — e é ele que diz ao runtime para drenar.
		var beat = heartbeat(nodeId, key);
		assertThat(beat.path("status").asString()).isEqualTo("PAUSED");
		assertThat(beat.path("acceptTasks").asBoolean())
			.as("nó pausado não pode receber tarefa nova")
			.isFalse();

		// A chave NÃO caiu: pausa não é saída da rede. É esta asserção que separa pausa de
		// desconexão — sem ela, o `pause` poderia ser um `disconnect` com outro nome e o teste
		// passaria.
		mockMvc.perform(get("/network/nodes/me/config").header("X-Node-Id", nodeId).header("X-Node-Key", key))
			.andExpect(status().isOk());

		// `acceptTasks=false` e a lista de tarefas são coisas diferentes: o primeiro é o que o
		// runtime usa para drenar, a segunda é a FILA dele. Um pausado que ainda recebe a tarefa
		// designada continua executando — pausa de fachada. Ele pergunta e leva 403, a mesma porta
		// pelos outros estados não-operáveis.
		mockMvc.perform(get("/network/tasks/next").header("X-Node-Id", nodeId).header("X-Node-Key", key))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NODE_PAUSED"));

		// Retomada pelo próprio participante: volta a operar sem nenhum passo de admin.
		mockMvc.perform(post("/network/participant/nodes/" + nodeId + "/resume")
			.header("X-Session-Token", dono.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ACTIVE"));
		assertThat(heartbeat(nodeId, key).path("acceptTasks").asBoolean()).isTrue();

		// Retomado, ele já não é recusado: a fila volta a responder.
		mockMvc.perform(get("/network/tasks/next").header("X-Node-Id", nodeId).header("X-Node-Key", key))
			.andExpect(status().isOk());
	}

	/**
	 * Desconectar e pausar são estados diferentes. Se pausar caísse em {@code SUSPENDED}, a
	 * retomada do participante teria de reativar um nó suspenso — e um suspenso por decisão
	 * administrativa voltaria a operar pela porta da tela do participante.
	 */
	@Test
	void pausa_nao_se_confunde_com_desconexao() throws Exception {
		var dono = auth.authenticate("net_dono_pausa_vs_desconexao");
		String admin = adminToken();
		String nodeId = storageNodeFor(admin, dono.userId());
		issueKey(admin, nodeId);

		mockMvc.perform(post("/network/participant/nodes/" + nodeId + "/disconnect")
			.header("X-Session-Token", dono.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("SUSPENDED"));

		// Desconectado não "retoma": é decisão administrativa que devolve o servidor à rede.
		mockMvc.perform(post("/network/participant/nodes/" + nodeId + "/resume")
			.header("X-Session-Token", dono.token()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("NODE_NOT_RESUMABLE"));

		// E a pausa também não existe sobre suspenso: rebaixar SUSPENDED a PAUSED daria, pela
		// retomada, a volta por cima de uma suspensão.
		mockMvc.perform(post("/network/participant/nodes/" + nodeId + "/pause")
			.header("X-Session-Token", dono.token()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("NODE_NOT_PAUSABLE"));
	}

	/** Pausar duas vezes não é erro, e retomar o que não foi pausado também não muda nada. */
	@Test
	void pausar_e_retomar_sao_idempotentes() throws Exception {
		var dono = auth.authenticate("net_dono_idem");
		String admin = adminToken();
		String nodeId = storageNodeFor(admin, dono.userId());

		mockMvc.perform(post("/network/participant/nodes/" + nodeId + "/pause")
			.header("X-Session-Token", dono.token()))
			.andExpect(status().isOk());
		mockMvc.perform(post("/network/participant/nodes/" + nodeId + "/pause")
			.header("X-Session-Token", dono.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("PAUSED"));

		mockMvc.perform(post("/network/participant/nodes/" + nodeId + "/resume")
			.header("X-Session-Token", dono.token()))
			.andExpect(status().isOk());
		mockMvc.perform(post("/network/participant/nodes/" + nodeId + "/resume")
			.header("X-Session-Token", dono.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ACTIVE"));

		// Pausar de novo depois de retomar funciona: o nó já foi PAUSED e voltou, e isso não pode
		// ficar "grudado".
		mockMvc.perform(post("/network/participant/nodes/" + nodeId + "/pause")
			.header("X-Session-Token", dono.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("PAUSED"));
	}

	/**
	 * Nó em quarentena não é pausável: a pausa devolveria a {@code ACTIVE}, que seria a saída da
	 * quarentena pela tela do participante — a análise humana deixaria de existir.
	 */
	@Test
	void no_em_quarentena_nao_e_pausavel() throws Exception {
		var dono = auth.authenticate("net_dono_quarentena");
		String admin = adminToken();
		String nodeId = storageNodeFor(admin, dono.userId());

		mockMvc
			.perform(post("/admin/network/nodes/" + nodeId + "/status").header("X-Session-Token", admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("status", "QUARANTINED"))))
			.andExpect(status().isOk());

		mockMvc.perform(post("/network/participant/nodes/" + nodeId + "/pause")
			.header("X-Session-Token", dono.token()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("NODE_NOT_PAUSABLE"));
	}

	/** Nó de outro participante é 404 — 403 confirmaria que o id existe. */
	@Test
	void nao_pausa_no_de_outro_participante() throws Exception {
		var dono = auth.authenticate("net_dono_pausa_alheia");
		var outro = auth.authenticate("net_outro_pausa");
		String admin = adminToken();
		String nodeId = storageNodeFor(admin, outro.userId());

		mockMvc.perform(post("/network/participant/nodes/" + nodeId + "/pause")
			.header("X-Session-Token", dono.token()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NODE_UNKNOWN"));

		// E o nó do outro continua operando: um 404 que ainda mudasse o status seria pior do que
		// um 200 — o dono do nó não teria como saber por que ele parou de trabalhar.
		mockMvc.perform(get("/network/participant/nodes/" + nodeId).header("X-Session-Token", outro.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ACTIVE"));

		mockMvc.perform(post("/network/participant/nodes/" + nodeId + "/resume")
			.header("X-Session-Token", dono.token()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NODE_UNKNOWN"));
	}

	private tools.jackson.databind.JsonNode heartbeat(String nodeId, String key) throws Exception {
		Map<String, Object> observed = new HashMap<>();
		observed.put("cpuPercent", 3.0);
		observed.put("memoryMb", 512);
		observed.put("runningTasks", 0);
		var result = mockMvc
			.perform(post("/network/nodes/heartbeat").header("X-Node-Id", nodeId).header("X-Node-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("runtimeVersion", "0.1.0", "observed", observed))))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}

	private String adminToken() throws Exception {
		var session = auth.authenticate("net_pausa_admin");
		TestDatabase.setAdmin(session.userId());
		return session.token();
	}

	private String storageNodeFor(String admin, String ownerUserId) throws Exception {
		// O displayName carrega o dono: a suíte H2 é COMPARTILHADA, e o registro de conclusão de
		// despacho resolve o nó por nome. Um nome fixo aqui batia com o de outro teste e o
		// heartbeat deste respondia a pausa do outro — falha intermitente do CI inteiro.
		var tokenResult = mockMvc
			.perform(post("/admin/network/nodes/provision-token").header("X-Session-Token", admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("displayName", "no-da-pausa-" + ownerUserId,
					"ownerUserId", ownerUserId, "profiles", List.of("NODE_STORAGE")))))
			.andExpect(status().isOk())
			.andReturn();
		String provisionToken = objectMapper.readTree(tokenResult.getResponse().getContentAsString())
			.get("token").asString();
		var enroll = mockMvc
			.perform(post("/network/nodes/enroll").contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("provisionToken", provisionToken, "declared",
					Map.of("cpuCores", 4, "memoryMb", 8192, "diskMb", 102400, "bandwidthMbps", 100)))))
			.andExpect(status().isOk())
			.andReturn();
		String nodeId = objectMapper.readTree(enroll.getResponse().getContentAsString()).get("nodeId").asString();
		mockMvc.perform(post("/admin/network/nodes/" + nodeId + "/approve").header("X-Session-Token", admin))
			.andExpect(status().isOk());
		return nodeId;
	}

	private String issueKey(String admin, String nodeId) throws Exception {
		var result = mockMvc
			.perform(post("/admin/network/nodes/" + nodeId + "/keys").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("apiKey").asString();
	}

}