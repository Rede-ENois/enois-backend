package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

import com.socialcoisa.rede.network.application.ContentReconcileWorker;
import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestDatabase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Fase 6, replicação geográfica: a replicação existe por nó, e a pergunta da contingência é por
 * região.
 *
 * <p>Até esta rodada "replicado" queria dizer "cada nó tem versão e hash e a reconciliação o
 * confere". Isso responde "o nó X está em dia?", nunca "a rede sobrevive à queda de uma região?" —
 * e as duas perguntas divergem: dez nós de borda no mesmo rack são dez cópias e uma região só. Os
 * testes abaixo fixam as duas metades que faltavam:</p>
 * <ul>
 * <li>a varredura de reconciliação não acusa nó de região declarada fora de operação: é a rede que
 * parou de lhe servir (R103 fechou as portas de tarefa), não o cache dele que divergiu. Sem isso o
 * painel enchia de {@code SYNC_DIVERGENCE} no meio do incidente, acusando nó saudável por decisão
 * do Control Plane;</li>
 * <li>o painel mede cobertura <b>por região</b>: quantas regiões distintas têm cópia utilizável de
 * cada tipo, contra quantas regiões a rede tem. Nó parado, sem heartbeat recente ou de região
 * evacuada não cobre região nenhuma.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class NetworkReplicationGeographicTests {

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
		jdbc.update("delete from network_region_states");
		jdbc.update("delete from network_content_snapshots");
		jdbc.update("delete from network_content_versions");
		jdbc.update("delete from network_node_keys");
		jdbc.update("delete from network_provision_tokens");
		jdbc.update("delete from network_nodes");
		jdbc.update("delete from audit_logs where action like 'NETWORK_REGION_%' "
				+ "or action in ('SYNC_DIVERGENCE', 'SYNC_RECOVERED')");
	}

	// ---------------------------------------------------------------- a fila da varredura

	/**
	 * O nó da região evacuada continua {@code ACTIVE} — a região é que saiu —, então a fila antiga
	 * (que filtrava só {@code n.status = 'ACTIVE'}) o conferia e gravava {@code CONTENT_STALE}.
	 *
	 * <p>O nó não divergiu: desde a declaração ele não recebe conteúdo, porque a borda deixou de lhe
	 * servir. Acusá-lo é acusar o nó por uma decisão do Control Plane, e no meio de um incidente o
	 * painel passa a mostrar divergência de saúde onde o problema é evacuação.</p>
	 *
	 * <p>Q0: neutralizada a guarda regional, esta asserção falha — a rodada confere os dois nós e o
	 * da região evacuada recebe {@code CONTENT_STALE}.</p>
	 */
	@Test
	void varreduraNaoAcusaNoDeRegiaoEvacuada() throws Exception {
		String admin = adminToken();
		Node evacuado = activeNode(admin, "borda-sudeste", "sudeste");
		Node saudavel = activeNode(admin, "borda-sul", "sul");
		var session = auth.authenticate("geo_queue_1");
		String username = usernameOf(session);

		syncProfile(evacuado, username);
		syncProfile(saudavel, username);
		editHeadline(session, "Mudou depois da sincronia", "geo-queue-1");

		declare(admin, "sudeste", "queda regional em curso");

		// Rodada única: só o nó da região ainda em operação é conferido.
		assertThat(worker.runOnce()).isEqualTo(1);

		JsonNode divergences = divergences(admin);
		assertThat(divergences.size()).isEqualTo(1);
		assertThat(divergences.get(0).get("nodeId").asText()).isEqualTo(saudavel.id());
		assertThat(divergences.get(0).get("reason").asText()).isEqualTo("CONTENT_STALE");

		// E o nó evacuado não tem auditoria de divergência: ele não foi sequer conferido.
		assertThat(auditCount(evacuado, "SYNC_DIVERGENCE", username)).isZero();
		assertThat(checkedCount(evacuado, username)).isZero();
	}

	/**
	 * A declaração é da REGIÃO, não da grafia. Um nó que gravou " Sudeste " (o instalador não
	 * normaliza antes de mandar) é evacuado pela mesma declaração de "sudeste" — e sai da fila como
	 * o outro. Sem a normalização, a guarda não acharia esse nó e o incidente continuaria acusando.
	 */
	@Test
	void guardaRegionalComparaRegiaoNormalizada() throws Exception {
		String admin = adminToken();
		Node grafiaDiferente = activeNode(admin, "borda-caixa-alta", " SUDESTE ");
		var session = auth.authenticate("geo_queue_2");
		String username = usernameOf(session);

		syncProfile(grafiaDiferente, username);
		editHeadline(session, "Mudou", "geo-queue-2");
		declare(admin, "sudeste ", "mesma região, outra grafia");

		assertThat(worker.runOnce()).isZero();
		assertThat(divergences(admin).size()).isZero();
		assertThat(auditCount(grafiaDiferente, "SYNC_DIVERGENCE", username)).isZero();
	}

	/**
	 * Quando a região volta, o nó volta à fila sozinho: a exclusão é a declaração vigente, não uma
	 * marca no snapshot. Não é preciso limpar nada à mão no banco nem tocar em nó nenhum.
	 */
	@Test
	void regiaoDeVoltaDevolveONoAFila() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "borda-sudeste", "sudeste");
		var session = auth.authenticate("geo_queue_3");
		String username = usernameOf(session);

		syncProfile(node, username);
		editHeadline(session, "Mudou", "geo-queue-3");
		declare(admin, "sudeste", "incidente");
		assertThat(worker.runOnce()).isZero();

		clear(admin, "sudeste");

		assertThat(worker.runOnce()).isEqualTo(1);
		assertThat(divergences(admin).get(0).get("nodeId").asText()).isEqualTo(node.id());
	}

	// ---------------------------------------------------------------- cobertura por região

	/**
	 * A conta que separa "replicado" de "replicado geograficamente": quatro nós, duas regiões, quatro
	 * cópias. A redundância é 4; a cobertura geográfica é 2. Uma métrica por nó chamaria isso de
	 * quatro vezes a proteção necessária.
	 *
	 * <p>Q0: não havia medida nenhuma — a rota não existia ({@code /admin/network/content/coverage}
	 * dava 404) e o painel só sabia contar nós por região, nunca regiões com cópia.</p>
	 */
	@Test
	void coberturaContaRegioesDistintasENaoNos() throws Exception {
		String admin = adminToken();
		activeNode(admin, "sudeste-1", "sudeste");
		activeNode(admin, "sudeste-2", "sudeste");
		activeNode(admin, "sudeste-3", "sudeste");
		Node sul = activeNode(admin, "sul-1", "sul");
		var session = auth.authenticate("geo_cov_1");
		String username = usernameOf(session);

		for (Node node : List.of(node("sudeste-1"), node("sudeste-2"), node("sudeste-3"), sul)) {
			syncProfile(node, username);
		}

		JsonNode profile = coverageOf(admin, "PROFILE");
		assertThat(profile.get("regionsWithCopy").asLong()).isEqualTo(2);
		assertThat(profile.get("totalRegions").asLong()).isEqualTo(2);
		assertThat(profile.get("copies").asLong()).isEqualTo(4);
	}

	/**
	 * Região evacuada sai do numerador <b>e</b> do denominador: o nó dela pode ter o cache completo,
	 * mas não atende ninguém enquanto a região estiver declarada fora. Contá-lo prometeria
	 * contingência que a rede não tem; deixá-lo no total faria "2 de 3" parecer região perdida.
	 */
	@Test
	void regiaoEvacuadaSaiDaCobertura() throws Exception {
		String admin = adminToken();
		Node sudeste = activeNode(admin, "borda-sudeste", "sudeste");
		Node sul = activeNode(admin, "borda-sul", "sul");
		var session = auth.authenticate("geo_cov_2");
		String username = usernameOf(session);

		syncProfile(sudeste, username);
		syncProfile(sul, username);

		assertThat(coverageOf(admin, "PROFILE").get("regionsWithCopy").asLong()).isEqualTo(2);
		assertThat(coverageOf(admin, "PROFILE").get("totalRegions").asLong()).isEqualTo(2);

		declare(admin, "sudeste", "queda regional");

		// Q0: sem a região evacuada no corte, a medida continuaria dizendo 2 de 2 em plena queda.
		assertThat(coverageOf(admin, "PROFILE").get("regionsWithCopy").asLong()).isEqualTo(1);
		assertThat(coverageOf(admin, "PROFILE").get("totalRegions").asLong()).isEqualTo(1);
		assertThat(coverageOf(admin, "PROFILE").get("copies").asLong()).isEqualTo(1);

		clear(admin, "sudeste");

		assertThat(coverageOf(admin, "PROFILE").get("regionsWithCopy").asLong()).isEqualTo(2);
	}

	/**
	 * Cópia de nó que parou de bater não cobre região: o cache dele existe no banco, mas ele não
	 * atende. O corte de heartbeat é o mesmo do scheduler — "vivo" aqui é o mesmo vivo que recebe
	 * tarefa, e não uma segunda definição que o painel inventou.
	 */
	@Test
	void noSemHeartbeatRecenteNaoCobreRegiao() throws Exception {
		String admin = adminToken();
		Node morto = activeNode(admin, "borda-morta", "sudeste");
		Node vivo = activeNode(admin, "borda-viva", "sul");
		var session = auth.authenticate("geo_cov_3");
		String username = usernameOf(session);

		syncProfile(morto, username);
		syncProfile(vivo, username);

		jdbc.update("update network_nodes set last_seen_at = ? where id = ?",
			java.sql.Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)), morto.id());

		assertThat(coverageOf(admin, "PROFILE").get("regionsWithCopy").asLong()).isEqualTo(1);
		assertThat(coverageOf(admin, "PROFILE").get("totalRegions").asLong()).isEqualTo(1);
		assertThat(coverageOf(admin, "PROFILE").get("copies").asLong()).isEqualTo(1);
	}

	/**
	 * Rede que não usa o campo de região não pode ser subnotificada: o nó sem região conta como uma
	 * região própria ({@code ""}), porque ele tem a cópia e atende. A conta é de painel, não de
	 * despacho — nada aqui muda quem recebe tarefa.
	 */
	@Test
	void noSemRegiaoContaComoRegiaoPropria() throws Exception {
		String admin = adminToken();
		Node semRegiao = activeNode(admin, "borda-sem-regiao", null);
		Node sul = activeNode(admin, "borda-sul", "sul");
		var session = auth.authenticate("geo_cov_4");
		String username = usernameOf(session);

		syncProfile(semRegiao, username);
		syncProfile(sul, username);

		assertThat(coverageOf(admin, "PROFILE").get("regionsWithCopy").asLong()).isEqualTo(2);
		assertThat(coverageOf(admin, "PROFILE").get("totalRegions").asLong()).isEqualTo(2);
	}

	/**
	 * Tipo sem cópia nenhuma aparece com zero, em vez de sumir da resposta. Omitir a linha esconderia
	 * exatamente o buraco que o painel existe para mostrar: "COMMUNITY não está em região nenhuma".
	 */
	@Test
	void tipoSemCopiaApareceComZero() throws Exception {
		String admin = adminToken();
		activeNode(admin, "borda-sul", "sul");

		JsonNode community = coverageOf(admin, "COMMUNITY");
		assertThat(community.get("regionsWithCopy").asLong()).isZero();
		assertThat(community.get("totalRegions").asLong()).isEqualTo(1);
		assertThat(community.get("copies").asLong()).isZero();

		// Todos os tipos conhecidos estão na resposta: a lista é a allowlist, não "o que achou".
		JsonNode all = coverage(admin);
		assertThat(all.size()).isEqualTo(2);
	}

	@Test
	void apenasAdminVeACobertura() throws Exception {
		var session = auth.authenticate("geo_cov_5");

		mockMvc.perform(get("/admin/network/content/coverage").header("X-Session-Token", session.token()))
			.andExpect(status().isForbidden());
	}

	/** O painel responde a cobertura junto do resto do conteúdo, e a rota não muda nenhuma contagem. */
	@Test
	void coberturaNaoAlteraAsOutrasContagens() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "borda-sul", "sul");
		var session = auth.authenticate("geo_cov_6");
		String username = usernameOf(session);

		syncProfile(node, username);

		coverage(admin);

		mockMvc.perform(get("/admin/network/content/overview").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.snapshots").value(1))
			.andExpect(jsonPath("$.divergent").value(0));
	}

	// ---------------------------------------------------------------- helpers

	private record Node(String id, String key) {
	}

	private final Map<String, Node> created = new HashMap<>();

	private String adminToken() throws Exception {
		var session = auth.authenticate("net_geo_admin");
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

	/** O nó criado por nome, depois do enrollment — a conta de cobertura testa quatro nós de uma vez. */
	private Node node(String name) {
		return created.get(name);
	}

	private Node activeNode(String admin, String name, String region) throws Exception {
		var tokenResult = mockMvc
			.perform(post("/admin/network/nodes/provision-token").header("X-Session-Token", admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("displayName", name,
					"ownerUserId", "owner-" + name, "profiles", List.of("NODE_EDGE")))))
			.andExpect(status().isOk())
			.andReturn();
		String provisionToken = objectMapper.readTree(tokenResult.getResponse().getContentAsString())
			.get("token")
			.asString();

		Map<String, Object> enrollBody = new HashMap<>();
		enrollBody.put("provisionToken", provisionToken);
		enrollBody.put("declared",
			Map.of("cpuCores", 4, "memoryMb", 8192, "diskMb", 51200, "bandwidthMbps", 100));
		if (region != null) {
			enrollBody.put("location", Map.of("region", region, "country", "BR"));
		}

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

		Node createdNode = new Node(nodeId, key);
		created.put(name.trim(), createdNode);

		mockMvc
			.perform(post("/network/nodes/heartbeat").header("X-Node-Id", createdNode.id())
				.header("X-Node-Key", createdNode.key()).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("runtimeVersion", "0.1.0", "observed",
					Map.of("cpuPercent", 5.0, "memoryMb", 1024, "runningTasks", 0)))))
			.andExpect(status().isOk());
		return createdNode;
	}

	private void syncProfile(Node node, String username) throws Exception {
		MvcResult served = mockMvc
			.perform(get("/network/content/PROFILE/" + username).header("X-Node-Id", node.id())
				.header("X-Node-Key", node.key()))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode body = objectMapper.readTree(served.getResponse().getContentAsString());
		mockMvc
			.perform(post("/network/content/PROFILE/" + username + "/synced").header("X-Node-Id", node.id())
				.header("X-Node-Key", node.key()).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("sourceVersion", body.get("version").asLong(),
					"contentHash", body.get("contentHash").asText()))))
			.andExpect(status().isOk());
	}

	private JsonNode divergences(String admin) throws Exception {
		MvcResult result = mockMvc
			.perform(get("/admin/network/content/divergences").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}

	private JsonNode coverage(String admin) throws Exception {
		MvcResult result = mockMvc
			.perform(get("/admin/network/content/coverage").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}

	private JsonNode coverageOf(String admin, String kind) throws Exception {
		for (JsonNode row : coverage(admin)) {
			if (kind.equals(row.get("kind").asText())) {
				return row;
			}
		}
		throw new AssertionError("cobertura sem a linha do tipo " + kind + ": " + coverage(admin));
	}

	private void declare(String admin, String region, String reason) throws Exception {
		mockMvc
			.perform(post("/admin/network/regions").header("X-Session-Token", admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("region", region, "reason", reason))))
			.andExpect(status().isOk());
	}

	private void clear(String admin, String region) throws Exception {
		mockMvc
			.perform(delete("/admin/network/regions/" + region.trim()).header("X-Session-Token", admin))
			.andExpect(status().isOk());
	}

	private int auditCount(Node node, String action, String targetId) {
		Integer count = jdbc.queryForObject(
			"select count(*) from audit_logs where action = ? and actor_id = ? and target_id = ?", Integer.class,
			action, node.id(), targetId);
		return count == null ? 0 : count;
	}

	private int checkedCount(Node node, String contentId) {
		Integer count = jdbc.queryForObject("""
			select count(*) from network_content_snapshots
			where node_id = ? and content_type = 'PROFILE' and content_id = ? and checked_at is not null
			""", Integer.class, node.id(), contentId);
		return count == null ? 0 : count;
	}
}