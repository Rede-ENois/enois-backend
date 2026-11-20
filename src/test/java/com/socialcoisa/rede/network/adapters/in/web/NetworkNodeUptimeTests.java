package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
 * Disponibilidade histórica do nó (brief §7 e §18, V369) pela rota de verdade: heartbeat grava o
 * contador do dia, o admin e o participante leem a série.
 *
 * <p><b>Antes:</b> o item "disponibilidade histórica" do §7 era não entregue — a rede tinha
 * transições na trilha, não uma série por dia. E a soma do tempo fora vinha de uma amostra de 200
 * linhas comparada com o total de quedas: a partir da 201ª, o número virava piso para sempre.</p>
 *
 * <p><b>Agora:</b> um contador por dia alimentado pelo heartbeat; a série é a leitura; e a soma da
 * trilha lê todas as linhas, então ela é o total.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class NetworkNodeUptimeTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private com.socialcoisa.rede.network.application.port.out.NodeUptimeRepository uptime;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
		jdbc.update("delete from network_node_uptime_daily");
		jdbc.update("delete from network_node_logs");
		jdbc.update("delete from network_node_samples");
		jdbc.update("delete from network_node_keys");
		jdbc.update("delete from network_provision_tokens");
		jdbc.update("delete from network_nodes");
		jdbc.update("delete from audit_logs where action = 'NETWORK_NODE_RECONNECTED'");
	}

	/**
	 * Dois heartbeats com 5 min de intervalo e a janela de "vivo" de 180 s: o dia registra 5 min
	 * observados e 3 min no ar. É a regra do trecho, medida no banco de verdade.
	 */
	@Test
	void o_segundo_heartbeat_soma_ao_dia_o_trecho_no_ar_e_o_observado() throws Exception {
		var dono = auth.authenticate("net_uptime_trecho");
		String admin = adminToken();
		String nodeId = storageNodeFor(admin, dono.userId());
		String key = issueKey(admin, nodeId);

		Instant agora = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
		jdbc.update("update network_nodes set last_seen_at = ? where id = ?",
			Timestamp.from(agora.minusSeconds(300)), nodeId);
		heartbeat(nodeId, key);

		var linha = jdbc.queryForMap("select present_seconds, observed_seconds, dia from network_node_uptime_daily "
			+ "where node_id = ?", nodeId);
		long presente = ((Number) linha.get("present_seconds")).longValue();
		long observado = ((Number) linha.get("observed_seconds")).longValue();

		assertThat(observado).isBetween(299L, 303L);
		// 3 min de janela no ar; o resto é tempo fora, e o trecho todo é observado.
		assertThat(presente).isBetween(178L, 182L);
		assertThat(presente).isLessThan(observado);
		assertThat(linha.get("dia").toString())
			.isEqualTo(LocalDate.now(ZoneOffset.UTC).toString());
	}

	/**
	 * O contador recusa o reenvio e soma o trecho que chega fora de ordem.
	 *
	 * <p>São dois fatos diferentes. O <b>reenvio</b> é o mesmo trecho que já fechou o dia: somar de
	 * novo diria que o nó esteve no ar por mais tempo do que existe. Um trecho <b>fora de ordem</b>
	 * (chega depois, terminando antes do último contato gravado) é tempo observado que ainda não
	 * estava contado, e soma — o dia acumula o que houve, não só o que chegou em sequência.</p>
	 */
	@Test
	void o_contador_recusa_o_reenvio_e_soma_o_trecho_fora_de_ordem() throws Exception {
		var dono = auth.authenticate("net_uptime_reenvio");
		String admin = adminToken();
		String nodeId = storageNodeFor(admin, dono.userId());
		issueKey(admin, nodeId);
		LocalDate hoje = LocalDate.now(ZoneOffset.UTC);
		Instant agora = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);

		// O dia contou até um contato 1 h atrás (o último heartbeat).
		Instant ultimoContato = agora.minusSeconds(3_600);
		assertThat(uptime.add(nodeId, hoje, 120, 120, ultimoContato.minusSeconds(120), ultimoContato)).isTrue();
		assertThat(observado(nodeId)).isEqualTo(120L);

		// O mesmo contato publicado de novo não soma: é o mesmo intervalo fechado no mesmo instante.
		assertThat(uptime.add(nodeId, hoje, 120, 120, ultimoContato.minusSeconds(120), ultimoContato))
			.as("reenvio do mesmo contato").isFalse();
		assertThat(observado(nodeId)).isEqualTo(120L);

		// Fora de ordem: um trecho de 60 s que terminou antes do último contato e chegou depois.
		Instant de = ultimoContato.minusSeconds(600);
		Instant ate = ultimoContato.minusSeconds(540);
		assertThat(uptime.add(nodeId, hoje, 60, 60, de, ate)).as("trecho fora de ordem entra").isTrue();
		assertThat(observado(nodeId)).as("trecho fora de ordem soma").isEqualTo(180L);
		assertThat(presente(nodeId)).isEqualTo(180L);

		// O reenvio dele também é recusado: `received_at` guarda a chegada, e a chegada é ele.
		assertThat(uptime.add(nodeId, hoje, 60, 60, de, ate)).as("reenvio do fora de ordem").isFalse();
		assertThat(observado(nodeId)).isEqualTo(180L);

		// O instante é gravado em UTC, e não na hora da JVM: outra sessão (painel, harness, SQL) lê
		// o mesmo valor.
		assertThat(jdbc.queryForObject("select formatdatetime(first_seen_at, 'yyyy-MM-dd HH:mm:ss') "
			+ "from network_node_uptime_daily where node_id = ?", String.class, nodeId))
			.isEqualTo(de.toString().substring(0, 10) + " " + de.toString().substring(11, 19));
		assertThat(jdbc.queryForObject("select formatdatetime(last_seen_at, 'yyyy-MM-dd HH:mm:ss') "
			+ "from network_node_uptime_daily where node_id = ?", String.class, nodeId))
			.isEqualTo(ultimoContato.toString().substring(0, 10) + " " + ultimoContato.toString().substring(11, 19));
	}

	/**
	 * A soma da trilha lê TODAS as reconexões. Antes parava em 200 e o número virava piso: com 205
	 * quedas de 10 s, a tela dizia "2 000 s, incompleto" em vez de "2 050 s, total".
	 */
	@Test
	void a_soma_das_ausencias_e_o_total_e_nao_uma_amostra() throws Exception {
		var dono = auth.authenticate("net_uptime_amostra");
		String admin = adminToken();
		String nodeId = storageNodeFor(admin, dono.userId());
		for (int i = 0; i < 205; i++) {
			reconnect(nodeId, 10, Instant.now().minusSeconds(10_000 - i));
		}

		mockMvc.perform(get("/network/participant/nodes/" + nodeId).header("X-Session-Token", dono.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.outages").value(205))
			.andExpect(jsonPath("$.measuredOutages").value(205))
			// 205 × 10 s. A amostra de 200 daria 2000.
			.andExpect(jsonPath("$.offlineSeconds").value(2050))
			.andExpect(jsonPath("$.availabilityComplete").value(true));
	}

	/** Admin e participante leem a mesma série, na mesma janela. */
	@Test
	void admin_e_participante_veem_a_mesma_serie_por_dia() throws Exception {
		var dono = auth.authenticate("net_uptime_serie");
		String admin = adminToken();
		String nodeId = storageNodeFor(admin, dono.userId());
		LocalDate hoje = LocalDate.now(ZoneOffset.UTC);
		semearDia(nodeId, hoje.minusDays(1), 86_400, 86_400);
		semearDia(nodeId, hoje, 86_400 - 3_600, 86_400);

		mockMvc.perform(get("/admin/network/nodes/" + nodeId + "/uptime").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.measuredDays").value(2))
			.andExpect(jsonPath("$.observedSeconds").value(172_800))
			.andExpect(jsonPath("$.presentSeconds").value(169_200))
			.andExpect(jsonPath("$.offlineSeconds").value(3_600))
			// (86400 + 82800) / 172800 = 97,916666...%
			.andExpect(jsonPath("$.days.length()").value(2));

		mockMvc.perform(get("/network/participant/nodes/" + nodeId + "/uptime").header("X-Session-Token",
			dono.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.measuredDays").value(2))
			.andExpect(jsonPath("$.presentSeconds").value(169_200));
	}

	/** Nó de outra conta é 404 na rota do participante — não 403, que confirmaria o id. */
	@Test
	void participante_de_outra_conta_nao_le_a_serie_alheia() throws Exception {
		var dono = auth.authenticate("net_uptime_dono");
		var outro = auth.authenticate("net_uptime_outro");
		String admin = adminToken();
		String nodeId = storageNodeFor(admin, dono.userId());

		mockMvc.perform(get("/network/participant/nodes/" + nodeId + "/uptime")
			.header("X-Session-Token", outro.token()))
			.andExpect(status().isNotFound());
	}

	/** A janela vai de 1 a 90 dias: pedir 365 não traz mais do que noventa. */
	@Test
	void a_janela_pedida_e_limitada_a_noventa_dias() throws Exception {
		var dono = auth.authenticate("net_uptime_janela");
		String admin = adminToken();
		String nodeId = storageNodeFor(admin, dono.userId());
		LocalDate hoje = LocalDate.now(ZoneOffset.UTC);
		semearDia(nodeId, hoje.minusDays(100), 86_400, 86_400);
		semearDia(nodeId, hoje, 86_400, 86_400);

		mockMvc.perform(get("/admin/network/nodes/" + nodeId + "/uptime?days=365").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.measuredDays").value(1));
	}

	/** Nó desconhecido no painel é 404, como nas outras rotas por nó. */
	@Test
	void no_desconhecido_e_404() throws Exception {
		String admin = adminToken();
		mockMvc.perform(get("/admin/network/nodes/nd_nao_existe/uptime").header("X-Session-Token", admin))
			.andExpect(status().isNotFound());
	}

	/** O observado contado para o nó, ou 0 quando o dia ainda não tem linha. */
	private long observado(String nodeId) {
		Long v = jdbc.queryForObject(
			"select observed_seconds from network_node_uptime_daily where node_id = ?", Long.class, nodeId);
		return v == null ? 0 : v;
	}

	/** O tempo no ar contado para o nó, ou 0 quando o dia ainda não tem linha. */
	private long presente(String nodeId) {
		Long v = jdbc.queryForObject(
			"select present_seconds from network_node_uptime_daily where node_id = ?", Long.class, nodeId);
		return v == null ? 0 : v;
	}

	private void semearDia(String nodeId, LocalDate dia, long presente, long observado) {
		jdbc.update("""
			insert into network_node_uptime_daily (node_id, dia, present_seconds, observed_seconds,
			  first_seen_at, last_seen_at, received_at) values (?, ?, ?, ?, ?, ?, ?)
			""", nodeId, java.sql.Date.valueOf(dia), presente, observado,
			java.time.LocalDateTime.of(dia, java.time.LocalTime.MIDNIGHT),
			java.time.LocalDateTime.of(dia, java.time.LocalTime.of(23, 59)),
			java.time.LocalDateTime.of(dia, java.time.LocalTime.of(23, 59)));
	}

	private void reconnect(String nodeId, int offlineSeconds, Instant at) {
		jdbc.update(
			"insert into audit_logs (id, actor_id, action, target_id, metadata, created_at) values (?,?,?,?,?,?)",
			"al_" + java.util.UUID.randomUUID(), nodeId, "NETWORK_NODE_RECONNECTED", nodeId,
			"{\"offlineForSeconds\":" + offlineSeconds + "}", Timestamp.from(at));
	}

	private void heartbeat(String nodeId, String key) throws Exception {
		Map<String, Object> observed = new HashMap<>();
		observed.put("cpuPercent", 3.0);
		observed.put("memoryMb", 512);
		observed.put("runningTasks", 0);
		mockMvc.perform(post("/network/nodes/heartbeat").header("X-Node-Id", nodeId).header("X-Node-Key", key)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("runtimeVersion", "0.1.0", "observed", observed))))
			.andExpect(status().isOk());
	}

	private String adminToken() throws Exception {
		var session = auth.authenticate("net_uptime_admin");
		TestDatabase.setAdmin(session.userId());
		return session.token();
	}

	private String storageNodeFor(String admin, String ownerUserId) throws Exception {
		var tokenResult = mockMvc.perform(post("/admin/network/nodes/provision-token")
			.header("X-Session-Token", admin).contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("displayName", "no-da-disponibilidade",
				"ownerUserId", ownerUserId, "profiles", List.of("NODE_STORAGE")))))
			.andExpect(status().isOk())
			.andReturn();
		String provisionToken = objectMapper.readTree(tokenResult.getResponse().getContentAsString())
			.get("token").asString();
		var enroll = mockMvc.perform(post("/network/nodes/enroll").contentType(MediaType.APPLICATION_JSON)
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
		var result = mockMvc.perform(post("/admin/network/nodes/" + nodeId + "/keys").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("apiKey").asString();
	}
}