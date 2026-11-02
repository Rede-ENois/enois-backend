package com.socialcoisa.rede.onboarding.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestAuthHelper.AuthSession;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * First-run onboarding: the flag in /auth/me, the starter pack and the complete/skip writes.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OnboardingFlowTests {

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
	void newAccountSeesOnboardingAndCompletingItIsIdempotent() throws Exception {
		AuthSession user = auth.authenticate("onb-new-" + shortId());
		assertThat(me(user).get("onboardingCompleted").asBoolean()).isFalse();

		String skill = "onb-skill-" + shortId();
		Map<String, Object> body = Map.of("topics", List.of("pets"), "skills", List.of(skill));
		complete(user, body).andExpect(status().isOk()).andExpect(jsonPath("$.onboardingCompleted").value(true));
		complete(user, body).andExpect(status().isOk());

		assertThat(me(user).get("onboardingCompleted").asBoolean()).isTrue();
		assertThat(jdbc.queryForObject("select count(*) from user_skills where user_id = ? and skill = ?",
			Integer.class, user.userId(), skill)).isEqualTo(1);
		String liked = jdbc.queryForObject("select liked_topics from user_preferences where user_id = ?",
			String.class, user.userId());
		assertThat(liked).isEqualTo("PETS");
	}

	@Test
	void skipMarksCompletedWithoutChanges() throws Exception {
		AuthSession user = auth.authenticate("onb-skip-" + shortId());
		mockMvc.perform(post("/me/onboarding/skip").header("X-Session-Token", user.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.onboardingCompleted").value(true));
		assertThat(me(user).get("onboardingCompleted").asBoolean()).isTrue();
		assertThat(jdbc.queryForObject("select count(*) from user_preferences where user_id = ?", Integer.class,
			user.userId())).isZero();
	}

	@Test
	void accountsBackfilledAsCompletedSeeOnboardingAgainButRealCompletionsStay() throws Exception {
		AuthSession existing = auth.authenticate("onb-old-" + shortId());
		jdbc.update("update users set onboarding_completed_at = created_at where id = ?", existing.userId());
		assertThat(me(existing).get("onboardingCompleted").asBoolean()).isTrue();

		AuthSession skipped = auth.authenticate("onb-done-" + shortId());
		mockMvc.perform(post("/me/onboarding/skip").header("X-Session-Token", skipped.token()))
			.andExpect(status().isOk());

		jdbc.execute((java.sql.Connection connection) -> {
			ScriptUtils.executeSqlScript(connection,
				new ClassPathResource("db/migration/V224__onboarding_for_existing_accounts.sql"));
			return null;
		});

		assertThat(me(existing).get("onboardingCompleted").asBoolean()).isFalse();
		assertThat(me(skipped).get("onboardingCompleted").asBoolean()).isTrue();
	}

	@Test
	void completeFollowsTheManualJoinRules() throws Exception {
		String tag = shortId();
		AuthSession owner = auth.authenticate("onb-owner-" + tag);
		AuthSession user = auth.authenticate("onb-joiner-" + tag);
		String community = createCommunity(owner, "Onb comunidade " + tag, "PETS");
		String openGroup = createGroup(owner, "Onb aberto " + tag, "OPEN", "PETS");
		String approvalGroup = createGroup(owner, "Onb aprovacao " + tag, "APPROVAL", "PETS");

		Map<String, Object> body = Map.of(
			"communityIds", List.of(community, "community_unknown_" + tag),
			"groupIds", List.of(openGroup, approvalGroup, "group_unknown_" + tag));
		complete(user, body).andExpect(status().isOk())
			.andExpect(jsonPath("$.joinedCommunityIds.length()").value(1))
			.andExpect(jsonPath("$.joinedCommunityIds[0]").value(community))
			.andExpect(jsonPath("$.joinedGroupIds.length()").value(1))
			.andExpect(jsonPath("$.joinedGroupIds[0]").value(openGroup));
		// The retry reports the same state and creates nothing new.
		complete(user, body).andExpect(status().isOk())
			.andExpect(jsonPath("$.joinedCommunityIds[0]").value(community))
			.andExpect(jsonPath("$.joinedGroupIds[0]").value(openGroup));

		assertThat(count("community_members", "community_id", community, user.userId())).isEqualTo(1);
		assertThat(count("group_members", "group_id", openGroup, user.userId())).isEqualTo(1);
		assertThat(count("group_members", "group_id", approvalGroup, user.userId())).isZero();
		assertThat(count("group_join_requests", "group_id", approvalGroup, user.userId())).isZero();
		assertThat(jdbc.queryForObject("select count(*) from community_members where community_id = ?",
			Integer.class, "community_unknown_" + tag)).isZero();
	}

	@Test
	void connectionRequestsSkipInvalidTargetsAndAreNotDuplicated() throws Exception {
		String tag = shortId();
		AuthSession user = auth.authenticate("onb-conn-" + tag);
		AuthSession friend = auth.authenticate("onb-friend-" + tag);
		AuthSession blocker = auth.authenticate("onb-blocker-" + tag);
		block(blocker, user);

		Map<String, Object> body = Map.of("connectUserIds",
			List.of(friend.userId(), user.userId(), blocker.userId(), "user_unknown_" + tag));
		complete(user, body).andExpect(status().isOk())
			.andExpect(jsonPath("$.requestedUserIds.length()").value(1))
			.andExpect(jsonPath("$.requestedUserIds[0]").value(friend.userId()));
		complete(user, body).andExpect(status().isOk())
			.andExpect(jsonPath("$.requestedUserIds[0]").value(friend.userId()));

		assertThat(jdbc.queryForObject("select count(*) from user_connections where requester_user_id = ?",
			Integer.class, user.userId())).isEqualTo(1);
	}

	@Test
	void capsAreEnforced() throws Exception {
		AuthSession user = auth.authenticate("onb-caps-" + shortId());
		complete(user, Map.of("communityIds", ids("c", 7))).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("TOO_MANY_COMMUNITIES"));
		complete(user, Map.of("groupIds", ids("g", 7))).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("TOO_MANY_GROUPS"));
		complete(user, Map.of("connectUserIds", ids("u", 9))).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("TOO_MANY_CONNECTIONS"));
		complete(user, Map.of("skills", ids("s", 21))).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("TOO_MANY_SKILLS"));
		complete(user, Map.of("topics", List.of("NOT_A_TOPIC"))).andExpect(status().isBadRequest());
		// A rejected call changes nothing, including the flag.
		assertThat(me(user).get("onboardingCompleted").asBoolean()).isFalse();
	}

	@Test
	void starterPackExcludesJoinedConnectedBlockedMutedAndSelf() throws Exception {
		String tag = shortId();
		String skill = "onb-pack-" + tag;
		AuthSession viewer = auth.authenticate("onb-viewer-" + tag);
		AuthSession match = auth.authenticate("onb-match-" + tag);
		AuthSession pending = auth.authenticate("onb-pending-" + tag);
		AuthSession blocker = auth.authenticate("onb-blk-" + tag);
		AuthSession muted = auth.authenticate("onb-mute-" + tag);
		for (AuthSession person : List.of(viewer, match, pending, blocker, muted)) {
			complete(person, Map.of("skills", List.of(skill))).andExpect(status().isOk());
		}
		connect(viewer, pending);
		block(blocker, viewer);
		mockMvc.perform(post("/network/mutes/" + muted.userId())
				.header("X-Session-Token", viewer.token())
				.header("Idempotency-Key", "onb-mute-" + UUID.randomUUID()))
			.andExpect(status().is2xxSuccessful());

		String joined = createCommunity(match, "Onb pack membro " + tag, "RELIGIAO");
		String fresh = createCommunity(match, "Onb pack nova " + tag, "RELIGIAO");
		complete(viewer, Map.of("communityIds", List.of(joined))).andExpect(status().isOk());
		String openGroup = createGroup(match, "Onb pack aberto " + tag, "OPEN", "RELIGIAO");
		String approvalGroup = createGroup(match, "Onb pack aprovacao " + tag, "APPROVAL", "RELIGIAO");

		JsonNode pack = objectMapper.readTree(mockMvc.perform(get("/me/onboarding/starter-pack")
				.param("topics", "RELIGIAO").param("skills", skill.toUpperCase())
				.header("X-Session-Token", viewer.token()))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString());

		assertThat(ids(pack.get("communities"))).contains(fresh).doesNotContain(joined);
		assertThat(ids(pack.get("groups"))).contains(openGroup).doesNotContain(approvalGroup);
		assertThat(ids(pack.get("people"))).contains(match.userId())
			.doesNotContain(viewer.userId(), pending.userId(), blocker.userId(), muted.userId());
		JsonNode person = pack.get("people").get(0);
		assertThat(person.has("email")).isFalse();
		assertThat(person.has("username")).isTrue();
	}

	@Test
	void anonymousGets401() throws Exception {
		mockMvc.perform(get("/me/onboarding/starter-pack")).andExpect(status().isUnauthorized());
		mockMvc.perform(post("/me/onboarding/complete").contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(post("/me/onboarding/skip")).andExpect(status().isUnauthorized());
	}

	@Test
	void manualJoinOfUnknownCommunityIs404() throws Exception {
		AuthSession user = auth.authenticate("onb-404-" + shortId());
		mockMvc.perform(post("/communities/community_missing_" + shortId() + "/members")
				.header("X-Session-Token", user.token())
				.header("Idempotency-Key", "onb-404-" + UUID.randomUUID()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("COMMUNITY_NOT_FOUND"));
		mockMvc.perform(post("/network/connections/user_missing_" + shortId())
				.header("X-Session-Token", user.token())
				.header("Idempotency-Key", "onb-404-" + UUID.randomUUID()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
	}

	private JsonNode me(AuthSession user) throws Exception {
		return objectMapper.readTree(mockMvc.perform(get("/auth/me").header("X-Session-Token", user.token()))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString());
	}

	private org.springframework.test.web.servlet.ResultActions complete(AuthSession user, Map<String, Object> body)
			throws Exception {
		return mockMvc.perform(post("/me/onboarding/complete")
			.header("X-Session-Token", user.token())
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(body)));
	}

	private String createCommunity(AuthSession owner, String name, String topic) throws Exception {
		return idOf(mockMvc.perform(post("/communities")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "onb-comm-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", name, "description", "d", "topic", topic))))
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
	}

	private String createGroup(AuthSession owner, String name, String joinMode, String topic) throws Exception {
		return idOf(mockMvc.perform(post("/groups")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "onb-group-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", name, "joinMode", joinMode, "topic", topic))))
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
	}

	private void connect(AuthSession from, AuthSession to) throws Exception {
		mockMvc.perform(post("/network/connections/" + to.userId())
				.header("X-Session-Token", from.token())
				.header("Idempotency-Key", "onb-conn-" + UUID.randomUUID()))
			.andExpect(status().isAccepted());
	}

	private void block(AuthSession blocker, AuthSession target) throws Exception {
		mockMvc.perform(post("/network/blocks/" + target.userId())
				.header("X-Session-Token", blocker.token())
				.header("Idempotency-Key", "onb-block-" + UUID.randomUUID()))
			.andExpect(status().isCreated());
	}

	private int count(String table, String column, String id, String userId) {
		return jdbc.queryForObject("select count(*) from " + table + " where " + column + " = ? and user_id = ?",
			Integer.class, id, userId);
	}

	private String idOf(String json) throws Exception {
		return objectMapper.readTree(json).get("id").asText();
	}

	private static List<String> ids(JsonNode items) {
		List<String> ids = new ArrayList<>();
		items.forEach(item -> ids.add(item.get("id").asText()));
		return ids;
	}

	private static List<String> ids(String prefix, int n) {
		return new ArrayList<>(Collections.nCopies(n, prefix)).stream()
			.map(p -> p + "-" + UUID.randomUUID()).toList();
	}

	private static String shortId() {
		return UUID.randomUUID().toString().substring(0, 8);
	}
}
