package com.socialcoisa.rede.feed.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

/**
 * Contagem de reacoes (BUG-05) e notificacao de resposta a comentario (BUG-06).
 */
@SpringBootTest
@AutoConfigureMockMvc
class PostReactionsAndReplyTests {

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
	}

	@Test
	void changingReactionTypeDoesNotInflateTheTotal() throws Exception {
		var author = auth.authenticate("user_react_author");
		var reactor = auth.authenticate("user_react_actor");
		String postId = publish(author, "Post para testar a contagem de reacoes.");

		// Nova reacao: conta 1.
		assertThat(react(reactor, postId, "LIKE", "r-like-1")).isEqualTo(1);
		// Trocar o tipo NAO pode aumentar o total.
		assertThat(react(reactor, postId, "LOVE", "r-love-1")).isEqualTo(1);
		// Remover zera.
		assertThat(removeReaction(reactor, postId, "r-del-1")).isZero();
	}

	@Test
	void repeatingTheSameReactionTogglesItOffWithoutDrift() throws Exception {
		var author = auth.authenticate("user_react_author2");
		var reactor = auth.authenticate("user_react_actor2");
		String postId = publish(author, "Tocar a mesma reacao de novo deve desfazer.");

		assertThat(react(reactor, postId, "LIKE", "t-like-1")).isEqualTo(1);
		// Mesmo tipo de novo = toggle off (nao soma).
		assertThat(react(reactor, postId, "LIKE", "t-like-2")).isZero();
		// E a contagem volta a subir quando reage de verdade.
		assertThat(react(reactor, postId, "LIKE", "t-like-3")).isEqualTo(1);
	}

	/** The card reads the pills from the post itself (one batched query per page), not one call per post. */
	@Test
	void postReadsCarryTheReactionSummaryMatchingTheReactionsEndpoint() throws Exception {
		var author = auth.authenticate("user_react_author_sum");
		var a = auth.authenticate("user_react_a_sum");
		var b = auth.authenticate("user_react_b_sum");
		var c = auth.authenticate("user_react_c_sum");
		String postId = publish(author, "Resumo de reacoes vem junto com o post.");
		react(a, postId, "LIKE", "s-a-1");
		react(b, postId, "LIKE", "s-b-1");
		react(c, postId, "LOVE", "s-c-1");

		MvcResult single = mockMvc.perform(get("/feed/posts/" + postId).header("X-Session-Token", author.token()))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode summary = objectMapper.readTree(single.getResponse().getContentAsString()).get("reactionSummary");
		assertThat(summary.get("LIKE").asInt()).isEqualTo(2);
		assertThat(summary.get("LOVE").asInt()).isEqualTo(1);

		MvcResult list = mockMvc.perform(get("/feed/posts/" + postId + "/reactions")
				.header("X-Session-Token", author.token()))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode body = objectMapper.readTree(list.getResponse().getContentAsString());
		assertThat(body.get("summary")).isEqualTo(summary);
		assertThat(body.get("reactions").size()).isEqualTo(3);

		// A post nobody reacted to still carries the field (empty), so the card knows not to fetch.
		String quiet = publish(author, "Post sem nenhuma reacao ainda.");
		MvcResult empty = mockMvc.perform(get("/feed/posts/" + quiet).header("X-Session-Token", author.token()))
			.andExpect(status().isOk())
			.andReturn();
		assertThat(objectMapper.readTree(empty.getResponse().getContentAsString()).get("reactionSummary").size())
			.isZero();
	}

	/**
	 * Reactions received pay nothing (#330): paying for popularity invites like-farming. The old
	 * reward paid the author on every 10th reaction, so 10 reactors would have created an entry.
	 */
	@Test
	void reactionsReceivedDoNotCreateCoinEntriesForTheAuthor() throws Exception {
		var author = auth.authenticate("user_react_noreward_author");
		String postId = publish(author, "Reacao nao rende faisca para quem publicou.");
		long before = coinEntries(author.userId());

		for (int i = 0; i < 10; i++) {
			var reactor = auth.authenticate("user_react_noreward_r" + i);
			assertThat(react(reactor, postId, "LIKE", "nr-like-" + i)).isEqualTo(i + 1);
		}

		assertThat(coinEntries(author.userId())).isEqualTo(before);
	}

	private long coinEntries(String accountId) {
		return jdbc.queryForObject("select count(*) from coin_entries where account_id = ?", Long.class, accountId);
	}

	@Test
	void secondUserReactionCountsIndependently() throws Exception {
		var author = auth.authenticate("user_react_author3");
		var a = auth.authenticate("user_react_a3");
		var b = auth.authenticate("user_react_b3");
		String postId = publish(author, "Duas pessoas, duas contagens.");

		assertThat(react(a, postId, "LIKE", "m-a-1")).isEqualTo(1);
		assertThat(react(b, postId, "WOW", "m-b-1")).isEqualTo(2);
		// a troca de tipo de a nao mexe no total (que continua 2).
		assertThat(react(a, postId, "SAD", "m-a-2")).isEqualTo(2);
	}

	@Test
	void replyingToACommentNotifiesItsAuthor() throws Exception {
		var postAuthor = auth.authenticate("user_reply_poster");
		var commenter = auth.authenticate("user_reply_commenter");
		var postId = publish(postAuthor, "Post para conversa de comentarios.");

		String commentId = comment(commenter, postId, null, "Primeiro comentario.", "c-first-1");
		// O autor do post responde ao comentario de outra pessoa.
		comment(postAuthor, postId, commentId, "Obrigado pelo comentario!", "c-reply-1");

		List<JsonNode> notifications = notifications(commenter);
		assertThat(notifications)
			.anySatisfy(n -> {
				assertThat(n.get("kind").asText()).isEqualTo("NEW_REPLY");
				assertThat(n.get("actorId").asText()).isEqualTo(postAuthor.userId());
			});
	}

	@Test
	void replyingToOwnCommentDoesNotNotifyYourself() throws Exception {
		var postAuthor = auth.authenticate("user_reply_poster2");
		var commenter = auth.authenticate("user_reply_self");
		String postId = publish(postAuthor, "Responder a si mesmo nao gera aviso inutil.");

		String commentId = comment(commenter, postId, null, "Comentario proprio.", "c-self-1");
		comment(commenter, postId, commentId, "Complementando meu proprio comentario.", "c-self-2");

		List<JsonNode> notifications = notifications(commenter);
		assertThat(notifications).noneSatisfy(n -> assertThat(n.get("kind").asText()).isEqualTo("NEW_REPLY"));
	}

	private String publish(TestAuthHelper.AuthSession session, String content) throws Exception {
		MvcResult result = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "pub-" + content.hashCode())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"type", "INSIGHT", "content", content, "tags", List.of(), "mediaIds", List.of()))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private int react(TestAuthHelper.AuthSession session, String postId, String type, String idem) throws Exception {
		MvcResult result = mockMvc.perform(post("/feed/posts/" + postId + "/reactions")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", idem)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("type", type))))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("counters").get("reactions").asInt();
	}

	private int removeReaction(TestAuthHelper.AuthSession session, String postId, String idem) throws Exception {
		MvcResult result = mockMvc.perform(delete("/feed/posts/" + postId + "/reactions")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", idem))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("counters").get("reactions").asInt();
	}

	private String comment(TestAuthHelper.AuthSession session, String postId, String parentCommentId, String content,
			String idem) throws Exception {
		var body = new java.util.HashMap<String, Object>();
		body.put("content", content);
		if (parentCommentId != null) body.put("parentCommentId", parentCommentId);
		MvcResult result = mockMvc.perform(post("/feed/posts/" + postId + "/comments")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", idem)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(body)))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private List<JsonNode> notifications(TestAuthHelper.AuthSession session) throws Exception {
		MvcResult result = mockMvc.perform(get("/notifications").header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
		JsonNode items = root.has("items") ? root.get("items") : root;
		List<JsonNode> list = new java.util.ArrayList<>();
		for (int i = 0; i < items.size(); i++) {
			list.add(items.get(i));
		}
		return list;
	}
}