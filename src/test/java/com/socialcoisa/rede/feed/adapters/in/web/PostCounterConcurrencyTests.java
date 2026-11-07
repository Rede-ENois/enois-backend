package com.socialcoisa.rede.feed.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import com.socialcoisa.rede.feed.application.port.out.PostRepository;
import com.socialcoisa.rede.feed.domain.Post;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestAuthHelper.AuthSession;

/**
 * Post counters under concurrent interactions on the same post. Each interaction used to load the
 * post, change the counter in memory and save the whole row back: concurrent ones overwrote each
 * other (lost updates on H2, "Record has changed since last read" and deadlocks on MariaDB).
 */
@SpringBootTest
@AutoConfigureMockMvc
class PostCounterConcurrencyTests {

	private static final int PEOPLE = 8;

	@Autowired private MockMvc mockMvc;
	@Autowired private ObjectMapper objectMapper;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private PostRepository posts;
	@Autowired private TransactionTemplate transactions;

	private TestAuthHelper auth;
	private final String prefix = "counter_" + UUID.randomUUID().toString().substring(0, 8);

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	@Test
	void concurrentReactionsCommentsAndSavesKeepExactCounters() throws Exception {
		AuthSession author = auth.authenticate(prefix + "_author");
		String postId = publish(author, "Post popular " + prefix);
		List<AuthSession> people = people("fan");

		List<Callable<Integer>> calls = new ArrayList<>();
		for (int i = 0; i < people.size(); i++) {
			AuthSession person = people.get(i);
			int n = i;
			calls.add(() -> statusOf(post("/feed/posts/" + postId + "/reactions"), person, "react-" + n,
				Map.of("type", n % 2 == 0 ? "LIKE" : "LOVE")));
			calls.add(() -> statusOf(post("/feed/posts/" + postId + "/comments"), person, "comment-" + n,
				Map.of("content", "Comentario " + n + " " + prefix)));
			calls.add(() -> statusOf(post("/feed/posts/" + postId + "/saves"), person, "save-" + n, null));
		}

		assertThat(runTogether(calls)).allSatisfy(status -> assertThat(status).isBetween(200, 299));
		assertThat(counters(postId)).containsExactly(PEOPLE, PEOPLE, PEOPLE);
		assertThat(rows(postId)).containsExactly(PEOPLE, PEOPLE, PEOPLE);
	}

	@Test
	void concurrentRemovalsGiveEachInteractionBackOnce() throws Exception {
		AuthSession author = auth.authenticate(prefix + "_author2");
		String postId = publish(author, "Post com desfazer " + prefix);
		List<AuthSession> people = people("undo");
		List<String> commentIds = new ArrayList<>();
		for (int i = 0; i < people.size(); i++) {
			AuthSession person = people.get(i);
			assertThat(statusOf(post("/feed/posts/" + postId + "/reactions"), person, "r-" + i,
				Map.of("type", "LIKE"))).isEqualTo(200);
			commentIds.add(comment(person, postId, "Para apagar " + i + " " + prefix, "c-" + i));
		}

		// Half of the people take everything back, each removal sent twice at once (double tap).
		List<Callable<Integer>> calls = new ArrayList<>();
		for (int i = 0; i < PEOPLE / 2; i++) {
			AuthSession person = people.get(i);
			String commentId = commentIds.get(i);
			for (String tap : List.of("a", "b")) {
				calls.add(() -> statusOf(delete("/feed/posts/" + postId + "/reactions"), person,
					"unreact-" + commentId + tap, null));
				calls.add(() -> statusOf(delete("/feed/posts/" + postId + "/comments/" + commentId), person,
					"uncomment-" + commentId + tap, null));
			}
		}

		assertThat(runTogether(calls)).allSatisfy(status -> assertThat(status).isIn(200, 404));
		assertThat(counters(postId)).containsExactly(PEOPLE / 2, PEOPLE / 2, 0);
		assertThat(rows(postId)).containsExactly(PEOPLE / 2, PEOPLE / 2, 0);
	}

