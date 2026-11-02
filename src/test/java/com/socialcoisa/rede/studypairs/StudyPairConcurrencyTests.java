package com.socialcoisa.rede.studypairs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestAuthHelper.AuthSession;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The two invariants of the module that only the database can hold under real concurrency: one open
 * request per person and two active pairs at most, with never the same duo paired twice.
 *
 * The requests go out at the same instant (barrier) from two threads, each in its own transaction —
 * the shape of a double tap, two devices or two replicas.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StudyPairConcurrencyTests {

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
	void twoRequestsFromTheSamePersonAtOnceKeepExactlyOneOpenRequest() throws Exception {
		AuthSession me = auth.authenticate("sp-race-self");

		List<Integer> statuses = together(concurrent(me, "primeiro objetivo sp-race-self"),
			concurrent(me, "segundo objetivo sp-race-self"));

		assertThat(statuses).containsExactlyInAnyOrder(201, 409);
		assertThat(requestRows(me.userId())).isEqualTo(1);
		assertThat(openRequests(me.userId())).isEqualTo(1);
	}

	@Test
	void twoPeopleAskingTheSameGoalAtOncePairWithTheWaitingRequestOnlyOnce() throws Exception {
		AuthSession waiting = auth.authenticate("sp-race-wait");
		AuthSession first = auth.authenticate("sp-race-first");
		AuthSession second = auth.authenticate("sp-race-second");
		assertThat(request(waiting, "objetivo sp-race-goal").status()).isEqualTo(201);

		List<Integer> statuses = together(concurrent(first, "objetivo sp-race-goal"),
			concurrent(second, "objetivo sp-race-goal"));

		assertThat(statuses).containsExactly(201, 201);
		// The waiting request left the queue exactly once, so only one pair was created for the goal.
		assertThat(jdbc.queryForObject(
			"select count(*) from study_pairs where goal_key = ? and status = 'ACTIVE'", Integer.class,
			"objetivo-sp-race-goal")).isEqualTo(1);
		assertThat(jdbc.queryForObject(
			"select count(*) from study_pair_requests where user_id = ? and status = 'MATCHED'", Integer.class,
			waiting.userId())).isEqualTo(1);
		// One of the two got the pair; the other is still in the queue waiting.
		assertThat(matchedRows(first.userId()) + matchedRows(second.userId())).isEqualTo(1);
		assertThat(openRequests(first.userId()) + openRequests(second.userId())).isEqualTo(1);
	}

	@Test
	void theDatabaseRefusesASecondActivePairBetweenTheSameTwoPeople() throws Exception {
		AuthSession a = auth.authenticate("sp-race-duo-a");
		AuthSession b = auth.authenticate("sp-race-duo-b");
		assertThat(request(a, "objetivo sp-race-duo").status()).isEqualTo(201);
		assertThat(request(b, "objetivo sp-race-duo").status()).isEqualTo(201);

		String duplicateKey = a.userId().compareTo(b.userId()) <= 0
			? a.userId() + ":" + b.userId()
			: b.userId() + ":" + a.userId();

		assertThatThrownBy(() -> jdbc.update("insert into study_pairs"
			+ " (id, user_a, user_b, goal_key, goal_text, topic, status, started_at, ended_at, active_pair_key)"
			+ " values (?, ?, ?, ?, ?, null, 'ACTIVE', current_timestamp, null, ?)",
			"sp_duplicate_" + UUID.randomUUID().toString().replace("-", ""), b.userId(), a.userId(),
			"objetivo-sp-race-duo", "objetivo sp-race-duo", duplicateKey))
			.isInstanceOf(DuplicateKeyException.class);
	}

	@Test
	void theDatabaseRefusesASecondOpenRequestForTheSamePerson() throws Exception {
		AuthSession me = auth.authenticate("sp-race-index");
		assertThat(request(me, "objetivo sp-race-index").status()).isEqualTo(201);

		assertThatThrownBy(() -> jdbc.update("insert into study_pair_requests"
			+ " (id, user_id, goal_key, goal_text, topic, status, created_at, open_user_id)"
			+ " values (?, ?, ?, ?, null, 'OPEN', current_timestamp, ?)",
			"spr_duplicate_" + UUID.randomUUID().toString().replace("-", ""), me.userId(), "outro-objetivo",
			"outro objetivo", me.userId()))
			.isInstanceOf(DuplicateKeyException.class);
		assertThat(openRequests(me.userId())).isEqualTo(1);
	}

	@Test
	void twoCheckinsAtOnceKeepOneRowAndNotifyThePartnerOnce() throws Exception {
		AuthSession a = auth.authenticate("sp-race-checkin-a");
		AuthSession b = auth.authenticate("sp-race-checkin-b");
		assertThat(request(a, "objetivo sp-race-checkin").status()).isEqualTo(201);
		Response matched = request(b, "objetivo sp-race-checkin");
		assertThat(matched.status()).isEqualTo(201);
		assertThat(matched.pairId()).isNotNull();

		// Double tap: the browser sends two writes with different Idempotency-Keys, so both reach the
		// service and only the primary key (pair, member, week) decides who inserted the row.
		List<Integer> statuses = together(() -> checkin(a, matched.pairId(), "primeira nota"),
			() -> checkin(a, matched.pairId(), "segunda nota"));

		assertThat(statuses).containsExactlyInAnyOrder(200, 200);
		assertThat(jdbc.queryForObject(
			"select count(*) from study_checkins where pair_id = ? and user_id = ?", Integer.class, matched.pairId(),
			a.userId())).isEqualTo(1);
		// The partner hears about the week once, not once per write.
		assertThat(jdbc.queryForObject(
			"select count(*) from notifications where user_id = ? and kind = 'STUDY_PAIR_CHECKIN'", Integer.class,
			b.userId())).isEqualTo(1);
	}

	@Test
	void anEndedPairFreesTheSlotAndAClosedRequestFreesTheQueue() throws Exception {
		AuthSession a = auth.authenticate("sp-race-slot-a");
		AuthSession b = auth.authenticate("sp-race-slot-b");
		AuthSession c = auth.authenticate("sp-race-slot-c");

		assertThat(request(a, "objetivo sp-race-slot-1").status()).isEqualTo(201);
		Response matched = request(b, "objetivo sp-race-slot-1");
		assertThat(matched.status()).isEqualTo(201);
		assertThat(request(a, "objetivo sp-race-slot-2").status()).isEqualTo(201);
		assertThat(request(c, "objetivo sp-race-slot-2").status()).isEqualTo(201);

		// Two active pairs: the ceiling answers with the explicit error, not a third pair.
		assertThat(request(a, "objetivo sp-race-slot-3").status()).isEqualTo(409);
		assertThat(activePairs(a.userId())).isEqualTo(2);

		mockMvc.perform(post("/study-pairs/" + matched.pairId() + "/end").header("X-Session-Token", a.token())
			.header("Idempotency-Key", key())).andReturn().getResponse().getStatus();
		assertThat(activePairs(a.userId())).isEqualTo(1);

		assertThat(request(a, "objetivo sp-race-slot-3").status()).isEqualTo(201);
		assertThat(activePairs(a.userId())).isEqualTo(1);
	}

	private int checkin(AuthSession session, String pairId, String note) throws Exception {
		return mockMvc.perform(post("/study-pairs/" + pairId + "/checkins")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", key())
				.contentType(MediaType.APPLICATION_JSON)
				.content(json(Map.of("note", note))))
			.andReturn().getResponse().getStatus();
	}

	private Callable<Integer> concurrent(AuthSession session, String goal) {
		return () -> request(session, goal).status();
	}

	private Response request(AuthSession session, String goal) throws Exception {
		MvcResult result = mockMvc.perform(post("/study-pairs/requests")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", key())
				.contentType(MediaType.APPLICATION_JSON)
				.content(json(Map.of("goal", goal))))
			.andReturn();
		int status = result.getResponse().getStatus();
		String body = result.getResponse().getContentAsString();
		String pairId = null;
		if (status == 201 && body != null && !body.isBlank()) {
			JsonNode node = objectMapper.readTree(body).path("pairId");
			pairId = node.isMissingNode() || node.isNull() ? null : node.asText();
		}
		return new Response(status, pairId);
	}

	private List<Integer> together(Callable<Integer> first, Callable<Integer> second) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(2);
		CyclicBarrier start = new CyclicBarrier(2);
		try {
			Future<Integer> one = pool.submit(() -> {
				start.await(10, TimeUnit.SECONDS);
				return first.call();
			});
			Future<Integer> two = pool.submit(() -> {
				start.await(10, TimeUnit.SECONDS);
				return second.call();
			});
			return List.of(one.get(30, TimeUnit.SECONDS), two.get(30, TimeUnit.SECONDS));
		}
		finally {
			pool.shutdownNow();
		}
	}

	private int openRequests(String userId) {
		return jdbc.queryForObject("select count(*) from study_pair_requests where user_id = ? and status = 'OPEN'",
			Integer.class, userId);
	}

	private int requestRows(String userId) {
		return jdbc.queryForObject("select count(*) from study_pair_requests where user_id = ?", Integer.class, userId);
	}

	private int matchedRows(String userId) {
		return jdbc.queryForObject("select count(*) from study_pair_requests where user_id = ? and status = 'MATCHED'",
			Integer.class, userId);
	}

	private int activePairs(String userId) {
		return jdbc.queryForObject("select count(*) from study_pairs where status = 'ACTIVE'"
			+ " and (user_a = ? or user_b = ?)", Integer.class, userId, userId);
	}

	private String json(Object value) throws Exception {
		return objectMapper.writeValueAsString(value);
	}

	private static String key() {
		return "sp-race-" + UUID.randomUUID();
	}

	private record Response(int status, String pairId) {
	}
}