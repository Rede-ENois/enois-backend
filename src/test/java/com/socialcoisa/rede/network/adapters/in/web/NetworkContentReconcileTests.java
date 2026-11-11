package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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

import com.socialcoisa.rede.network.application.ContentReconcileWorker;
import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestDatabase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Rede cooperativa, fase 3: reconciliação periódica do conteúdo de borda.
 *
 * <p>O que estes testes fixam, porque cada um é um jeito de errar:</p>
 * <ul>
 * <li>a varredura confere o nó <b>sem</b> o nó pedir nada — é o buraco que ela existe para fechar;</li>
 * <li>divergência vira {@code SYNC_DIVERGENCE} uma vez só, não a cada rodada: um nó parado por uma
 * hora não pode encher a trilha de auditoria com dezenas de linhas idênticas;</li>
 * <li>quando o nó volta a ficar em dia, o alerta fecha com {@code SYNC_RECOVERED} e a linha deixa
 * de aparecer no painel;</li>
 * <li>a hora da divergência é preservada entre rodadas — o painel mostra "divergente desde", não
 * "divergente desde a última rodada";</li>
 * <li>nó suspenso não é conferido, e as linhas dele não ocupam o lote da fila;</li>
 * <li>a rota do nó confere só o que ele mesmo declarou ter, nunca o snapshot de outro nó;</li>
 * <li>o painel é admin-only.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class NetworkContentReconcileTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private ContentReconcileWorker worker;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
		jdbc.update("delete from network_content_snapshots");
		jdbc.update("delete from network_content_versions");
		jdbc.update("delete from network_node_keys");
		jdbc.update("delete from network_provision_tokens");
		jdbc.update("delete from network_nodes");
	}

	// ---------------------------------------------------------------- a varredura

	/**
	 * O buraco que a varredura fecha: o nó sincronizou, o dono editou o perfil, e o nó NUNCA chama
	 * a reconciliação. Antes desta rodada, nada acontecia — o painel mostrava "sem divergência".
	 */
	@Test
	void varreduraAcusaDivergenciaSemONoPedirNada() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "borda-a", List.of("NODE_EDGE"));
		var session = auth.authenticate("reconcile_sweep_1");
		String username = usernameOf(session);

		syncProfile(node, username);

		// O dono edita depois da sincronia: o nó está com o corpo velho.
		editHeadline(session, "Depois da sincronia", "reconcile-sweep-1");

		// Q0: sem a varredura — o comportamento antigo — o painel não sabe de nada, porque o nó
		// nunca chamou /network/content/reconcile. É este buraco que a varredura fecha.
		assertThat(divergences(admin).size()).isZero();
		assertThat(auditCount(node, "SYNC_DIVERGENCE", username)).isZero();

		assertThat(worker.runOnce()).isEqualTo(1);

		JsonNode divergences = divergences(admin);
		assertThat(divergences.size()).isEqualTo(1);
		assertThat(divergences.get(0).get("nodeId").asText()).isEqualTo(node.id());
		assertThat(divergences.get(0).get("reason").asText()).isEqualTo("CONTENT_STALE");
		assertThat(divergences.get(0).get("contentId").asText()).isEqualTo(username);
		assertThat(divergences.get(0).get("divergenceAt").isNull()).isFalse();
		assertThat(auditCount(node, "SYNC_DIVERGENCE", username)).isEqualTo(1);
	}

	/**
	 * A varredura roda a cada poucos minutos. Se cada rodada gravasse um alerta, um nó parado por
	 * uma hora encheria a trilha com dezenas de linhas idênticas e esconderia o que importa. O
	 * alerta é da TRANSIÇÃO, e a hora da divergência não anda.
	 */
	@Test
	void divergenciaPersistenteAuditaUmaVezSoEPreservaAHora() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "borda-a", List.of("NODE_EDGE"));
		var session = auth.authenticate("reconcile_sweep_2");
		String username = usernameOf(session);

		syncProfile(node, username);
		editHeadline(session, "Mudou uma vez", "reconcile-sweep-2");

		worker.runOnce();
		String firstAt = divergenceAt(node, username);

		// Três rodadas depois, nada mudou: nenhum alerta novo, mesma hora de divergência.
		worker.runOnce();
		worker.runOnce();
		worker.runOnce();

		assertThat(auditCount(node, "SYNC_DIVERGENCE", username)).isEqualTo(1);
		assertThat(divergenceAt(node, username)).isEqualTo(firstAt);
		assertThat(divergences(admin).size()).isEqualTo(1);
	}

	/**
	 * Quando o nó volta a ficar em dia, o alerta fecha: a linha sai do painel e a recuperação é
	 * auditada. Sem isso, o painel mostraria divergência para sempre depois do primeiro incidente.
	 */
	@Test
	void noQueVoltaAFicarEmDiaFechaOAlerta() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "borda-a", List.of("NODE_EDGE"));
		var session = auth.authenticate("reconcile_sweep_3");
		String username = usernameOf(session);

		syncProfile(node, username);
		editHeadline(session, "Mudou", "reconcile-sweep-3");
		worker.runOnce();
		assertThat(divergences(admin).size()).isEqualTo(1);

		// O nó baixa de novo e confirma: agora está na versão vigente.
		syncProfile(node, username);
		assertThat(worker.runOnce()).isZero();

		assertThat(divergences(admin).size()).isZero();
		assertThat(auditCount(node, "SYNC_RECOVERED", username)).isEqualTo(1);
		assertThat(divergenceAt(node, username)).isNull();
	}

	/**
	 * O agregado que sumiu da origem é divergência: o nó está servindo o que não existe mais. E a
	 * varredura não pode morrer por causa disso — o 404 derruba só a leitura daquele item.
	 */
	@Test
	void agregadoQueSumiuNaoDerrubaARodada() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "borda-a", List.of("NODE_EDGE"));
		var session = auth.authenticate("reconcile_sweep_4");
		String username = usernameOf(session);
		String communityId = createCommunity(auth.authenticate("reconcile_sweep_4b"), "Comunidade Some");

		syncProfile(node, username);
		syncCommunity(node, communityId);
		TestDatabase.deleteCommunity(communityId);

		// Duas linhas na fila, uma delas com origem ausente: a rodada confere as duas.
		assertThat(worker.runOnce()).isEqualTo(1);

		JsonNode divergences = divergences(admin);
		assertThat(divergences.size()).isEqualTo(1);
		assertThat(divergences.get(0).get("reason").asText()).isEqualTo("CONTENT_GONE");
		assertThat(divergences.get(0).get("contentId").asText()).isEqualTo(communityId);
		assertThat(auditCount(node, "SYNC_DIVERGENCE", communityId)).isEqualTo(1);
	}

	/**
	 * Nó suspenso não é conferido, e as linhas dele não podem ocupar o lote: a fila ordena por
	 * "nunca conferido primeiro", então sem o filtro de status um nó suspenso com muitos agregados
	 * travaria a varredura dos nós ativos para sempre.
	 */
	@Test
	void noSuspensoNaoEOcupaOFila() throws Exception {
		String admin = adminToken();
		Node suspended = activeNode(admin, "borda-suspensa", List.of("NODE_EDGE"));
		Node active = activeNode(admin, "borda-ativa", List.of("NODE_EDGE"));
		var session = auth.authenticate("reconcile_sweep_5");
		String username = usernameOf(session);

		syncProfile(suspended, username);
		syncProfile(active, username);
		editHeadline(session, "Mudou", "reconcile-sweep-5");

		mockMvc
			.perform(post("/admin/network/nodes/" + suspended.id() + "/status").header("X-Session-Token", admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("status", "SUSPENDED"))))
			.andExpect(status().isOk());

		worker.runOnce();

		JsonNode divergences = divergences(admin);
		assertThat(divergences.size()).isEqualTo(1);
		assertThat(divergences.get(0).get("nodeId").asText()).isEqualTo(active.id());
		assertThat(auditCount(suspended, "SYNC_DIVERGENCE", username)).isZero();
	}

	// ---------------------------------------------------------------- a rota do nó

	/**
	 * O nó só confere o que ele mesmo declarou ter. O corpo da requisição diz o que conferir, mas o
	 * snapshot é lido pelo id autenticado — um nó não confere (nem audita) o cache de outro.
	 */
	@Test
	void noNaoConfereOSnapshotDeOutroNo() throws Exception {
		String admin = adminToken();
		Node owner = activeNode(admin, "borda-dona", List.of("NODE_EDGE"));
		Node intruder = activeNode(admin, "borda-intrusa", List.of("NODE_EDGE"));
		var session = auth.authenticate("reconcile_sweep_6");
		String username = usernameOf(session);

		syncProfile(owner, username);
		editHeadline(session, "Mudou", "reconcile-sweep-6");

		// O intruso pede a conferência do agregado que a dona sincronizou: ele não tem snapshot,
		// então não há o que conferir e nada é auditado em nome dele.
		JsonNode result = reconcile(intruder, List.of(Map.of("kind", "PROFILE", "contentId", username)));
		assertThat(result.size()).isZero();
		assertThat(auditCount(intruder, "SYNC_DIVERGENCE", username)).isZero();
		assertThat(auditCount(owner, "SYNC_DIVERGENCE", username)).isZero();

		// A dona pedindo a mesma conferência encontra a divergência.
		JsonNode ownerResult = reconcile(owner, List.of(Map.of("kind", "PROFILE", "contentId", username)));
		assertThat(ownerResult.size()).isEqualTo(1);
		assertThat(ownerResult.get(0).get("reason").asText()).isEqualTo("CONTENT_STALE");
		assertThat(auditCount(owner, "SYNC_DIVERGENCE", username)).isEqualTo(1);
	}

	// ---------------------------------------------------------------- painel

	@Test
	void painelMostraAsContagensEAsDivergencias() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "borda-a", List.of("NODE_EDGE"));
		var session = auth.authenticate("reconcile_sweep_7");
		String username = usernameOf(session);

		syncProfile(node, username);
		editHeadline(session, "Mudou", "reconcile-sweep-7");
		worker.runOnce();

		mockMvc
			.perform(get("/admin/network/content/overview").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.snapshots").value(1))
			.andExpect(jsonPath("$.divergent").value(1));
	}

	@Test
	void apenasAdminVeAReconciliacao() throws Exception {
		var session = auth.authenticate("reconcile_sweep_8");

		mockMvc.perform(get("/admin/network/content/divergences").header("X-Session-Token", session.token()))
			.andExpect(status().isForbidden());
		mockMvc.perform(get("/admin/network/content/overview").header("X-Session-Token", session.token()))
			.andExpect(status().isForbidden());
		mockMvc.perform(post("/admin/network/content/check").header("X-Session-Token", session.token()))
			.andExpect(status().isForbidden());
	}

	// ---------------------------------------------------------------- helpers

	private record Node(String id, String key) {
	}

	private String adminToken() throws Exception {
		var session = auth.authenticate("net_reconcile_admin");
		TestDatabase.setAdmin(session.userId());
		return session.token();
	}

	private String usernameOf(TestAuthHelper.AuthSession session) throws Exception {
		MvcResult me = mockMvc.perform(get("/me").header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(me.getResponse().getContentAsString()).get("username").asText();
	}

	private void editHeadline(TestAuthHelper.AuthSession session, String headline, String idempotencyKey)
			throws Exception {
		mockMvc
			.perform(patch("/me/profile").header("X-Session-Token", session.token())
				.header("Idempotency-Key", idempotencyKey)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("headline", headline))))
			.andExpect(status().isOk());
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

	/** Baixa o conteúdo e confirma o que guardou: é o que deixa o nó em dia. */
	private void syncProfile(Node node, String username) throws Exception {
		sync(node, "PROFILE", username);
	}

	private void syncCommunity(Node node, String communityId) throws Exception {
		sync(node, "COMMUNITY", communityId);
	}

	private void sync(Node node, String kind, String contentId) throws Exception {
		MvcResult served = mockMvc
			.perform(get("/network/content/" + kind + "/" + contentId).header("X-Node-Id", node.id())
				.header("X-Node-Key", node.key()))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode body = objectMapper.readTree(served.getResponse().getContentAsString());
		mockMvc
			.perform(post("/network/content/" + kind + "/" + contentId + "/synced").header("X-Node-Id", node.id())
				.header("X-Node-Key", node.key()).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("sourceVersion", body.get("version").asLong(),
					"contentHash", body.get("contentHash").asText()))))
			.andExpect(status().isOk());
	}

	private JsonNode reconcile(Node node, List<Map<String, String>> snapshots) throws Exception {
		MvcResult result = mockMvc
			.perform(post("/network/content/reconcile").header("X-Node-Id", node.id())
				.header("X-Node-Key", node.key()).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("snapshots", snapshots))))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}

	private JsonNode divergences(String admin) throws Exception {
		MvcResult result = mockMvc
			.perform(get("/admin/network/content/divergences").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}

	private String createCommunity(TestAuthHelper.AuthSession owner, String name) throws Exception {
		MvcResult result = mockMvc
			.perform(post("/communities").header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "reconcile-com-" + name.toLowerCase(java.util.Locale.ROOT))
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", name))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private int auditCount(Node node, String action, String targetId) {
		Integer count = jdbc.queryForObject(
			"select count(*) from audit_logs where action = ? and actor_id = ? and target_id = ?", Integer.class,
			action, node.id(), targetId);
		return count == null ? 0 : count;
	}

	private String divergenceAt(Node node, String contentId) {
		return jdbc.queryForObject("""
			select cast(divergence_at as varchar) from network_content_snapshots
			where node_id = ? and content_type = 'PROFILE' and content_id = ?
			""", String.class, node.id(), contentId);
	}
}
