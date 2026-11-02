package com.socialcoisa.rede.feed.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.socialcoisa.rede.coin.application.CoinService;
import com.socialcoisa.rede.feed.application.UserContentPurgeService;
import com.socialcoisa.rede.moderation.application.BanCleanupService;
import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestAuthHelper.AuthSession;

/**
 * Duvidas com resposta aceita: so quem perguntou aceita, a resposta precisa ser de outra pessoa e
 * desta duvida, e a faisca de "ajuda" sai uma vez por duvida (trocar a resposta nao paga de novo).
 * Se a resposta aceita some (autor apaga, banimento, purga de spam), a duvida volta a ficar aberta.
 */
@SpringBootTest
@AutoConfigureMockMvc
class QuestionAcceptedAnswerTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	@MockitoSpyBean
	private CoinService coins;

	@Autowired
	private BanCleanupService banCleanup;

	@Autowired
	private UserContentPurgeService purge;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	@Test
	void askerAcceptsAnswerResolvesPaysAndNotifies() throws Exception {
		AuthSession asker = veteran("qaa_asker1");
		AuthSession helper = veteran("qaa_helper1");
		String questionId = publish(asker, "QUESTION", "Como rodo um teste so no maven?");
		String answerId = comment(helper, questionId, "Usa -Dtest=NomeDaClasse");

		accept(asker, questionId, answerId)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.acceptedCommentId").value(answerId))
			.andExpect(jsonPath("$.resolved").value(true));

		mockMvc.perform(get("/feed/posts/" + questionId))
			.andExpect(jsonPath("$.acceptedCommentId").value(answerId))
			.andExpect(jsonPath("$.resolved").value(true));
		mockMvc.perform(get("/feed/posts/" + questionId + "/comments"))
			.andExpect(jsonPath("$.items[0].accepted").value(true));
		assertThat(rewards(questionId, helper.userId())).isEqualTo(1);
		// Same API-path form as NEW_REPLY/MENTION: the app maps "/api/feed/posts/{id}" to "/post/{id}".
		assertThat(jdbc.queryForList(
			"select href from notifications where user_id = ? and kind = 'ANSWER_ACCEPTED'", String.class,
			helper.userId())).containsExactly("/api/feed/posts/" + questionId);
	}

	@Test
	void onlyTheAskerAccepts() throws Exception {
		AuthSession asker = veteran("qaa_asker2");
		AuthSession helper = veteran("qaa_helper2");
		String questionId = publish(asker, "QUESTION", "Duvida que so a dona resolve");
		String answerId = comment(helper, questionId, "Resposta da helper2");

		accept(helper, questionId, answerId)
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NOT_QUESTION_AUTHOR"));
	}

	@Test
	void ownCommentCannotBeAccepted() throws Exception {
		AuthSession asker = veteran("qaa_asker3");
		String questionId = publish(asker, "QUESTION", "Duvida com auto resposta");
		String ownId = comment(asker, questionId, "Eu mesma respondo");

		accept(asker, questionId, ownId)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("OWN_ANSWER"));
		assertThat(rewards(questionId, asker.userId())).isZero();
	}

	@Test
	void commentFromAnotherPostIsRefused() throws Exception {
		AuthSession asker = veteran("qaa_asker4");
		AuthSession helper = veteran("qaa_helper4");
		String questionId = publish(asker, "QUESTION", "Duvida A do asker4");
		String otherId = publish(asker, "QUESTION", "Duvida B do asker4");
		String foreignAnswer = comment(helper, otherId, "Resposta da duvida B");

		accept(asker, questionId, foreignAnswer)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_ANSWER_COMMENT"));
	}

	@Test
	void onlyQuestionsHaveAcceptedAnswers() throws Exception {
		AuthSession asker = veteran("qaa_asker5");
		AuthSession helper = veteran("qaa_helper5");
		String insightId = publish(asker, "INSIGHT", "Isto nao e uma duvida");
		String commentId = comment(helper, insightId, "Comentario num insight");

		accept(asker, insightId, commentId)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("NOT_A_QUESTION"));
	}

	@Test
	void changingTheAcceptedAnswerNeverPaysTwice() throws Exception {
		AuthSession asker = veteran("qaa_asker6");
		AuthSession first = veteran("qaa_helper6a");
		AuthSession second = veteran("qaa_helper6b");
		String questionId = publish(asker, "QUESTION", "Duvida com duas respostas");
		String firstAnswer = comment(first, questionId, "Primeira resposta");
		String secondAnswer = comment(second, questionId, "Segunda resposta");

		accept(asker, questionId, firstAnswer).andExpect(status().isOk());
		accept(asker, questionId, secondAnswer)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.acceptedCommentId").value(secondAnswer));
		mockMvc.perform(delete("/feed/posts/" + questionId + "/accepted-answer")
			.header("X-Session-Token", asker.token())).andExpect(status().isOk());
		accept(asker, questionId, firstAnswer).andExpect(status().isOk());

		assertThat(rewards(questionId, first.userId())).isEqualTo(1);
		assertThat(rewards(questionId, second.userId())).isZero();
	}

	@Test
	void unacceptReopensTheQuestion() throws Exception {
		AuthSession asker = veteran("qaa_asker7");
		AuthSession helper = veteran("qaa_helper7");
		String questionId = publish(asker, "QUESTION", "Duvida que volta a ficar aberta");
		String answerId = comment(helper, questionId, "Resposta que deixa de valer");
		accept(asker, questionId, answerId).andExpect(status().isOk());

		mockMvc.perform(delete("/feed/posts/" + questionId + "/accepted-answer")
				.header("X-Session-Token", asker.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.resolved").value(false));

		mockMvc.perform(get("/feed/posts/" + questionId))
			.andExpect(jsonPath("$.acceptedCommentId").doesNotExist())
			.andExpect(jsonPath("$.resolved").value(false));
		mockMvc.perform(get("/feed/posts/" + questionId + "/comments"))
			.andExpect(jsonPath("$.items[0].accepted").value(false));
	}

	@Test
	void feedFiltersQuestionsByResolved() throws Exception {
		AuthSession asker = veteran("qaa_asker8");
		AuthSession helper = veteran("qaa_helper8");
		String resolvedId = publish(asker, "QUESTION", "Duvida resolvida do filtro");
		String openId = publish(asker, "QUESTION", "Duvida aberta do filtro");
		accept(asker, resolvedId, comment(helper, resolvedId, "Resposta do filtro")).andExpect(status().isOk());

		List<String> answered = feedIds(asker, "true");
		List<String> open = feedIds(asker, "false");
		assertThat(answered).contains(resolvedId).doesNotContain(openId);
		assertThat(open).contains(openId).doesNotContain(resolvedId);
	}

	@Test
	void rewardFailureKeepsTheAcceptance() throws Exception {
		AuthSession asker = veteran("qaa_asker9");
		AuthSession helper = veteran("qaa_helper9");
		String questionId = publish(asker, "QUESTION", "Duvida com o cofre fora do ar");
		String answerId = comment(helper, questionId, "Resposta sem faisca");
		doThrow(new IllegalStateException("treasury down"))
			.when(coins).reward(anyString(), anyLong(), eq("resposta:" + questionId), anyString());

		accept(asker, questionId, answerId).andExpect(status().isOk());

		mockMvc.perform(get("/feed/posts/" + questionId))
			.andExpect(jsonPath("$.acceptedCommentId").value(answerId));
		assertThat(rewards(questionId, helper.userId())).isZero();
	}

	@Test
	void newAccountAnswerIsAcceptedButNotPaid() throws Exception {
		AuthSession asker = veteran("qaa_asker10");
		AuthSession newcomer = auth.authenticate("qaa_newcomer10");
		String questionId = publish(asker, "QUESTION", "Duvida respondida por conta nova");
		String answerId = comment(newcomer, questionId, "Resposta de quem acabou de chegar");

		accept(asker, questionId, answerId).andExpect(status().isOk());
		assertThat(rewards(questionId, newcomer.userId())).isZero();
	}

	@Test
	void authorDeletingTheAcceptedAnswerReopensTheQuestion() throws Exception {
		AuthSession asker = veteran("qaa_asker11");
		AuthSession helper = veteran("qaa_helper11");
		String questionId = publish(asker, "QUESTION", "Duvida cuja resposta some");
		String answerId = comment(helper, questionId, "Resposta que o autor apaga");
		accept(asker, questionId, answerId).andExpect(status().isOk());

		mockMvc.perform(delete("/feed/posts/" + questionId + "/comments/" + answerId)
				.header("X-Session-Token", helper.token())
				.header("Idempotency-Key", "qaa-" + UUID.randomUUID()))
			.andExpect(status().isOk());

		assertOpen(questionId);
		// The help reward already paid stays: the ref is per question.
		assertThat(rewards(questionId, helper.userId())).isEqualTo(1);
	}

	@Test
	void banCleanupRemovingTheAcceptedAnswerReopensTheQuestion() throws Exception {
		AuthSession asker = veteran("qaa_asker12");
		AuthSession helper = veteran("qaa_helper12");
		String questionId = publish(asker, "QUESTION", "Duvida respondida por conta banida");
		String answerId = comment(helper, questionId, "Resposta de quem vai ser banido");
		accept(asker, questionId, answerId).andExpect(status().isOk());

		banCleanup.cleanup("sanc_qaa12", helper.userId());

		assertOpen(questionId);
	}

	@Test
	void spamPurgeRemovingTheAcceptedAnswerReopensTheQuestion() throws Exception {
		AuthSession asker = veteran("qaa_asker13");
		AuthSession helper = veteran("qaa_helper13");
		String questionId = publish(asker, "QUESTION", "Duvida respondida por spammer");
		String answerId = comment(helper, questionId, "Resposta de quem vai ser purgado");
		accept(asker, questionId, answerId).andExpect(status().isOk());

		purge.purgeAllByAuthor("admin_qaa13", helper.userId(), "teste");

		assertOpen(questionId);
	}

	private void assertOpen(String questionId) throws Exception {
		mockMvc.perform(get("/feed/posts/" + questionId))
			.andExpect(jsonPath("$.acceptedCommentId").doesNotExist())
			.andExpect(jsonPath("$.resolved").value(false));
		assertThat(jdbc.queryForObject("select count(*) from posts where id = ? and resolved_at is null",
			Integer.class, questionId)).isEqualTo(1);
	}

	/** Account older than the new-account window, so it is eligible for the help reward. */
	private AuthSession veteran(String name) throws Exception {
		AuthSession session = auth.authenticate(name);
		jdbc.update("update users set created_at = ? where id = ?",
			Timestamp.from(Instant.now().minus(Duration.ofDays(30))), session.userId());
		return session;
	}

	private int rewards(String questionId, String accountId) {
		return jdbc.queryForObject(
			"select count(*) from coin_entries where ref_id = ? and account_id = ? and kind = 'REWARD'", Integer.class,
			"resposta:" + questionId, accountId);
	}

	private org.springframework.test.web.servlet.ResultActions accept(AuthSession user, String postId, String commentId)
			throws Exception {
		return mockMvc.perform(post("/feed/posts/" + postId + "/accepted-answer")
			.header("X-Session-Token", user.token())
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("commentId", commentId))));
	}

	private List<String> feedIds(AuthSession user, String resolved) throws Exception {
		String body = mockMvc.perform(get("/feed").param("tab", "ALL").param("type", "QUESTION")
				.param("resolved", resolved).param("limit", "50")
				.header("X-Session-Token", user.token()))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		List<String> ids = new ArrayList<>();
		for (JsonNode item : objectMapper.readTree(body).get("items")) {
			ids.add(item.get("id").asText());
		}
		return ids;
	}

	private String publish(AuthSession author, String type, String content) throws Exception {
		String body = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "qaa-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("type", type, "content", content, "tags", List.of(),
					"mediaIds", List.of()))))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		return objectMapper.readTree(body).get("id").asText();
	}

	private String comment(AuthSession author, String postId, String content) throws Exception {
		String body = mockMvc.perform(post("/feed/posts/" + postId + "/comments")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "qaa-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("content", content))))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		return objectMapper.readTree(body).get("id").asText();
	}
}
