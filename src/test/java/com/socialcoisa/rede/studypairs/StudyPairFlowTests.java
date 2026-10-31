package com.socialcoisa.rede.studypairs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.socialcoisa.rede.network.application.BlockService;
import com.socialcoisa.rede.network.application.MuteService;
import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestAuthHelper.AuthSession;
import com.socialcoisa.rede.studypairs.application.StudyPairService;
import com.socialcoisa.rede.studypairs.domain.StudyGoal;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class StudyPairFlowTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private BlockService blocks;

	@Autowired
	private MuteService mutes;

	@Autowired
	private StudyPairService service;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	@Test
	void goalKeyFoldsCaseAccentsAndPunctuation() {
		assertThat(StudyGoal.key("  Aprender RÚST!! ")).isEqualTo("aprender-rust");
		assertThat(StudyGoal.key("aprender rust")).isEqualTo("aprender-rust");
		assertThat(StudyGoal.key("?!")).isEmpty();
		assertThat(StudyGoal.weekKey(LocalDate.of(2026, 10, 30))).isEqualTo("2026-W44");
		assertThat(StudyGoal.weekKey(LocalDate.of(2027, 1, 1))).isEqualTo("2026-W53");
	}

	@Test
	void matchesOnTheSameNormalizedGoalAndNotifiesBoth() throws Exception {
		AuthSession a = auth.authenticate("sp-match-a");
		AuthSession b = auth.authenticate("sp-match-b");

		requestPair(a, "Aprender Rust sp-match").andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("OPEN"));
		String pairId = body(requestPair(b, "aprender rúst SP-MATCH").andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("MATCHED"))).get("pairId").asText();

		assertThat(notifications(a.userId(), "STUDY_PAIR_MATCHED")).isEqualTo(1);
		assertThat(notifications(b.userId(), "STUDY_PAIR_MATCHED")).isEqualTo(1);
		me(a).andExpect(jsonPath("$.openRequest").doesNotExist())
			.andExpect(jsonPath("$.pairs[0].id").value(pairId))
			.andExpect(jsonPath("$.pairs[0].goal").value("Aprender Rust sp-match"))
			.andExpect(jsonPath("$.pairs[0].partner.id").value(b.userId()))
			.andExpect(jsonPath("$.pairs[0].myCheckinThisWeek").value(false));
	}

	@Test
	void matchesFifoAndNeverAcrossABlock() throws Exception {
		AuthSession first = auth.authenticate("sp-fifo-first");
		AuthSession blocked = auth.authenticate("sp-fifo-blocked");
		AuthSession third = auth.authenticate("sp-fifo-third");
		blocks.block(first.userId(), blocked.userId());

		requestPair(first, "goal sp-fifo").andExpect(jsonPath("$.status").value("OPEN"));
		// Blocked by the first one: stays waiting instead of pairing.
		requestPair(blocked, "goal sp-fifo").andExpect(jsonPath("$.status").value("OPEN"));
		requestPair(third, "goal sp-fifo").andExpect(jsonPath("$.status").value("MATCHED"));

		me(third).andExpect(jsonPath("$.pairs[0].partner.id").value(first.userId()));
		me(blocked).andExpect(jsonPath("$.openRequest.goal").value("goal sp-fifo"))
			.andExpect(jsonPath("$.pairs.length()").value(0));
	}

	@Test
	void minorsCannotRequestAndAreNeverMatched() throws Exception {
		AuthSession minor = auth.authenticate("sp-minor-a");
		setBirthDate(minor.userId(), LocalDate.now().minusYears(15));
		requestPair(minor, "goal sp-minor").andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("STUDY_PAIRS_ADULTS_ONLY"));

		// A request left in the queue by someone who is now known to be a minor is skipped.
		AuthSession waiting = auth.authenticate("sp-minor-waiting");
		AuthSession adult = auth.authenticate("sp-minor-adult");
		requestPair(waiting, "goal sp-minor").andExpect(jsonPath("$.status").value("OPEN"));
		setBirthDate(waiting.userId(), LocalDate.now().minusYears(16));
		requestPair(adult, "goal sp-minor").andExpect(jsonPath("$.status").value("OPEN"));
	}

	@Test
	void oneOpenRequestAtATimeAndCancel() throws Exception {
		AuthSession a = auth.authenticate("sp-one-a");
		requestPair(a, "goal sp-one").andExpect(status().isCreated());
		requestPair(a, "other goal sp-one").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("STUDY_REQUEST_ALREADY_OPEN"));

		mockMvc.perform(delete("/study-pairs/requests/current").header("X-Session-Token", a.token())
				.header("Idempotency-Key", key()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
		mockMvc.perform(delete("/study-pairs/requests/current").header("X-Session-Token", a.token())
				.header("Idempotency-Key", key()))
			.andExpect(status().isNotFound());
		requestPair(a, "other goal sp-one").andExpect(status().isCreated());
	}

	@Test
	void blankGoalAndUnknownTopicAreRejected() throws Exception {
		AuthSession a = auth.authenticate("sp-invalid-a");
		requestPair(a, " ?! ").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("STUDY_GOAL_REQUIRED"));
		mockMvc.perform(post("/study-pairs/requests").header("X-Session-Token", a.token())
				.header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
				.content(json(Map.of("goal", "goal sp-invalid", "topic", "NOPE"))))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_TOPIC"));
	}

	@Test
	void atMostTwoActivePairs() throws Exception {
		AuthSession a = auth.authenticate("sp-limit-a");
		pair(a, auth.authenticate("sp-limit-b"), "goal sp-limit-1");
		pair(a, auth.authenticate("sp-limit-c"), "goal sp-limit-2");
		requestPair(a, "goal sp-limit-3").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("STUDY_PAIR_LIMIT"));
	}

	@Test
	void checkinOncePerWeekEditableAndNotifiesOnce() throws Exception {
		AuthSession a = auth.authenticate("sp-checkin-a");
		AuthSession b = auth.authenticate("sp-checkin-b");
		String pairId = pair(a, b, "goal sp-checkin");

		checkin(a, pairId, "estudei ownership").andExpect(status().isOk())
			.andExpect(jsonPath("$.note").value("estudei ownership"));
		checkin(a, pairId, "estudei ownership e borrowing").andExpect(status().isOk());

		assertThat(notifications(b.userId(), "STUDY_PAIR_CHECKIN")).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from study_checkins where pair_id = ? and user_id = ?",
			Integer.class, pairId, a.userId())).isEqualTo(1);
		me(b).andExpect(jsonPath("$.pairs[0].partnerCheckinThisWeek").value(true))
			.andExpect(jsonPath("$.pairs[0].myCheckinThisWeek").value(false))
			.andExpect(jsonPath("$.pairs[0].checkins[0].note").value("estudei ownership e borrowing"));

		checkin(a, pairId, "abc ".repeat(126)).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("STUDY_NOTE_TOO_LONG"));
	}

	@Test
	void nonMembersAreForbidden() throws Exception {
		AuthSession a = auth.authenticate("sp-member-a");
		AuthSession b = auth.authenticate("sp-member-b");
		AuthSession outsider = auth.authenticate("sp-member-out");
		String pairId = pair(a, b, "goal sp-member");

		checkin(outsider, pairId, "oi").andExpect(status().isForbidden());
		end(outsider, pairId).andExpect(status().isForbidden());
	}

	@Test
	void eitherMemberEndsThePairAndThePartnerIsTold() throws Exception {
		AuthSession a = auth.authenticate("sp-end-a");
		AuthSession b = auth.authenticate("sp-end-b");
		String pairId = pair(a, b, "goal sp-end");

		end(b, pairId).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ENDED"));
		assertThat(notifications(a.userId(), "STUDY_PAIR_ENDED")).isEqualTo(1);
		end(a, pairId).andExpect(status().isConflict());
		checkin(a, pairId, "ainda aqui").andExpect(status().isConflict());
		me(a).andExpect(jsonPath("$.pairs.length()").value(0));
	}

	@Test
	void reminderGoesOnlyToMembersWithoutThisWeeksCheckin() throws Exception {
		AuthSession a = auth.authenticate("sp-remind-a");
		AuthSession b = auth.authenticate("sp-remind-b");
		String pairId = pair(a, b, "goal sp-remind");
		checkin(a, pairId, "feito").andExpect(status().isOk());

		service.sendWeeklyReminders();
		// A second run in the same week (another replica, a restart) sends nothing new.
		service.sendWeeklyReminders();

		assertThat(notifications(a.userId(), "STUDY_PAIR_REMINDER")).isZero();
		assertThat(notifications(b.userId(), "STUDY_PAIR_REMINDER")).isEqualTo(1);
	}

	@Test
	void blockAfterMatchEndsThePairQuietly() throws Exception {
		AuthSession a = auth.authenticate("sp-postblock-a");
		AuthSession b = auth.authenticate("sp-postblock-b");
		String pairId = pair(a, b, "goal sp-postblock");
		blocks.block(a.userId(), b.userId());

		checkin(b, pairId, "oi").andExpect(status().isConflict());
		me(b).andExpect(jsonPath("$.pairs.length()").value(0));
		assertThat(notifications(b.userId(), "STUDY_PAIR_ENDED")).isZero();
	}

	@Test
	void aMuteInEitherDirectionPreventsPairing() throws Exception {
		AuthSession muter = auth.authenticate("sp-mute-muter");
		AuthSession muted = auth.authenticate("sp-mute-muted");
		mutes.mute(muter.userId(), muted.userId());

		// The muter waits; the muted one asks later and must not be paired with them.
		requestPair(muter, "goal sp-mute").andExpect(jsonPath("$.status").value("OPEN"));
		requestPair(muted, "goal sp-mute").andExpect(jsonPath("$.status").value("OPEN"));
	}

	@Test
	void aMuteAfterMatchEndsThePairQuietly() throws Exception {
		AuthSession a = auth.authenticate("sp-postmute-a");
		AuthSession b = auth.authenticate("sp-postmute-b");
		String pairId = pair(a, b, "goal sp-postmute");
		mutes.mute(b.userId(), a.userId());

		// The muted side (a) is the one asking: the check must look both ways.
		me(a).andExpect(jsonPath("$.pairs.length()").value(0));
		checkin(a, pairId, "oi").andExpect(status().isConflict());
		assertThat(notifications(a.userId(), "STUDY_PAIR_ENDED")).isZero();
	}

	@Test
	void disabledModuleRejectsRequestsPausesMatchingAndSilencesReminders() throws Exception {
		AuthSession off = auth.authenticate("sp-off-a");
		disableModule(off);
		requestPair(off, "goal sp-off").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("STUDY_PAIRS_DISABLED"));

		// A request opened before turning the module off is paused: nobody is matched with it.
		AuthSession waiting = auth.authenticate("sp-off-waiting");
		AuthSession other = auth.authenticate("sp-off-other");
		requestPair(waiting, "goal sp-off-2").andExpect(jsonPath("$.status").value("OPEN"));
		disableModule(waiting);
		requestPair(other, "goal sp-off-2").andExpect(jsonPath("$.status").value("OPEN"));

		// An active pair keeps going, but the member who turned it off gets no reminder.
		AuthSession a = auth.authenticate("sp-off-pair-a");
		AuthSession b = auth.authenticate("sp-off-pair-b");
		pair(a, b, "goal sp-off-3");
		disableModule(a);
		service.sendWeeklyReminders();
		assertThat(notifications(a.userId(), "STUDY_PAIR_REMINDER")).isZero();
		assertThat(notifications(b.userId(), "STUDY_PAIR_REMINDER")).isEqualTo(1);
	}

	@Test
	void popularGoalsCarryNoPersonalData() throws Exception {
		AuthSession a = auth.authenticate("sp-popular-a");
		requestPair(a, "Goal SP-Popular").andExpect(status().isCreated());

		String response = mockMvc.perform(get("/study-pairs/goals/popular").header("X-Session-Token", a.token()))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		JsonNode items = objectMapper.readTree(response).get("items");
		JsonNode mine = null;
		for (JsonNode item : items) {
			if ("goal-sp-popular".equals(item.get("goalKey").asText())) {
				mine = item;
			}
		}
		assertThat(mine).isNotNull();
		assertThat(mine.get("openRequests").asInt()).isEqualTo(1);
		assertThat(response).doesNotContain(a.userId()).doesNotContain("sp-popular-a");
	}

	private String pair(AuthSession first, AuthSession second, String goal) throws Exception {
		requestPair(first, goal).andExpect(jsonPath("$.status").value("OPEN"));
		return body(requestPair(second, goal).andExpect(jsonPath("$.status").value("MATCHED"))).get("pairId").asText();
	}

	private void disableModule(AuthSession session) throws Exception {
		mockMvc.perform(put("/me/preferences").header("X-Session-Token", session.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(json(Map.of("disabledModules", java.util.List.of(StudyPairService.MODULE)))))
			.andExpect(status().isOk());
	}

	@Test
	void searchFindsOpenGoalsWithoutRevealingWhoAsked() throws Exception {
		AuthSession owner = auth.authenticate("sp-search-owner");
		AuthSession viewer = auth.authenticate("sp-search-viewer");

		requestPair(owner, "Estudar Kotlin para Android sp-search").andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("OPEN"));

		var result = body(mockMvc.perform(get("/study-pairs/search").param("q", "kotlin")
				.header("X-Session-Token", viewer.token()))
			.andExpect(status().isOk())).get("items");
		assertThat(result.size()).isEqualTo(1);
		assertThat(result.get(0).get("goal").asText()).contains("Kotlin");
		// O resultado é anônimo: não sai id de pedido nem de quem pediu.
		assertThat(result.get(0).has("userId")).isFalse();
		assertThat(result.get(0).has("id")).isFalse();

		// O próprio pedido não aparece para quem buscou.
		var mine = body(mockMvc.perform(get("/study-pairs/search").param("q", "kotlin")
				.header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())).get("items");
		assertThat(mine.size()).isZero();

		// Termo curto não busca nada (evita varrer a tabela a cada tecla).
		var shortTerm = body(mockMvc.perform(get("/study-pairs/search").param("q", "k")
				.header("X-Session-Token", viewer.token()))
			.andExpect(status().isOk())).get("items");
		assertThat(shortTerm.size()).isZero();

		// Quem foi bloqueado não aparece na busca.
		AuthSession blocker = auth.authenticate("sp-search-blocker");
		requestPair(blocker, "Estudar Elixir sp-search").andExpect(status().isCreated());
		blocks.block(viewer.userId(), blocker.userId());
		var hidden = body(mockMvc.perform(get("/study-pairs/search").param("q", "elixir")
				.header("X-Session-Token", viewer.token()))
			.andExpect(status().isOk())).get("items");
		assertThat(hidden.size()).isZero();
	}

	private ResultActions requestPair(AuthSession session, String goal) throws Exception {
		Map<String, Object> payload = new HashMap<>();
		payload.put("goal", goal);
		return mockMvc.perform(post("/study-pairs/requests").header("X-Session-Token", session.token())
			.header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON).content(json(payload)));
	}

	private ResultActions checkin(AuthSession session, String pairId, String note) throws Exception {
		return mockMvc.perform(post("/study-pairs/" + pairId + "/checkins").header("X-Session-Token", session.token())
			.header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
			.content(json(Map.of("note", note))));
	}

	private ResultActions end(AuthSession session, String pairId) throws Exception {
		return mockMvc.perform(post("/study-pairs/" + pairId + "/end").header("X-Session-Token", session.token())
			.header("Idempotency-Key", key()));
	}

	private ResultActions me(AuthSession session) throws Exception {
		return mockMvc.perform(get("/study-pairs/me").header("X-Session-Token", session.token()))
			.andExpect(status().isOk());
	}

	private int notifications(String userId, String kind) {
		return jdbc.queryForObject("select count(*) from notifications where user_id = ? and kind = ?", Integer.class,
			userId, kind);
	}

	private void setBirthDate(String userId, LocalDate date) {
		jdbc.update("update users set birth_date = ? where id = ?", date.toString(), userId);
	}

	private JsonNode body(ResultActions actions) throws Exception {
		return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
	}

	private String json(Object value) throws Exception {
		return objectMapper.writeValueAsString(value);
	}

	private static String key() {
		return "sp-" + UUID.randomUUID();
	}
}
