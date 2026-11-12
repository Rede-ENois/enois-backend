package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestDatabase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Rede cooperativa, fase 3 (NODE_READ_REPLICA): o catálogo que a réplica de leitura percorre.
 *
 * <p>A réplica é o único perfil que precisa <b>enumerar</b> — a borda só responde a id. O catálogo
 * devolve ids de projeções públicas, nunca tabela, e enumera por chave, não por posição. O que estes
 * testes fixam, cada um porque é um jeito de errar:</p>
 *
 * <ul>
 * <li>percorrer o catálogo em páginas pequenas devolve cada item <b>uma vez</b> e nenhum a mais —
 * nem buraco, nem repetição (a versão com {@code offset} repetiria e pularia quando alguém apaga
 * conta no meio da paginação);</li>
 * <li>o que a vitrine esconde não aparece aqui: conta suspensa, comunidade de conta suspensa e
 * comunidade com o texto do ataque ficam fora — a réplica é superfície pública;</li>
 * <li>o catálogo devolve o <b>@</b>, que é a chave que a rota de conteúdo aceita, e o corpo do item
 * carrega a versão da origem (nada de segunda serialização da mesma projeção);</li>
 * <li>{@code NODE_EDGE} autentica e recebe 403: a lista de todo mundo não é o que ele precisa
 * operar;</li>
 * <li>tipo fora da allowlist é recusado antes de qualquer consulta;</li>
 * <li>uma página de 500 custa um número <b>constante</b> de consultas — nem uma por perfil, nem uma
 * por comunidade (a contagem por item seria a N+1 que a réplica amplifica por todo o catálogo).</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class NetworkReplicaTests {

	@TestConfiguration
	static class InspectorConfig {

		@Bean
		CountingInspector countingInspector() {
			return new CountingInspector();
		}

		/** Liga o contador ao Hibernate da aplicação (bean do próprio Hibernate, Boot 4). */
		@Bean
		org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer statementInspector(
				CountingInspector countingInspector) {
			return properties -> properties.put(
				org.hibernate.cfg.AvailableSettings.STATEMENT_INSPECTOR, countingInspector);
		}
	}

	/** Conta as instruções SQL da thread do teste (o MockMvc roda na própria thread). */
	static class CountingInspector implements StatementInspector {

		private final AtomicReference<List<String>> captured =
			new AtomicReference<>(java.util.Collections.synchronizedList(new ArrayList<>()));

		@Override
		public String inspect(String sql) {
			captured.get().add(sql);
			return sql;
		}

		void reset() {
			captured.set(java.util.Collections.synchronizedList(new ArrayList<>()));
		}

		List<String> sql() {
			return List.copyOf(captured.get());
		}
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private CountingInspector inspector;

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

	// ---------------------------------------------------------------- enumeração por chave

	/**
	 * O catálogo inteiro, em páginas de 2, devolve cada conta <b>uma vez</b>.
	 *
	 * <p>É a diferença que justifica a chave: com posição, apagar uma conta entre duas páginas
	 * empurra as seguintes para trás e uma delas nunca é lida; criar uma empurra para frente e outra
	 * é lida duas vezes. Percorrer com a chave e comparar com o que foi criado prova que não há nem
	 * buraco nem repetição.</p>
	 *
	 * <p>A leitura começa do <b>fim</b> do catálogo, capturado antes de criar as contas: o banco é
	 * compartilhado entre os testes desta classe, e começar do início misturaria contas de outros
	 * casos — o que se prova aqui é a travessia, não a composição global do banco.</p>
	 */
	@Test
	void catalogoPercorreCadaPerfilUmaVezSemBuracoNemRepeticao() throws Exception {
		Node node = activeNode("replica");
		JsonNode anchor = drain(node, "PROFILE");
		String afterAt = anchor == null ? null : anchor.get("nextAfterAt").asText();
		String afterId = anchor == null ? null : anchor.get("nextAfterId").asText();

		List<String> expected = List.of(perfil("replica_p1"), perfil("replica_p2"), perfil("replica_p3"),
			perfil("replica_p4"), perfil("replica_p5"));

		Set<String> seen = new HashSet<>();
		List<String> collected = new ArrayList<>();
		JsonNode last = null;
		for (int page = 0; page < 10; page++) {
			last = replica(node, "PROFILE", afterAt, afterId, 2);
			for (JsonNode item : last.get("items")) {
				assertThat(seen.add(item.asText())).as("item repetido no catalogo: %s", item.asText()).isTrue();
				collected.add(item.asText());
			}
			if (!last.get("hasMore").asBoolean()) {
				break;
			}
			afterAt = last.get("nextAfterAt").asText();
			afterId = last.get("nextAfterId").asText();
		}

		// Exatamente as cinco, na ordem em que nasceram — nem a mais, nem a menos.
		assertThat(collected).containsExactlyElementsOf(expected);
		// O fim da travessia continua deixando posição guardada: "li até aqui" não é a mesma coisa
		// que "há mais" — é a diferença que evita a réplica rebaixar o catálogo inteiro na rodada
		// seguinte.
		assertThat(last.get("hasMore").asBoolean()).isFalse();
		assertThat(last.get("nextAfterAt").asText()).isNotBlank();
	}

	/**
	 * O catálogo devolve o @, e é com ele que a réplica busca o corpo.
	 *
	 * <p>Se devolvesse o id do usuário, a réplica não conseguiria trocar o item por conteúdo sem uma
	 * consulta extra — o catálogo existe para dizer "o que ler", e o @ é a chave da leitura.</p>
	 */
	@Test
	void oCatalogoDevolveOArrobaQueARotaDeConteudoAceita() throws Exception {
		Node node = activeNode("replica");
		JsonNode anchor = drain(node, "PROFILE");
		String afterAt = anchor == null ? null : anchor.get("nextAfterAt").asText();
		String afterId = anchor == null ? null : anchor.get("nextAfterId").asText();
		String username = perfil("replica_handle_1");

		JsonNode page = replica(node, "PROFILE", afterAt, afterId, 100);
		assertThat(page.get("kind").asText()).isEqualTo("PROFILE");
		assertThat(texts(page)).contains(username);

		// A chave devolvida abre o corpo versionado: mesma projeção, agora com versão e hash.
		JsonNode served = content(node, "PROFILE", username, null);
		assertThat(served.get("version").asLong()).isPositive();
		assertThat(objectMapper.readTree(served.get("body").asText()).get("username").asText()).isEqualTo(username);
	}

	/**
	 * A chave anda sobre o último item <b>lido</b>, não o último devolvido.
	 *
	 * <p>Uma página cheia de contas suspensas devolveria lista vazia; se o cursor ficasse parado, a
	 * réplica repetiria a mesma página para sempre e nunca chegaria ao fim do catálogo.</p>
	 */
	@Test
	void paginaSoDeContasSuspensasAindaAvancaOCursor() throws Exception {
		Node node = activeNode("replica");
		JsonNode anchor = drain(node, "PROFILE");
		String afterAt = anchor.get("nextAfterAt").asText();
		String afterId = anchor.get("nextAfterId").asText();

		var first = auth.authenticate("replica_banida_a");
		var second = auth.authenticate("replica_banida_b");
		ban(first.userId());
		ban(second.userId());
		String expected = perfil("replica_visivel_1");

		// A página de 2 é exatamente as duas contas suspensas: sai vazia, mas o cursor AVANÇA.
		JsonNode bannedPage = replica(node, "PROFILE", afterAt, afterId, 2);
		assertThat(texts(bannedPage)).isEmpty();
		assertThat(bannedPage.get("nextAfterAt").asText() + "|" + bannedPage.get("nextAfterId").asText())
			.isNotEqualTo(afterAt + "|" + afterId);

		// A página seguinte já começa depois delas e traz a conta visível.
		JsonNode visiblePage = replica(node, "PROFILE", bannedPage.get("nextAfterAt").asText(),
			bannedPage.get("nextAfterId").asText(), 100);
		assertThat(texts(visiblePage)).containsExactly(expected);
	}

	// ---------------------------------------------------------------- nunca mais largo que a vitrine

	/**
	 * Conta suspensa não entra no catálogo, e a página seguinte não perde o resto.
	 *
	 * <p>A réplica é superfície pública: o que o perfil público esconde atrás de 404 não pode sair
	 * daqui como id — seria o mesmo conteúdo com outra porta.</p>
	 */
	@Test
	void contaSuspensaNaoEntraNoCatalogo() throws Exception {
		Node node = activeNode("replica");
		JsonNode anchor = drain(node, "PROFILE");
		String afterAt = anchor == null ? null : anchor.get("nextAfterAt").asText();
		String afterId = anchor == null ? null : anchor.get("nextAfterId").asText();

		var banned = auth.authenticate("replica_ban_1");
		String visible = perfil("replica_ok_1");
		ban(banned.userId());

		List<String> ids = texts(replica(node, "PROFILE", afterAt, afterId, 100));
		assertThat(ids).contains(visible).doesNotContain(usernameOf(banned));
	}

	/**
	 * Comunidade de conta suspensa e comunidade com o texto do ataque ficam fora do catálogo — o mesmo
	 * critério da vitrine.
	 *
	 * <p>Não basta a comunidade existir: o que a lista pública filtra, a réplica filtra. Se a regra
	 * morasse em dois lugares, a réplica passaria a servir o que a vitrine esconde na primeira
	 * mudança de critério.</p>
	 */
	@Test
	void comunidadeForaDaVitrineNaoEntraNoCatalogo() throws Exception {
		Node node = activeNode("replica");
		JsonNode anchor = drain(node, "COMMUNITY");
		String afterAt = anchor == null ? null : anchor.get("nextAfterAt").asText();
		String afterId = anchor == null ? null : anchor.get("nextAfterId").asText();

		var owner = auth.authenticate("replica_com_owner");
		var bannedOwner = auth.authenticate("replica_com_banned");

		String publica = createCommunity(owner, "Rede Cooperativa");
		String deBanido = createCommunity(bannedOwner, "Comunidade Fantasma");
		String suspeita = createCommunity(owner, "Red Team msm");
		ban(bannedOwner.userId());

		List<String> ids = texts(replica(node, "COMMUNITY", afterAt, afterId, 100));
		assertThat(ids).contains(publica).doesNotContain(deBanido).doesNotContain(suspeita);
	}

	/**
	 * A comunidade que existe no catálogo abre pelo mesmo caminho de conteúdo, com versão — o
	 * {@code sourceVersion} que a réplica guarda vem da origem, não de um contador paralelo.
	 */
	@Test
	void comunidadeDoCatalogoAbreComVersaoDaOrigem() throws Exception {
		Node node = activeNode("replica");
		String communityId = createCommunity(auth.authenticate("replica_com_ok"), "Rede Aberta");

		assertThat(texts(replica(node, "COMMUNITY", null, null, 100))).contains(communityId);

		JsonNode served = content(node, "COMMUNITY", communityId, null);
		assertThat(served.get("version").asLong()).isPositive();
		assertThat(served.get("contentHash").asText()).isNotBlank();
	}

	// ---------------------------------------------------------------- autorização

	/**
	 * A borda autentica mas não enumera. Capacidade não dá permissão — e há permissão que a borda não
	 * precisa: ela descobre conteúdo pelo feed e pelo id, não precisa da lista de todo mundo.
	 */
	@Test
	void bordaNaoEnumeraOCatalogo() throws Exception {
		Node node = activeNode("borda", List.of("NODE_EDGE"));
		perfil("replica_forbidden_1");

		mockMvc
			.perform(get("/network/replica/PROFILE").header("X-Node-Id", node.id())
				.header("X-Node-Key", node.key()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NODE_PROFILE_REQUIRED"));
	}

	@Test
	void noLocalTambemEnumera() throws Exception {
		Node node = activeNode("local", List.of("NODE_LOCAL"));
		JsonNode anchor = drain(node, "PROFILE");
		String afterAt = anchor == null ? null : anchor.get("nextAfterAt").asText();
		String afterId = anchor == null ? null : anchor.get("nextAfterId").asText();
		String username = perfil("replica_local_1");

		assertThat(texts(replica(node, "PROFILE", afterAt, afterId, 100))).contains(username);
	}

	@Test
	void chaveInvalidaNaoAbreOCatalogo() throws Exception {
		Node node = activeNode("replica");

		mockMvc
			.perform(get("/network/replica/PROFILE").header("X-Node-Id", node.id())
				.header("X-Node-Key", "enk_chave-errada"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("NODE_KEY_INVALID"));
	}

	/**
	 * Tipo fora da allowlist é 403 sem tocar no banco de conteúdo: a réplica não descobre o catálogo
	 * tentando "o que der".
	 */
	@Test
	void tipoForaDaAllowlistNaoEnumera() throws Exception {
		Node node = activeNode("replica");

		mockMvc
			.perform(get("/network/replica/POST").header("X-Node-Id", node.id()).header("X-Node-Key", node.key()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("CONTENT_KIND_UNKNOWN"));

		assertThat(jdbc.queryForObject("select count(*) from network_content_versions", Integer.class)).isZero();
	}

	/**
	 * Cursor pela metade é cursor corrompido, não "começa do início": só a chave não define posição e
	 * só o id não ordena. Recomeçar em silêncio duplicaria o catálogo na réplica.
	 */
	@Test
	void cursorIncompletoERecusado() throws Exception {
		Node node = activeNode("replica");

		mockMvc
			.perform(get("/network/replica/PROFILE").param("afterAt", "2026-01-01T00:00:00Z")
				.header("X-Node-Id", node.id()).header("X-Node-Key", node.key()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("REPLICA_CURSOR_INVALID"));

		mockMvc
			.perform(get("/network/replica/PROFILE").param("afterAt", "nao-e-data").param("afterId", "x")
				.header("X-Node-Id", node.id()).header("X-Node-Key", node.key()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("REPLICA_CURSOR_INVALID"));
	}

	/**
	 * Apagar um item já lido não faz a travessia repetir o seguinte.
	 *
	 * <p>É a razão de a enumeração ser por <b>chave</b> e não por posição. Com posição, o cursor é
	 * "pulei N itens"; apagar um item entre duas páginas encurta a lista, o mesmo N passa a apontar
	 * para o item seguinte e ele é lido <b>duas vezes</b> — numa réplica de leitura isso é conteúdo
	 * velho servido de novo sem motivo, e o item que ficou no fim nunca é alcançado. Com chave, a
	 * posição de quem já foi lido não depende de quantos itens existem antes.</p>
	 */
	@Test
	void apagarUmItemJaLidoNaoRepeteOProximo() throws Exception {
		Node node = activeNode("replica");
		JsonNode anchor = drain(node, "COMMUNITY");
		String startAt = anchor == null ? null : anchor.get("nextAfterAt").asText();
		String startId = anchor == null ? null : anchor.get("nextAfterId").asText();

		var owner = auth.authenticate("replica_del_owner");
		String first = createCommunity(owner, "Entrega Um");
		String second = createCommunity(owner, "Entrega Dois");
		String third = createCommunity(owner, "Entrega Tres");

		JsonNode pageOne = replica(node, "COMMUNITY", startAt, startId, 2);
		assertThat(texts(pageOne)).containsExactly(first, second);

		// O primeiro item já lido é apagado antes da página seguinte.
		TestDatabase.deleteCommunity(first);

		JsonNode pageTwo = replica(node, "COMMUNITY", pageOne.get("nextAfterAt").asText(),
			pageOne.get("nextAfterId").asText(), 2);
		// O seguinte NÃO pode reaparecer, e o terceiro não pode ser pulado.
		assertThat(texts(pageTwo)).containsExactly(third);
	}

	// ---------------------------------------------------------------- custo constante

	/**
	 * Uma página de várias comunidades custa um número <b>constante</b> de consultas.
	 *
	 * <p>A réplica percorre o catálogo inteiro de um sistema vivo: uma consulta por item aqui vira
	 * uma varredura de milhares de consultas por rodada. O que se prova é a contagem com 1 e com 4
	 * comunidades <b>na mesma página</b> — o número não pode crescer com o tamanho da página.</p>
	 */
	@Test
	void paginaDeComunidadesCustaOMesmoNumeroDeConsultasComUmaOuComQuatro() throws Exception {
		Node node = activeNode("replica");
		var owner = auth.authenticate("replica_cost_owner");
		createCommunity(owner, "Custo Um");

		inspector.reset();
		replica(node, "COMMUNITY", null, null, 100);
		int withOne = inspector.sql().size();

		for (int i = 2; i <= 4; i++) {
			createCommunity(owner, "Custo " + i);
		}

		inspector.reset();
		JsonNode page = replica(node, "COMMUNITY", null, null, 100);
		int withFour = inspector.sql().size();

		assertThat(page.get("items").size()).isGreaterThanOrEqualTo(4);
		assertThat(withFour).as("consultas nao podem crescer com o tamanho da pagina").isEqualTo(withOne);
		// Igualdade sozinha nao prova nada se as duas contas explodiram: o teto absoluto garante que
		// a pagina e servida por um punhado de consultas (chave + comunidades + sancoes do lote).
		assertThat(withFour).as("pagina de comunidades deve ser barata").isLessThanOrEqualTo(12);
	}

	/**
	 * O mesmo para perfil — a suspensão da página inteira sai em UMA consulta, não uma por conta.
	 */
	@Test
	void paginaDePerfisCustaOMesmoNumeroDeConsultasComUmaOuComQuatro() throws Exception {
		Node node = activeNode("replica");
		perfil("replica_pcost_1");

		inspector.reset();
		replica(node, "PROFILE", null, null, 100);
		int withOne = inspector.sql().size();

		perfil("replica_pcost_2");
		perfil("replica_pcost_3");
		perfil("replica_pcost_4");

		inspector.reset();
		JsonNode page = replica(node, "PROFILE", null, null, 100);
		int withFour = inspector.sql().size();

		assertThat(page.get("items").size()).isGreaterThanOrEqualTo(4);
		assertThat(withFour).as("consultas nao podem crescer com o tamanho da pagina").isEqualTo(withOne);
		// Igualdade sozinha nao prova nada se as duas contas explodiram: o teto absoluto garante que
		// a pagina e servida por um punhado de consultas (chave + perfis + sancoes do lote).
		assertThat(withFour).as("pagina de perfis deve ser barata").isLessThanOrEqualTo(12);
	}

	// ---------------------------------------------------------------- helpers

	private record Node(String id, String key) {
	}

	/** Uma conta nova, com o @ público. */
	private String perfil(String name) throws Exception {
		return usernameOf(auth.authenticate(name));
	}

	private String usernameOf(TestAuthHelper.AuthSession session) throws Exception {
		MvcResult me = mockMvc.perform(get("/me").header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(me.getResponse().getContentAsString()).get("username").asText();
	}

	private void ban(String userId) {
		jdbc.update("""
			insert into user_sanctions (id, user_id, type, reason, status, created_at)
			values (?, ?, 'BAN', 'teste de replica', 'ACTIVE', now())
			""", "san_" + UUID.randomUUID().toString().replace("-", ""), userId);
	}

	private Node activeNode(String suffix) throws Exception {
		return activeNode(suffix, List.of("NODE_READ_REPLICA"));
	}

	private Node activeNode(String suffix, List<String> profiles) throws Exception {
		var session = auth.authenticate("replica_admin_" + suffix);
		TestDatabase.setAdmin(session.userId());
		String admin = session.token();

		var tokenResult = mockMvc
			.perform(post("/admin/network/nodes/provision-token").header("X-Session-Token", admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("displayName", "replica-" + suffix,
					"ownerUserId", "owner-replica-" + suffix, "profiles", profiles))))
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

	private JsonNode replica(Node node, String kind, String afterAt, String afterId, int limit) throws Exception {
		var request = get("/network/replica/" + kind).header("X-Node-Id", node.id()).header("X-Node-Key", node.key())
			.param("limit", String.valueOf(limit));
		if (afterAt != null) {
			request = request.param("afterAt", afterAt).param("afterId", afterId);
		}
		MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}

	/**
	 * Percorre o catálogo até o fim e devolve a página vazia final (com o cursor do fim), ou {@code
	 * null} se o catálogo já estava vazio.
	 *
	 * <p>É a âncora dos testes: o banco é compartilhado entre eles, então "as contas que eu criei"
	 * só é um conjunto fechado se a leitura começar depois de tudo o que já existia.</p>
	 */
	private JsonNode drain(Node node, String kind) throws Exception {
		String afterAt = null;
		String afterId = null;
		JsonNode last = null;
		for (int page = 0; page < 200; page++) {
			JsonNode body = replica(node, kind, afterAt, afterId, 100);
			last = body;
			if (!body.get("hasMore").asBoolean()) {
				return last;
			}
			afterAt = body.get("nextAfterAt").asText();
			afterId = body.get("nextAfterId").asText();
		}
		return last;
	}

	private static List<String> texts(JsonNode page) {		List<String> items = new ArrayList<>();
		for (JsonNode item : page.get("items")) {
			items.add(item.asText());
		}
		return items;
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

	private String createCommunity(TestAuthHelper.AuthSession owner, String name) throws Exception {
		MvcResult result = mockMvc
			.perform(post("/communities").header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "replica-com-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", name))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}
}