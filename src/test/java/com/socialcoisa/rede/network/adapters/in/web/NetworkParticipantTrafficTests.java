package com.socialcoisa.rede.network.adapters.in.web;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
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

import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestDatabase;

import tools.jackson.databind.ObjectMapper;

/**
 * O participante vendo <b>tráfego</b> e <b>disponibilidade</b> da própria máquina (brief §18:
 * "consultar tráfego", "consultar disponibilidade").
 *
 * <p>O tráfego é o que o nó mede nas interfaces dele e manda no heartbeat (V345). A disponibilidade
 * é o que a rede sabe de verdade: quantas vezes ele voltou de uma ausência e por quanto tempo,
 * contado nas linhas {@code NETWORK_NODE_RECONNECTED} que a §8.6 já grava. Nenhum dos dois é
 * inventado no Control Plane.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class NetworkParticipantTrafficTests {

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
		jdbc.update("delete from network_node_logs");
		jdbc.update("delete from network_node_samples");
		jdbc.update("delete from network_node_keys");
		jdbc.update("delete from network_provision_tokens");
		jdbc.update("delete from network_nodes");
		jdbc.update("delete from audit_logs where action = 'NETWORK_NODE_RECONNECTED'");
	}

	/**
	 * O tráfego acumulado chega à tela, e a proporção de saída é calculada aqui — não é um segundo
	 * número que o nó manda, para que ninguém possa dizer 90% de saída com 1 GB contra 10 GB de
	 * entrada.
	 */
	@Test
	void participante_ve_o_trafego_medido_e_a_parcela_de_saida() throws Exception {
		var dono = auth.authenticate("net_dono_trafego");
		String admin = adminToken();
		String nodeId = storageNodeFor(admin, dono.userId());
		String key = issueKey(admin, nodeId);

		heartbeat(nodeId, key, 2_000L, 6_000L);

		mockMvc.perform(get("/network/participant/nodes/" + nodeId).header("X-Session-Token", dono.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.trafficInMb").value(2000))
			.andExpect(jsonPath("$.trafficOutMb").value(6000))
			// 6000 / (2000 + 6000).
			.andExpect(jsonPath("$.outboundSharePercent").value(75.0));
	}

	/**
	 * Runtime que ainda não mede tráfego: os dois contadores e a proporção ficam <b>nulos</b>.
	 * Zero seria "não transferiu nada", que é outra afirmação — e o participante ajustaria o
	 * limite de banda a partir de um número que ele nunca mediu.
	 */
	@Test
	void no_sem_medida_de_trafego_tem_nulo_nao_zero() throws Exception {
		var dono = auth.authenticate("net_dono_sem_trafego");
		String admin = adminToken();
		String nodeId = storageNodeFor(admin, dono.userId());
		String key = issueKey(admin, nodeId);
		heartbeat(nodeId, key, null, null);

		mockMvc.perform(get("/network/participant/nodes/" + nodeId).header("X-Session-Token", dono.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.trafficInMb").value(nullValue()))
			.andExpect(jsonPath("$.trafficOutMb").value(nullValue()))
			.andExpect(jsonPath("$.outboundSharePercent").value(nullValue()));

		// E o total agregado também é nulo: a soma é do que o nó mediu, e ele não mediu nada. Zero
		// aqui faria a tela afirmar "0 MB transferidos" para uma máquina cujo runtime só não conta.
		mockMvc.perform(get("/network/participant/nodes/" + nodeId).header("X-Session-Token", dono.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.trafficTotalMb").value(nullValue()));
	}

	/**
	 * Só metade medida: a entrada é conhecida e a saída não. A saída continua nula (não sabemos),
	 * mas a proporção é calculável porque a base existe — 0 de 2000 é um zero verdadeiro, e não
	 * "sem medida".
	 */
	@Test
	void apenas_entrada_medida_ja_permite_a_parcela_de_saida() throws Exception {
		var dono = auth.authenticate("net_dono_meio_trafego");
		String admin = adminToken();
		String nodeId = storageNodeFor(admin, dono.userId());
		String key = issueKey(admin, nodeId);
		heartbeat(nodeId, key, 2_000L, null);

		mockMvc.perform(get("/network/participant/nodes/" + nodeId).header("X-Session-Token", dono.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.trafficInMb").value(2000))
			.andExpect(jsonPath("$.trafficOutMb").value(nullValue()))
			.andExpect(jsonPath("$.outboundSharePercent").value(0.0));
	}

	/**
	 * A disponibilidade é a soma das ausências registradas, e a última é a mais recente. A tela
	 * mostra as três coisas: quantas quedas, quanto somado, e o caso recente.
	 */
	@Test
	void participante_ve_quedas_e_tempo_fora_somado() throws Exception {
		var dono = auth.authenticate("net_dono_disponibilidade");
		String admin = adminToken();
		String nodeId = storageNodeFor(admin, dono.userId());
		reconnect(nodeId, 40, Instant.now().minusSeconds(7200));
		reconnect(nodeId, 600, Instant.now().minusSeconds(3600));
		reconnect(nodeId, 20, Instant.now().minusSeconds(600));

		mockMvc.perform(get("/network/participant/nodes/" + nodeId).header("X-Session-Token", dono.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.outages").value(3))
			.andExpect(jsonPath("$.measuredOutages").value(3))
			// 40 + 600 + 20.
			.andExpect(jsonPath("$.offlineSeconds").value(660))
			// A mais recente, não a maior.
			.andExpect(jsonPath("$.lastOfflineSeconds").value(20))
			// Três de três medidas: a soma é o total, e a tela pode apresentá-la como tal.
			.andExpect(jsonPath("$.availabilityComplete").value(true));
	}

	/**
	 * Linha de reconexão sem {@code offlineForSeconds} conta como <b>queda</b> — ela prova que houve
	 * uma —, mas não entra na soma: somar zero diria "voltou no mesmo instante". A última ausência
	 * conhecida também não é essa linha.
	 */
	@Test
	void linha_sem_intervalo_conta_como_queda_mas_nao_como_zero_segundos() throws Exception {
		var dono = auth.authenticate("net_dono_linha_ilegivel");
		String admin = adminToken();
		String nodeId = storageNodeFor(admin, dono.userId());
		reconnect(nodeId, 120, Instant.now().minusSeconds(3600));
		reconnectRaw(nodeId, "{}", Instant.now().minusSeconds(60));

		mockMvc.perform(get("/network/participant/nodes/" + nodeId).header("X-Session-Token", dono.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.outages").value(2))
			.andExpect(jsonPath("$.offlineSeconds").value(120))
			.andExpect(jsonPath("$.lastOfflineSeconds").value(120))
			// A linha sem intervalo NÃO é "0 segundos": se entrasse na soma como zero, o tempo fora
			// apareceria menor do que as duas linhas provam. Ela entra em `outages` (houve queda) e
			// não em `measuredOutages` — é esta separação que diz à tela que a soma é piso, não total.
			.andExpect(jsonPath("$.measuredOutages").value(1))
			.andExpect(jsonPath("$.availabilityComplete").value(false));
	}

	/** Nó que nunca voltou de uma ausência: zero quedas, zero segundos, nenhuma "última". */
	@Test
	void no_sem_quedas_tem_zero_e_nenhuma_ultima_ausencia() throws Exception {
		var dono = auth.authenticate("net_dono_sem_queda");
		String admin = adminToken();
		String nodeId = storageNodeFor(admin, dono.userId());

		mockMvc.perform(get("/network/participant/nodes").header("X-Session-Token", dono.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].outages").value(0))
			.andExpect(jsonPath("$[0].offlineSeconds").value(0))
			.andExpect(jsonPath("$[0].measuredOutages").value(0))
			// Sem nenhuma queda, não há o que medir faltando: zero é o total, não um piso.
			.andExpect(jsonPath("$[0].availabilityComplete").value(true))
			.andExpect(jsonPath("$[0].lastOfflineSeconds").value(nullValue()))
			// Nunca bateu coração: não está "online" — a janela de 180 s não se cumpre.
			.andExpect(jsonPath("$[0].online").value(false));
	}

	/** O mesmo `online` do painel: quem bateu coração agora está; quem não bateu, não. */
	@Test
	void online_segue_a_mesma_janela_do_painel() throws Exception {
		var dono = auth.authenticate("net_dono_online");
		String admin = adminToken();
		String nodeId = storageNodeFor(admin, dono.userId());
		String key = issueKey(admin, nodeId);
		heartbeat(nodeId, key, 1L, 1L);

		mockMvc.perform(get("/network/participant/nodes/" + nodeId).header("X-Session-Token", dono.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.online").value(true));

		// Última vez visto há 10 minutos: fora da janela de 180 s.
		jdbc.update("update network_nodes set last_seen_at = ? where id = ?",
			Timestamp.from(Instant.now().minusSeconds(600)), nodeId);

		mockMvc.perform(get("/network/participant/nodes/" + nodeId).header("X-Session-Token", dono.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.online").value(false));
	}

	private void reconnect(String nodeId, int offlineSeconds, Instant at) {
		reconnectRaw(nodeId, "{\"offlineForSeconds\":" + offlineSeconds + "}", at);
	}

	private void reconnectRaw(String nodeId, String metadata, Instant at) {
		jdbc.update(
			"insert into audit_logs (id, actor_id, action, target_id, metadata, created_at) values (?,?,?,?,?,?)",
			"al_" + java.util.UUID.randomUUID(), nodeId, "NETWORK_NODE_RECONNECTED", nodeId, metadata,
			Timestamp.from(at));
	}

	private void heartbeat(String nodeId, String key, Long bytesIn, Long bytesOut) throws Exception {
		Map<String, Object> observed = new HashMap<>();
		observed.put("cpuPercent", 3.0);
		observed.put("memoryMb", 512);
		observed.put("runningTasks", 0);
		if (bytesIn != null) {
			observed.put("bytesInMb", bytesIn);
		}
		if (bytesOut != null) {
			observed.put("bytesOutMb", bytesOut);
		}
		mockMvc
			.perform(post("/network/nodes/heartbeat").header("X-Node-Id", nodeId).header("X-Node-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("runtimeVersion", "0.1.0", "observed", observed))))
			.andExpect(status().isOk());
	}

	private String adminToken() throws Exception {
		var session = auth.authenticate("net_trafego_admin");
		TestDatabase.setAdmin(session.userId());
		return session.token();
	}

	private String storageNodeFor(String admin, String ownerUserId) throws Exception {
		var tokenResult = mockMvc
			.perform(post("/admin/network/nodes/provision-token").header("X-Session-Token", admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("displayName", "no-do-trafego",
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