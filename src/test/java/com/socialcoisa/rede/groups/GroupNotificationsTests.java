package com.socialcoisa.rede.groups;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Notificações do contexto de Grupos (seção 16 do plano).
 *
 * O que este teste tranca:
 *
 * - pedido pendente avisa quem administra, e NÃO avisa o solicitante (que ainda não é membro);
 * - aprovação/rejeição avisa o solicitante;
 * - entrada aberta avisa a administração; remoção avisa quem foi removido;
 * - comentário avisa o autor da publicação e resposta avisa o autor do comentário pai;
 * - o texto do comentário NUNCA viaja na notificação (a caixa é lida fora do grupo);
 * - o href aponta para a página do grupo, que continua exigindo autorização;
 * - transferência de propriedade avisa o novo dono e a mudança de estado pela plataforma avisa
 *   o dono;
 * - a new post notifies every other member once per group: while unread, the next posts update the
 *   same notification (count and text), the author gets nothing, and after reading a new one starts.
 */
@SpringBootTest
@AutoConfigureMockMvc
class GroupNotificationsTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private TestAuthHelper authHelper() {
		return new TestAuthHelper(mockMvc, objectMapper);
	}

	private String createGroup(TestAuthHelper.AuthSession owner, String name, String joinMode, String key)
			throws Exception {
		MvcResult result = mockMvc.perform(post("/groups")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", name, "joinMode", joinMode))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private void join(TestAuthHelper.AuthSession user, String groupId, String key) throws Exception {
		mockMvc.perform(post("/groups/" + groupId + "/join")
				.header("X-Session-Token", user.token())
				.header("Idempotency-Key", key))
			.andExpect(status().isOk());
	}

	private String pendingRequestId(TestAuthHelper.AuthSession admin, String groupId) throws Exception {
		MvcResult result = mockMvc.perform(get("/groups/" + groupId + "/requests")
				.header("X-Session-Token", admin.token()))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString())
			.get("items").get(0).get("id").asText();
	}

	private String topic(TestAuthHelper.AuthSession admin, String groupId, String name, String key) throws Exception {
		MvcResult result = mockMvc.perform(post("/groups/" + groupId + "/topics")
				.header("X-Session-Token", admin.token())
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", name))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private String publish(TestAuthHelper.AuthSession author, String groupId, String topicId, String text,
			String key) throws Exception {
		MvcResult result = mockMvc.perform(post("/groups/" + groupId + "/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topicId", topicId, "content", text))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private String comment(TestAuthHelper.AuthSession author, String groupId, String postId, String text,
			String parentId, String key) throws Exception {
		Map<String, Object> body = new java.util.HashMap<>();
		body.put("content", text);
		if (parentId != null) {
			body.put("parentCommentId", parentId);
		}
		MvcResult result = mockMvc.perform(post("/groups/" + groupId + "/posts/" + postId + "/comments")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(body)))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private JsonNode notifications(TestAuthHelper.AuthSession user) throws Exception {
		MvcResult result = mockMvc.perform(get("/notifications")
				.header("X-Session-Token", user.token()))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("items");
	}

	private JsonNode byKind(JsonNode items, String kind) {
		for (JsonNode item : items) {
			if (kind.equals(item.get("kind").asText())) {
				return item;
			}
		}
		return null;
	}

	private java.util.List<JsonNode> allOfKind(JsonNode items, String kind) {
		java.util.List<JsonNode> found = new java.util.ArrayList<>();
		for (JsonNode item : items) {
			if (kind.equals(item.get("kind").asText())) {
				found.add(item);
			}
		}
		return found;
	}

	/** The new-post fan-out runs on the outbox worker, so the test waits for it (bounded). */
	private java.util.List<JsonNode> awaitNewPosts(TestAuthHelper.AuthSession user,
			java.util.function.Predicate<java.util.List<JsonNode>> done) throws Exception {
		long deadline = System.currentTimeMillis() + 20_000;
		java.util.List<JsonNode> items = allOfKind(notifications(user), "GROUP_NEW_POST");
		while (!done.test(items)) {
			assertThat(System.currentTimeMillis()).as("outbox worker did not deliver in time").isLessThan(deadline);
			Thread.sleep(200);
			items = allOfKind(notifications(user), "GROUP_NEW_POST");
		}
		return items;
	}

	@Test
	void newPostsCollapseIntoOneNotificationPerGroupWhileUnread() throws Exception {
		var owner = authHelper().authenticate("notif-newpost-owner");
		var author = authHelper().authenticate("notif-newpost-author");
		var reader = authHelper().authenticate("notif-newpost-reader");
		String groupId = createGroup(owner, "Notif Novas", "OPEN", "create-notif-newpost");
		join(author, groupId, "join-notif-newpost-1");
		join(reader, groupId, "join-notif-newpost-2");
		String topicId = topic(owner, groupId, "Geral", "topic-notif-newpost");

		publish(author, groupId, topicId, "texto privado do grupo", "post-notif-newpost-1");

		var first = awaitNewPosts(reader, items -> items.size() == 1);
		assertThat(first.get(0).get("body").asText()).isEqualTo("notif-newpost-author publicou em Notif Novas.")
			.doesNotContain("texto privado");
		assertThat(first.get(0).get("title").asText()).isEqualTo("Novas publicações em Notif Novas");
		assertThat(first.get(0).get("href").asText()).isEqualTo("/grupos/notif-novas");
		assertThat(awaitNewPosts(owner, items -> items.size() == 1)).hasSize(1);

		publish(author, groupId, topicId, "segunda", "post-notif-newpost-2");

		var collapsed = awaitNewPosts(reader, items -> items.size() == 1
			&& items.get(0).get("actorCount").asInt() == 2);
		assertThat(collapsed.get(0).get("id").asText()).isEqualTo(first.get(0).get("id").asText());
		assertThat(collapsed.get(0).get("body").asText()).isEqualTo("2 novas publicações em Notif Novas.");

		mockMvc.perform(post("/notifications/" + collapsed.get(0).get("id").asText() + "/read")
				.header("X-Session-Token", reader.token())
				.header("Idempotency-Key", "read-notif-newpost"))
			.andExpect(status().isOk());
		publish(author, groupId, topicId, "terceira", "post-notif-newpost-3");

		var fresh = awaitNewPosts(reader, items -> items.size() == 2);
		assertThat(fresh.get(0).get("read").asBoolean()).isFalse();
		assertThat(fresh.get(0).get("actorCount").asInt()).isEqualTo(1);
		assertThat(fresh.get(0).get("body").asText()).isEqualTo("notif-newpost-author publicou em Notif Novas.");
		// The owner never read: three posts, still one notification.
		assertThat(awaitNewPosts(owner, items -> !items.isEmpty() && items.get(0).get("actorCount").asInt() == 3))
			.hasSize(1);
		// The author is never told about their own posts.
		assertThat(allOfKind(notifications(author), "GROUP_NEW_POST")).isEmpty();
	}

	@Test
	void pedidoPendenteAvisaAdministracaoENaoOSolicitante() throws Exception {
		var dono = authHelper().authenticate("notif-pedido-dono");
		var pessoa = authHelper().authenticate("notif-pedido-pessoa");
		String groupId = createGroup(dono, "Notif Pedido", "APPROVAL", "create-notif-pedido");

		join(pessoa, groupId, "join-notif-pedido");

		JsonNode pedido = byKind(notifications(dono), "GROUP_JOIN_REQUEST");
		assertThat(pedido).isNotNull();
		assertThat(pedido.get("actorId").asText()).isEqualTo(pessoa.userId());
		assertThat(pedido.get("href").asText()).isEqualTo("/grupos/notif-pedido");
		assertThat(pedido.get("body").asText()).contains("notif-pedido-pessoa");

		// O solicitante ainda não é membro: não recebe a notificação do próprio pedido.
		assertThat(byKind(notifications(pessoa), "GROUP_JOIN_REQUEST")).isNull();
	}

	@Test
	void aprovacaoErejeicaoAvisamOSolicitante() throws Exception {
		var dono = authHelper().authenticate("notif-decisao-dono");
		var aprovada = authHelper().authenticate("notif-decisao-aprovada");
		var recusada = authHelper().authenticate("notif-decisao-recusada");
		String groupId = createGroup(dono, "Notif Decisao", "APPROVAL", "create-notif-decisao");

		join(aprovada, groupId, "join-notif-dec-1");
		String requestAprovada = pendingRequestId(dono, groupId);
		mockMvc.perform(post("/groups/" + groupId + "/requests/" + requestAprovada + "/approve")
				.header("X-Session-Token", dono.token())
				.header("Idempotency-Key", "approve-notif-dec-1"))
			.andExpect(status().isOk());

		JsonNode aprovado = byKind(notifications(aprovada), "GROUP_JOIN_APPROVED");
		assertThat(aprovado).isNotNull();
		assertThat(aprovado.get("href").asText()).isEqualTo("/grupos/notif-decisao");

		join(recusada, groupId, "join-notif-dec-2");
		String requestRecusada = pendingRequestId(dono, groupId);
		mockMvc.perform(post("/groups/" + groupId + "/requests/" + requestRecusada + "/reject")
				.header("X-Session-Token", dono.token())
				.header("Idempotency-Key", "reject-notif-dec-2"))
			.andExpect(status().isOk());

		assertThat(byKind(notifications(recusada), "GROUP_JOIN_REJECTED")).isNotNull();
		assertThat(byKind(notifications(recusada), "GROUP_JOIN_APPROVED")).isNull();
	}

	@Test
	void entradaAbertaAvisaAdministracao() throws Exception {
		var dono = authHelper().authenticate("notif-aberto-dono");
		var pessoa = authHelper().authenticate("notif-aberto-pessoa");
		String groupId = createGroup(dono, "Notif Aberto", "OPEN", "create-notif-aberto");

		join(pessoa, groupId, "join-notif-aberto");

		JsonNode entrada = byKind(notifications(dono), "GROUP_MEMBER_JOINED");
		assertThat(entrada).isNotNull();
		assertThat(entrada.get("actorId").asText()).isEqualTo(pessoa.userId());
	}

	@Test
	void remocaoAvisaORemovidoEORetiraDoConteudo() throws Exception {
		var dono = authHelper().authenticate("notif-remocao-dono");
		var pessoa = authHelper().authenticate("notif-remocao-pessoa");
		String groupId = createGroup(dono, "Notif Remocao", "OPEN", "create-notif-remocao");
		String topicId = topic(dono, groupId, "Geral", "topic-notif-remocao");
		publish(dono, groupId, topicId, "conteudo interno", "post-notif-remocao");
		join(pessoa, groupId, "join-notif-remocao");

		mockMvc.perform(delete("/groups/" + groupId + "/members/" + pessoa.userId())
				.header("X-Session-Token", dono.token())
				.header("Idempotency-Key", "remove-notif-remocao"))
			.andExpect(status().isOk());

		JsonNode removido = byKind(notifications(pessoa), "GROUP_MEMBER_REMOVED");
		assertThat(removido).isNotNull();
		assertThat(removido.get("href").asText()).isEqualTo("/grupos/notif-remocao");

		// O href continua exigindo autorização: removido não lê o feed do grupo.
		mockMvc.perform(get("/groups/" + groupId + "/posts")
				.header("X-Session-Token", pessoa.token()))
			.andExpect(status().isForbidden());
	}

	@Test
	void comentarioAvisaOAutorSemLevarOTexto() throws Exception {
		var dono = authHelper().authenticate("notif-coment-dono");
		var pessoa = authHelper().authenticate("notif-coment-pessoa");
		String groupId = createGroup(dono, "Notif Comentario", "OPEN", "create-notif-coment");
		join(pessoa, groupId, "join-notif-coment");
		String topicId = topic(dono, groupId, "Geral", "topic-notif-coment");
		String postId = publish(dono, groupId, topicId, "publicacao do dono", "post-notif-coment");

		comment(pessoa, groupId, postId, "segredo que nao deve vazar", null, "comment-notif-1");

		JsonNode aviso = byKind(notifications(dono), "NEW_GROUP_COMMENT");
		assertThat(aviso).isNotNull();
		assertThat(aviso.get("actorId").asText()).isEqualTo(pessoa.userId());
		assertThat(aviso.get("body").asText()).doesNotContain("segredo que nao deve vazar");
		assertThat(aviso.get("body").asText()).contains("comentou na sua publicação");

		// Quem comentou não recebe o aviso do próprio comentário.
		assertThat(byKind(notifications(pessoa), "NEW_GROUP_COMMENT")).isNull();
	}

	@Test
	void respostaAvisaOAutorDoComentarioPai() throws Exception {
		var dono = authHelper().authenticate("notif-reply-dono");
		var autorPai = authHelper().authenticate("notif-reply-pai");
		var autorResposta = authHelper().authenticate("notif-reply-resposta");
		String groupId = createGroup(dono, "Notif Resposta", "OPEN", "create-notif-reply");
		join(autorPai, groupId, "join-notif-reply-1");
		join(autorResposta, groupId, "join-notif-reply-2");
		String topicId = topic(dono, groupId, "Geral", "topic-notif-reply");
		String postId = publish(dono, groupId, topicId, "publicacao", "post-notif-reply");

		String parentId = comment(autorPai, groupId, postId, "comentario pai", null, "comment-notif-pai");
		comment(autorResposta, groupId, postId, "resposta", parentId, "comment-notif-resposta");

		assertThat(byKind(notifications(autorPai), "NEW_GROUP_REPLY")).isNotNull();
		// O dono (autor do post) recebe o comentário; quem respondeu não recebe os próprios.
		assertThat(byKind(notifications(dono), "NEW_GROUP_COMMENT")).isNotNull();
		assertThat(byKind(notifications(autorResposta), "NEW_GROUP_COMMENT")).isNull();
		assertThat(byKind(notifications(autorResposta), "NEW_GROUP_REPLY")).isNull();
	}

	@Test
	void transferenciaAvisaONovoDono() throws Exception {
		var dono = authHelper().authenticate("notif-transfer-dono");
		var novoDono = authHelper().authenticate("notif-transfer-novo");
		String groupId = createGroup(dono, "Notif Transfer", "OPEN", "create-notif-transfer");
		join(novoDono, groupId, "join-notif-transfer");

		mockMvc.perform(post("/groups/" + groupId + "/transfer")
				.header("X-Session-Token", dono.token())
				.header("Idempotency-Key", "transfer-notif")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("userId", novoDono.userId()))))
			.andExpect(status().isOk());

		JsonNode aviso = byKind(notifications(novoDono), "GROUP_OWNERSHIP_TRANSFERRED");
		assertThat(aviso).isNotNull();
		assertThat(aviso.get("body").asText()).contains("propriedade");
	}

	@Test
	void mudancaDeEstadoPelaPlataformaAvisaODono() throws Exception {
		var dono = authHelper().authenticate("notif-status-dono");
		var admin = authHelper().authenticate("group-admin");
		String groupId = createGroup(dono, "Notif Status", "OPEN", "create-notif-status");

		mockMvc.perform(post("/groups/" + groupId + "/status")
				.header("X-Session-Token", admin.token())
				.header("Idempotency-Key", "status-notif-suspenso")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("status", "SUSPENDED", "reason", "investigacao"))))
			.andExpect(status().isOk());

		JsonNode aviso = byKind(notifications(dono), "GROUP_STATUS_CHANGED");
		assertThat(aviso).isNotNull();
		assertThat(aviso.get("body").asText()).contains("SUSPENDED").contains("investigacao");
	}

	@Test
	void remocaoDeConteudoPelaModeracaoAvisaOAutor() throws Exception {
		var dono = authHelper().authenticate("notif-mod-dono");
		var pessoa = authHelper().authenticate("notif-mod-pessoa");
		String groupId = createGroup(dono, "Notif Moderacao", "OPEN", "create-notif-mod");
		join(pessoa, groupId, "join-notif-mod");
		String topicId = topic(dono, groupId, "Geral", "topic-notif-mod");
		String postId = publish(pessoa, groupId, topicId, "publicacao da pessoa", "post-notif-mod");

		mockMvc.perform(delete("/groups/" + groupId + "/posts/" + postId)
				.header("X-Session-Token", dono.token())
				.header("Idempotency-Key", "remove-notif-mod"))
			.andExpect(status().isOk());

		JsonNode aviso = byKind(notifications(pessoa), "GROUP_CONTENT_REMOVED");
		assertThat(aviso).isNotNull();
		assertThat(aviso.get("body").asText()).contains("publicação");
	}
}
