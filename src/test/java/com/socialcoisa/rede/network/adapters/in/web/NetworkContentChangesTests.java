package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.network.application.ContentChangeRetentionWorker;
import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestDatabase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Rede cooperativa, fase 3 (cache distribuído): o feed de mudanças do conteúdo público.
 *
 * <p>O problema que estes testes fixam: sem o feed, um nó de borda só descobre que um agregado mudou
 * perguntando por ele, um por um. Quem tem mil entradas em cache não tem como saber quais revalidar,
 * e o que ninguém pergunta nunca converge. O feed é o delta — diz o que mudou, não o conteúdo.</p>
 *
 * <ul>
 * <li>versão nova grava linha no feed <b>na mesma transação</b>; ler sem mudar não grava nada;</li>
 * <li>o cursor avança e a página seguinte não repete o que já veio;</li>
 * <li>cursor atrás da janela retida recebe {@code reset=true} — nunca "nada mudou", que deixaria o
 * nó servindo conteúdo velho para sempre;</li>
 * <li>a rota é do nó de borda: sem perfil é 403, sem chave é 401;</li>
 * <li>o feed não carrega corpo — só ids, versão e hash.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class NetworkContentChangesTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private ContentChangeRetentionWorker retention;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
		jdbc.update("delete from network_content_changes");
		jdbc.update("delete from network_content_snapshots");
		jdbc.update("delete from network_content_versions");
		jdbc.update("delete from network_node_keys");
		jdbc.update("delete from network_provision_tokens");
		jdbc.update("delete from network_nodes");
	}

	// ---------------------------------------------------------------- o feed

	/**
	 * A prova de regressão da rodada. Antes, cunhar a versão não deixava rastro nenhum que um nó
	 * pudesse ler em lote: o único caminho era pedir cada agregado com {@code ?version=}. Agora a
	 * cunhagem grava a linha do feed, e o nó que nunca perguntou nada descobre o que mudou.
	 */
	@Test
	void mudancaDeConteudoEntraNoFeedENoDescobreSemPerguntarPorAgregado() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "borda-a", List.of("NODE_EDGE"));
		var session = auth.authenticate("changes_feed_1");
		String username = usernameOf(session);
		assertThat(feedCount()).isZero();

		// O nó lê o conteúdo uma vez (é o que um nó de cache faz ao popular a entrada).
		content(node, "PROFILE", username);

		JsonNode page = feed(node, 0, 50);
		assertThat(page.get("reset").asBoolean()).isFalse();
		assertThat(page.get("changes").size()).isEqualTo(1);
		assertThat(page.get("changes").get(0).get("kind").asText()).isEqualTo("PROFILE");
		assertThat(page.get("changes").get(0).get("contentId").asText()).isEqualTo(username);
		assertThat(page.get("changes").get(0).get("version").asLong()).isEqualTo(1);
		assertThat(page.get("nextSeq").asLong()).isEqualTo(page.get("changes").get(0).get("seq").asLong());

		// Nada mudou: o nó pede de novo do cursor e não recebe nada.
		JsonNode idle = feed(node, page.get("nextSeq").asLong(), 50);
		assertThat(idle.get("changes").size()).isZero();
		assertThat(idle.get("nextSeq").asLong()).isEqualTo(page.get("nextSeq").asLong());

		// O dono edita o perfil: o nó descobre sem revalidar o que já tinha.
		mockMvc
			.perform(patch("/me/profile").header("X-Session-Token", session.token())
				.header("Idempotency-Key", "changes-feed-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("headline", "Mudou agora"))))
			.andExpect(status().isOk());
		content(node, "PROFILE", username);
		content(node, "PROFILE", username);

		JsonNode after = feed(node, page.get("nextSeq").asLong(), 50);
		assertThat(after.get("changes").size()).isEqualTo(1);
		assertThat(after.get("changes").get(0).get("version").asLong()).isEqualTo(2);
		assertThat(after.get("changes").get(0).get("contentHash").asText())
			.isNotEqualTo(page.get("changes").get(0).get("contentHash").asText());
	}

	/**
	 * Recalcular o mesmo corpo não é mudança, e não pode virar linha no feed: se cada leitura
	 * gravasse uma posição, todo nó rebaixaria tudo a cada poll — o feed viraria um acordar geral
	 * permanente em cima de conteúdo idêntico.
	 */
	@Test
	void leituraSemMudancaNaoPoluiOFeed() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "borda-a", List.of("NODE_EDGE"));
		String username = usernameOf(auth.authenticate("changes_quiet_1"));

		content(node, "PROFILE", username);
		for (int i = 0; i < 5; i++) {
			content(node, "PROFILE", username);
		}
		assertThat(feedCount()).isEqualTo(1);
	}

	@Test
	void feedPaginaEInformaQueHaMais() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "borda-a", List.of("NODE_EDGE"));
		var session = auth.authenticate("changes_page_1");
		String username = usernameOf(session);
		String other = usernameOf(auth.authenticate("changes_page_2"));
		content(node, "PROFILE", username);
		content(node, "PROFILE", other);

		JsonNode first = feed(node, 0, 1);
		assertThat(first.get("changes").size()).isEqualTo(1);
		assertThat(first.get("hasMore").asBoolean()).isTrue();

		JsonNode second = feed(node, first.get("nextSeq").asLong(), 1);
		assertThat(second.get("changes").size()).isEqualTo(1);
		assertThat(second.get("hasMore").asBoolean()).isFalse();
		// As duas páginas não repetem a mesma posição.
		assertThat(second.get("changes").get(0).get("seq").asLong())
			.isGreaterThan(first.get("changes").get(0).get("seq").asLong());
	}

	/**
	 * O feed não é o conteúdo. O nó revalida o que tem pelo endpoint autorizado, que é o único que
	 * entrega corpo — e este é o contrato que impede alguém de transformar o feed num atalho de
	 * leitura (e num caminho de cache sem controle de perfil).
	 */
	@Test
	void feedNaoCarregaCorpo() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "borda-a", List.of("NODE_EDGE"));
		String username = usernameOf(auth.authenticate("changes_nobody_1"));
		content(node, "PROFILE", username);

		mockMvc
			.perform(get("/network/content/changes").header("X-Node-Id", node.id())
				.header("X-Node-Key", node.key()).param("cursor", "0"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.changes[0].body").doesNotExist())
			.andExpect(jsonPath("$.changes[0].seq").exists())
			.andExpect(jsonPath("$.changes[0].version").exists())
			.andExpect(jsonPath("$.changes[0].contentHash").exists());
	}

	// ---------------------------------------------------------------- janela e reset

	/**
	 * O caso que precisa estar certo: o nó ficou fora além da janela e perdeu mudanças que já não
	 * existem para reler. Responder "nada mudou" o deixaria servindo conteúdo velho para sempre
	 * achando que está em dia; a resposta é {@code reset=true}.
	 */
	@Test
	void cursorAtrasDaJanelaExigeRecargaCompleta() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "borda-a", List.of("NODE_EDGE"));
		String first = usernameOf(auth.authenticate("changes_reset_1"));
		String second = usernameOf(auth.authenticate("changes_reset_2"));
		String third = usernameOf(auth.authenticate("changes_reset_3"));
		content(node, "PROFILE", first);
		content(node, "PROFILE", second);
		content(node, "PROFILE", third);

		// O nó parou na posição da primeira leitura; as duas seguintes venceram a retenção e saíram.
		long cursor = jdbc.queryForObject("select min(seq) from network_content_changes", Long.class);
		ageOldest(100);
		assertThat(retention.runOnce()).isEqualTo(1);
		ageOldest(100);
		assertThat(retention.runOnce()).isEqualTo(1);

		JsonNode reset = feed(node, cursor, 50);
		assertThat(reset.get("reset").asBoolean()).isTrue();
		assertThat(reset.get("changes").size()).isZero();
		// O cursor é devolvido como veio: quem decide o próximo passo é o nó, com a recarga completa.
		assertThat(reset.get("cursor").asLong()).isEqualTo(cursor);
	}

	/**
	 * Cursor 0 é "nunca sincronizei", não "perdi minha posição". Num feed já podado, o nó novo
	 * recebe a página normal — ele não tem nada em cache para ficar velho, e mandá-lo "recarregar
	 * do zero" seria pedir que ele fizesse exatamente o que ele já vai fazer.
	 */
	@Test
	void cursorZeroNumFeedPodadoNaoEhReset() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "borda-a", List.of("NODE_EDGE"));
		String first = usernameOf(auth.authenticate("changes_fresh_1"));
		String second = usernameOf(auth.authenticate("changes_fresh_2"));
		content(node, "PROFILE", first);
		content(node, "PROFILE", second);
		ageOldest(100);
		retention.runOnce();

		JsonNode page = feed(node, 0, 50);
		assertThat(page.get("reset").asBoolean()).isFalse();
		assertThat(page.get("changes").size()).isEqualTo(1);
	}

	/**
	 * A fronteira do reset não pode ser frouxa: com o cursor exatamente uma posição antes do menor
	 * retido, a próxima linha a ler é justamente a menor — não há buraco, e pedir recarga completa
	 * ali seria trabalho à toa.
	 */
	@Test
	void cursorImediatamenteAntesDoMenorRetidoNaoReseta() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "borda-a", List.of("NODE_EDGE"));
		String first = usernameOf(auth.authenticate("changes_edge_1"));
		String second = usernameOf(auth.authenticate("changes_edge_2"));
		content(node, "PROFILE", first);
		content(node, "PROFILE", second);

		ageOldest(100);
		retention.runOnce();
		long oldest = jdbc.queryForObject("select min(seq) from network_content_changes", Long.class);

		JsonNode page = feed(node, oldest - 1, 50);
		assertThat(page.get("reset").asBoolean()).isFalse();
		assertThat(page.get("changes").size()).isEqualTo(1);
		assertThat(page.get("changes").get(0).get("seq").asLong()).isEqualTo(oldest);
	}

	/**
	 * A poda só tira o que venceu: linha recente é o que um nó atrasado ainda tem para ler. Apagar
	 * por lote não pode virar "apagar tudo".
	 */
	@Test
	void retencaoPodaSoOVencido() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "borda-a", List.of("NODE_EDGE"));
		String oldOne = usernameOf(auth.authenticate("changes_ret_1"));
		String recent = usernameOf(auth.authenticate("changes_ret_2"));
		content(node, "PROFILE", oldOne);
		long oldSeq = jdbc.queryForObject("select max(seq) from network_content_changes", Long.class);
		content(node, "PROFILE", recent);

		jdbc.update("update network_content_changes set changed_at = ? where seq = ?",
			java.sql.Timestamp.from(Instant.now().minusSeconds(100 * 3600)), oldSeq);

		assertThat(retention.runOnce()).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from network_content_changes", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select min(seq) from network_content_changes", Long.class))
			.isGreaterThan(oldSeq);
	}

	// ---------------------------------------------------------------- credencial

	@Test
	void noSemPerfilDeBordaNaoLeOFeed() throws Exception {
		String admin = adminToken();
		Node node = activeNode(admin, "midia-a", List.of("NODE_MEDIA"));

		mockMvc.perform(get("/network/content/changes").header("X-Node-Id", node.id())
			.header("X-Node-Key", node.key())).andExpect(status().isForbidden());
	}

	@Test
	void feedSemChaveDeNoEh401() throws Exception {
		mockMvc.perform(get("/network/content/changes")).andExpect(status().isUnauthorized());
	}

	// ---------------------------------------------------------------- helpers

	private record Node(String id, String key) {
	}

	private int feedCount() {
		Integer count = jdbc.queryForObject("select count(*) from network_content_changes", Integer.class);
		return count == null ? 0 : count;
	}

	/** Envelhece a posição mais antiga do feed para além da janela de retenção. */
	private void ageOldest(int hours) {
		Long oldest = jdbc.queryForObject("select min(seq) from network_content_changes", Long.class);
		jdbc.update("update network_content_changes set changed_at = ? where seq = ?",
			java.sql.Timestamp.from(Instant.now().minusSeconds(hours * 3600L)), oldest);
	}

	private String adminToken() throws Exception {
		var session = auth.authenticate("net_changes_admin");
		TestDatabase.setAdmin(session.userId());
		return session.token();
	}

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

	private void content(Node node, String kind, String contentId) throws Exception {
		mockMvc.perform(get("/network/content/" + kind + "/" + contentId).header("X-Node-Id", node.id())
			.header("X-Node-Key", node.key())).andExpect(status().isOk());
	}

	private JsonNode feed(Node node, long cursor, int limit) throws Exception {
		MvcResult result = mockMvc
			.perform(get("/network/content/changes").header("X-Node-Id", node.id())
				.header("X-Node-Key", node.key()).param("cursor", String.valueOf(cursor))
				.param("limit", String.valueOf(limit)))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}
}