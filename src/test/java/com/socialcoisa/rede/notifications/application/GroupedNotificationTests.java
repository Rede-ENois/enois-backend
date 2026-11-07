package com.socialcoisa.rede.notifications.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.ExecutorService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import com.socialcoisa.rede.push.application.port.out.PushSender;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * Reactions to the same target share one unread notification: the text and the count follow the
 * newest person, the same person acting again does not count twice, the push goes out once, and
 * once read the next reaction starts a new notification.
 */
@SpringBootTest
@AutoConfigureMockMvc
class GroupedNotificationTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private NotificationService notifications;

	@Autowired
	private TransactionTemplate transactions;

	@Autowired
	private org.springframework.jdbc.core.JdbcTemplate jdbc;

	@MockitoBean
	private PushSender pushSender;

	/** Push is delivered on a thread pool; inline here so the count sees every delivery finished. */
	@MockitoBean(name = "pushExecutor")
	private ExecutorService pushExecutor;

	@BeforeEach
	void deliverPushInline() {
		doAnswer(invocation -> {
			invocation.<Runnable>getArgument(0).run();
			return null;
		}).when(pushExecutor).execute(any());
	}

	@Test
	void reactionsToTheSameTargetBecomeOneNotification() throws Exception {
		var user = new TestAuthHelper(mockMvc, objectMapper).authenticate("ngroup_owner");
		String userId = user.userId();
		String group = "NEW_REACTION:post:ngroup-" + System.nanoTime();

		react(userId, group, "actor_ana", "Ana");
		react(userId, group, "actor_bia", "Bia");
		react(userId, group, "actor_bia", "Bia");
		react(userId, group, "actor_caio", "Caio");
		react(userId, "NEW_REACTION:post:other-" + System.nanoTime(), "actor_ana", "Ana");

		mockMvc.perform(get("/notifications").header("X-Session-Token", user.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(2))
			.andExpect(jsonPath("$.items[1].body").value("Caio e mais 2 pessoas reagiram ao seu post."))
			.andExpect(jsonPath("$.items[1].actorCount").value(3))
			.andExpect(jsonPath("$.items[1].actorId").value("actor_caio"))
			.andExpect(jsonPath("$.items[1].href").value("/post?reacoes=1"))
			.andExpect(jsonPath("$.items[0].href").value("/post"));
		// One push per notification, not per reaction.
		verify(pushSender, times(2)).sendToUser(eq(userId), any());

		mockMvc.perform(post("/notifications/read-all").header("X-Session-Token", user.token())
				.header("Idempotency-Key", "ngroup-read-" + userId))
			.andExpect(status().isOk());
		react(userId, group, "actor_davi", "Davi");
		mockMvc.perform(get("/notifications").header("X-Session-Token", user.token()))
			.andExpect(jsonPath("$.items.length()").value(3))
			.andExpect(jsonPath("$.items[0].body").value("Davi reagiu ao seu post."));
	}

	/**
	 * Joining the unread notification is a read-then-write of its count. Concurrent joins (messages of
	 * one conversation arriving together) all read the same count and the last write won.
	 */
	@Test
	void concurrentJoinsOfOneGroupCountEveryEvent() throws Exception {
		String userId = new TestAuthHelper(mockMvc, objectMapper).authenticate("ngroup_counted").userId();
		String group = "conversation:ngroup-" + System.nanoTime();
		int joins = 8;
		count(userId, group, "actor_first");

		var pool = java.util.concurrent.Executors.newFixedThreadPool(joins);
		try {
			var start = new java.util.concurrent.CountDownLatch(1);
			var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
			for (int i = 0; i < joins; i++) {
				String actor = "actor_" + i;
				futures.add(pool.submit(() -> {
					start.await();
					count(userId, group, actor);
					return null;
				}));
			}
			start.countDown();
			for (var future : futures) {
				future.get(60, java.util.concurrent.TimeUnit.SECONDS);
			}
		}
		finally {
			pool.shutdownNow();
		}

		var rows = jdbc.queryForList("select actor_count, body from notifications where user_id = ? and group_key = ?",
			userId, group);
		org.assertj.core.api.Assertions.assertThat(rows).hasSize(1);
		org.assertj.core.api.Assertions.assertThat(rows.get(0).get("actor_count")).isEqualTo(joins + 1);
		org.assertj.core.api.Assertions.assertThat(rows.get(0).get("body")).isEqualTo((joins + 1) + " novas mensagens");
	}

	private void count(String userId, String group, String actorId) {
		notifications.createCounted(userId, "NEW_MESSAGE", group, actorId, "Nova mensagem", "Oi",
			count -> count + " novas mensagens", "/mensagens", "Responder");
	}

	/** In its own transaction: the push listener runs after commit. */
	private void react(String userId, String group, String actorId, String actorName) {
		transactions.executeWithoutResult(status -> notifications.createGrouped(userId, "NEW_REACTION", group, actorId,
			"Nova reação", actorName + " reagiu ao seu post.",
			count -> NotificationOutboxEventHandler.othersReacted(actorName, count, "ao seu post"), "/post",
			"/post?reacoes=1", "Ver"));
	}
}
