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

import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestDatabase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Rede cooperativa, fase 3 (NODE_EDGE): o nó de borda entrega conteúdo público versionado e
 * nunca decide autorização de usuário.
 *
 * <p>O que estes testes fixam, porque cada um é um jeito de errar:</p>
 * <ul>
 * <li>o nó pede o conteúdo com a versão que já tem e recebe {@code changed=false} — sem corpo,
 * sem download;</li>
 * <li>a versão só sobe quando o <b>corpo</b> muda; recalcular o mesmo conteúdo não é mudança;</li>
 * <li>a projeção pública da comunidade <b>não</b> tem {@code joined}: o que depende de quem lê
 * continua sendo respondido pelo Control Plane, com a sessão da pessoa;</li>
 * <li>nó sem o perfil {@code NODE_EDGE} não serve conteúdo, mesmo com a chave certa — capacidade
 * não dá permissão;</li>
 * <li>chave inválida ou revogada é 401; tipo de conteúdo desconhecido é 404, sem consulta;</li>
 * <li>o nó confirma o que guardou e o atraso ({@code lag}) cai a zero;</li>
 * <li>a reconciliação aponta conteúdo desatualizado ({@code CONTENT_STALE}) e conteúdo que sumiu
 * ({@code CONTENT_GONE}), e cada divergência vira auditoria {@code SYNC_DIVERGENCE}.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class NetworkContentTests {

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
		jdbc.update("delete from network_content_snapshots");
		jdbc.update("delete from network_content_versions");
		jdbc.update("delete from network_node_keys");
		jdbc.update("delete from network_provision_tokens");
		jdbc.update("delete from network_nodes");
	}

	// ---------------------------------------------------------------- versão

	/**
	 * O pull da borda: o nó informa a versão que já tem e recebe "não mudou" sem corpo. É o que
	 * evita baixar de novo, a cada poll, tudo o que a rede já tem.
	 */
	@Test
	void noComAVersaoAtualNaoRecebeCorpoDeNovo() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "borda-a", List.of("NODE_EDGE"));
		String username = usernameOf(auth.authenticate("edge_pull_1"));

		JsonNode first = content(node, "PROFILE", username, null);
		assertThat(first.get("changed").asBoolean()).isTrue();
		assertThat(first.get("version").asLong()).isEqualTo(1);
		assertThat(first.get("body").asText()).isNotBlank();

		// O nó pede de novo com a versão que já tem: nada mudou, sem corpo.
		JsonNode second = content(node, "PROFILE", username, 1L);
		assertThat(second.get("changed").asBoolean()).isFalse();
		assertThat(second.get("version").asLong()).isEqualTo(1);
		assertThat(second.get("body").isNull()).isTrue();

		// O hash servido é o do corpo servido: é o que o nó confirma depois.
		assertThat(second.get("contentHash").asText()).isEqualTo(first.get("contentHash").asText());
	}

	/**
	 * Versão nova significa corpo novo. Ler de novo sem mudar nada não sobe a versão (senão todo nó
	 * baixaria tudo de novo a cada poll), e editar o perfil sobe (senão o nó serviria conteudo velho
	 * para sempre).
	 */
	@Test
	void versaoSobeQuandoOCorpoMuda() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "borda-a", List.of("NODE_EDGE"));
		var session = auth.authenticate("edge_version_1");
		String username = usernameOf(session);

		JsonNode first = content(node, "PROFILE", username, null);
		long versionBefore = first.get("version").asLong();
		String hashBefore = first.get("contentHash").asText();

		// Ler de novo sem mexer no perfil: mesma versão, mesmo hash.
		JsonNode reread = content(node, "PROFILE", username, null);
		assertThat(reread.get("version").asLong()).isEqualTo(versionBefore);
		assertThat(reread.get("contentHash").asText()).isEqualTo(hashBefore);

		// O dono edita o perfil: o corpo muda, a versão sobe e o hash é outro.
		mockMvc
			.perform(patch("/me/profile").header("X-Session-Token", session.token())
				.header("Idempotency-Key", "edge-version-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("headline", "Dev de infra e rede"))))
			.andExpect(status().isOk());

		JsonNode after = content(node, "PROFILE", username, null);
		assertThat(after.get("version").asLong()).isGreaterThan(versionBefore);
		assertThat(after.get("contentHash").asText()).isNotEqualTo(hashBefore);
		assertThat(after.get("body").asText()).contains("Dev de infra e rede");
	}

	// ---------------------------------------------------------------- autorização

	/**
	 * A borda entrega o que é igual para todo mundo. O {@code joined} da comunidade depende de quem
	 * está lendo — por isso ele NÃO pode vir na projeção pública do nó. Quem pergunta "eu participo?"
	 * continua perguntando ao Control Plane, com a sessão da pessoa.
	 */
	@Test
	void projecaoDeComunidadeNaoCarregaOMembroDependenteDoLeitor() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "borda-a", List.of("NODE_EDGE"));
		String communityId = createCommunity(auth.authenticate("edge_com_1"), "Rede Coop");

		JsonNode body = objectMapper.readTree(content(node, "COMMUNITY", communityId, null).get("body").asText());
		assertThat(body.has("joined")).isFalse();
		assertThat(body.get("name").asText()).isEqualTo("Rede Coop");
		assertThat(body.get("members").asInt()).isEqualTo(1);
	}

	/**
	 * Capacidade não dá permissão: um nó com a chave certa mas sem o perfil de borda não serve
	 * conteúdo público.
	 */
	@Test
	void noSemPerfilDeBordaNaoServeConteudo() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "midia-a", List.of("NODE_MEDIA"));
		String username = usernameOf(auth.authenticate("edge_perm_1"));

		mockMvc
			.perform(get("/network/content/PROFILE/" + username).header("X-Node-Id", node.id())
				.header("X-Node-Key", node.key()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NODE_PROFILE_REQUIRED"));
	}

	@Test
	void chaveInvalidaNaoAbreConteudo() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "borda-a", List.of("NODE_EDGE"));
		String username = usernameOf(auth.authenticate("edge_key_1"));

		mockMvc
			.perform(get("/network/content/PROFILE/" + username).header("X-Node-Id", node.id())
				.header("X-Node-Key", "enk_chave-errada"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("NODE_KEY_INVALID"));

		mockMvc.perform(get("/network/content/PROFILE/" + username)).andExpect(status().isUnauthorized());
	}

	@Test
	void tipoDeConteudoDesconhecidoE404SemConsultar() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "borda-a", List.of("NODE_EDGE"));

		mockMvc
			.perform(get("/network/content/POST/" + java.util.UUID.randomUUID())
				.header("X-Node-Id", node.id()).header("X-Node-Key", node.key()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("CONTENT_KIND_UNKNOWN"));

		// Nada foi versionado: tipo fora da allowlist não cria linha de versão.
		assertThat(jdbc.queryForObject("select count(*) from network_content_versions", Integer.class)).isZero();
	}

	// ---------------------------------------------------------------- sincronização e atraso

	@Test
	void noConfirmaOSincronizadoEOAtrasoZera() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "borda-a", List.of("NODE_EDGE"));
		String username = usernameOf(auth.authenticate("edge_sync_1"));

		JsonNode served = content(node, "PROFILE", username, null);
		long version = served.get("version").asLong();
		String hash = served.get("contentHash").asText();

		long behindBefore = behindOf(node, "PROFILE");
		assertThat(behindBefore).isPositive();

		mockMvc
			.perform(post("/network/content/PROFILE/" + username + "/synced").header("X-Node-Id", node.id())
				.header("X-Node-Key", node.key()).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("sourceVersion", version, "contentHash", hash))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("SYNCED"));

		assertThat(behindOf(node, "PROFILE")).isEqualTo(behindBefore - 1);
		assertThat(snapshotOf(node, "PROFILE", username).sourceVersion()).isEqualTo(version);
	}

	// ---------------------------------------------------------------- reconciliação

	/**
	 * Divergência: o nó tem um corpo que a origem já não serve. É o alerta que o painel lê — e cada
	 * divergência vira auditoria {@code SYNC_DIVERGENCE}.
	 */
	@Test
	void reconciliacaoApontaConteudoDesatualizadoEAudita() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "borda-a", List.of("NODE_EDGE"));
		var session = auth.authenticate("edge_reconcile_1");
		String username = usernameOf(session);

		JsonNode served = content(node, "PROFILE", username, null);
		mockMvc
			.perform(post("/network/content/PROFILE/" + username + "/synced").header("X-Node-Id", node.id())
				.header("X-Node-Key", node.key()).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("sourceVersion", served.get("version").asLong(),
					"contentHash", served.get("contentHash").asText()))))
			.andExpect(status().isOk());

		// O dono edita o perfil depois que o nó sincronizou: o nó está com o corpo velho.
		mockMvc
			.perform(patch("/me/profile").header("X-Session-Token", session.token())
				.header("Idempotency-Key", "edge-reconcile-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("headline", "Depois da sincronia"))))
			.andExpect(status().isOk());

		JsonNode divergences = reconcile(node, List.of(Map.of("kind", "PROFILE", "contentId", username)));
		assertThat(divergences.size()).isEqualTo(1);
		assertThat(divergences.get(0).get("reason").asText()).isEqualTo("CONTENT_STALE");
		assertThat(divergences.get(0).get("contentId").asText()).isEqualTo(username);
		assertThat(divergences.get(0).get("nodeVersion").asLong()).isEqualTo(1);
		assertThat(divergences.get(0).get("sourceVersion").asLong()).isGreaterThan(1);

		assertThat(auditCount(node, username)).isEqualTo(1);
	}

	/**
	 * O agregado que sumiu da origem também é divergência: o nó está servindo conteúdo que não
	 * existe mais (comunidade excluída).
	 */
	@Test
	void reconciliacaoApontaConteudoQueSumiu() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "borda-a", List.of("NODE_EDGE"));
		String communityId = createCommunity(auth.authenticate("edge_reconcile_2"), "Comunidade Efemera");

		JsonNode served = content(node, "COMMUNITY", communityId, null);
		mockMvc
			.perform(post("/network/content/COMMUNITY/" + communityId + "/synced").header("X-Node-Id", node.id())
				.header("X-Node-Key", node.key()).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("sourceVersion", served.get("version").asLong(),
					"contentHash", served.get("contentHash").asText()))))
			.andExpect(status().isOk());

		TestDatabase.deleteCommunity(communityId);

		JsonNode divergences = reconcile(node, List.of(Map.of("kind", "COMMUNITY", "contentId", communityId)));
		assertThat(divergences.size()).isEqualTo(1);
		assertThat(divergences.get(0).get("reason").asText()).isEqualTo("CONTENT_GONE");
		assertThat(auditCount(node, communityId)).isEqualTo(1);
	}

	// ---------------------------------------------------------------- helpers

	private record Node(String id, String key) {
	}

	private String adminToken() throws Exception {
		var session = auth.authenticate("net_content_admin");
		TestDatabase.setAdmin(session.userId());
		return session.token();
	}

	/** O @ público da conta: é a chave do conteúdo de perfil. */
	private String usernameOf(TestAuthHelper.AuthSession session) throws Exception {
		MvcResult me = mockMvc.perform(get("/me").header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(me.getResponse().getContentAsString()).get("username").asText();
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

	private JsonNode content(Node node, String kind, String contentId, Long version) throws Exception {
		var request = get("/network/content/" + kind + "/" + contentId).header("X-Node-Id", node.id())
			.header("X-Node-Key", node.key());
		if (version != null) {
			request = request.param("version", String.valueOf(version));
		}
		MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString());
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

	private String createCommunity(TestAuthHelper.AuthSession owner, String name) throws Exception {
		MvcResult result = mockMvc
			.perform(post("/communities").header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "edge-com-" + name.toLowerCase(java.util.Locale.ROOT))
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", name))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	/** O atraso do nó, pela mesma conta que a rota devolve (a tabela é compartilhada entre testes). */
	private long behindOf(Node node, String kind) {
		Long count = jdbc.queryForObject("""
			select count(*) from network_content_versions v
			left join network_content_snapshots s
				on s.content_type = v.content_type and s.content_id = v.content_id and s.node_id = ?
			where v.content_type = ? and (s.source_version is null or s.source_version < v.version)
			""", Long.class, node.id(), kind);
		return count == null ? 0 : count;
	}

	private Snapshot snapshotOf(Node node, String kind, String contentId) {
		return jdbc.queryForObject("""
			select source_version, content_hash from network_content_snapshots
			where node_id = ? and content_type = ? and content_id = ?
			""", (rs, row) -> new Snapshot(rs.getLong("source_version"), rs.getString("content_hash")), node.id(),
			kind, contentId);
	}

	private record Snapshot(long sourceVersion, String contentHash) {
	}

	private int auditCount(Node node, String targetId) {
		Integer count = jdbc.queryForObject(
			"select count(*) from audit_logs where action = 'SYNC_DIVERGENCE' and actor_id = ? and target_id = ?",
			Integer.class, node.id(), targetId);
		return count == null ? 0 : count;
	}
}