	@Test
	void savingAPostLoadedBeforeAnInteractionKeepsTheCounters() throws Exception {
		AuthSession author = auth.authenticate(prefix + "_author3");
		String postId = publish(author, "Post salvo depois " + prefix);
		Post loadedBefore = posts.findById(new PostId(postId)).orElseThrow();
		AuthSession fan = auth.authenticate(prefix + "_late_fan");

		assertThat(statusOf(post("/feed/posts/" + postId + "/reactions"), fan, "late-react",
			Map.of("type", "LIKE"))).isEqualTo(200);
		comment(fan, postId, "Chegou antes do save " + prefix, "late-comment");
		transactions.executeWithoutResult(status -> posts.save(loadedBefore));

		assertThat(counters(postId)).containsExactly(1, 1, 0);
	}

	@Test
	void sameCommentReactionTappedRepeatedlyAtOnceNeverFails() throws Exception {
		AuthSession author = auth.authenticate(prefix + "_author4");
		String postId = publish(author, "Post do comentario " + prefix);
		String commentId = comment(author, postId, "Comentario reagido " + prefix, "creact-comment");
		AuthSession fan = auth.authenticate(prefix + "_tapper");

		List<Callable<Integer>> calls = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			int n = i;
			calls.add(() -> statusOf(post("/feed/posts/" + postId + "/comments/" + commentId + "/reactions"), fan,
				"creact-" + n, Map.of("type", n % 2 == 0 ? "LIKE" : "LOVE")));
		}

		assertThat(runTogether(calls)).allSatisfy(status -> assertThat(status).isEqualTo(200));
		assertThat(jdbc.queryForObject("select count(*) from comment_reactions where comment_id = ? and user_id = ?",
			Integer.class, commentId, fan.userId())).isBetween(0, 1);
	}

	private List<AuthSession> people(String role) throws Exception {
		List<AuthSession> people = new ArrayList<>();
		for (int i = 0; i < PEOPLE; i++) {
			people.add(auth.authenticate(prefix + "_" + role + i));
		}
		return people;
	}

	private List<Integer> runTogether(List<Callable<Integer>> calls) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(calls.size());
		try {
			CountDownLatch start = new CountDownLatch(1);
			List<Future<Integer>> futures = new ArrayList<>();
			for (Callable<Integer> call : calls) {
				futures.add(pool.submit(() -> {
					start.await();
					return call.call();
				}));
			}
			start.countDown();
			List<Integer> statuses = new ArrayList<>();
			for (Future<Integer> future : futures) {
				statuses.add(future.get(60, TimeUnit.SECONDS));
			}
			return statuses;
		}
		finally {
			pool.shutdownNow();
		}
	}

	private int statusOf(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
			AuthSession session, String idempotencyKey, Map<String, Object> body) throws Exception {
		request.header("X-Session-Token", session.token())
			.header("Idempotency-Key", prefix + "-" + idempotencyKey);
		if (body != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
		}
		return mockMvc.perform(request).andReturn().getResponse().getStatus();
	}

	/** reactions, comments, saves as stored on the post. */
	private List<Integer> counters(String postId) {
		return jdbc.queryForObject("select reactions, comments, saves from posts where id = ?",
			(rs, n) -> List.of(rs.getInt(1), rs.getInt(2), rs.getInt(3)), postId);
	}

	/** The rows the counters mirror, in the same order as {@link #counters}. */
	private List<Integer> rows(String postId) {
		return List.of(
			jdbc.queryForObject("select count(*) from reactions where post_id = ?", Integer.class, postId),
			jdbc.queryForObject("select count(*) from comments where post_id = ? and status = 'ACTIVE'",
				Integer.class, postId),
			jdbc.queryForObject("select count(*) from saved_posts where post_id = ?", Integer.class, postId));
	}

	private String publish(AuthSession session, String content) throws Exception {
		MvcResult result = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "pub-" + prefix + content.hashCode())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA",
					"type", "INSIGHT", "content", content, "tags", List.of(), "mediaIds", List.of()))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private String comment(AuthSession session, String postId, String content, String idempotencyKey)
			throws Exception {
		MvcResult result = mockMvc.perform(post("/feed/posts/" + postId + "/comments")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", prefix + "-" + idempotencyKey)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("content", content))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}
}
