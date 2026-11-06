package com.socialcoisa.rede.profile.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestAuthHelper.AuthSession;

/** Public connections/followers of a profile follow the same visibility rules as the profile read. */
@SpringBootTest
@AutoConfigureMockMvc
class ProfileNetworkListTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private TestAuthHelper auth;
	private String suffix;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
		suffix = String.valueOf(System.nanoTime());
	}

	@Test
	void bannedProfileHasNoPublicNetwork() throws Exception {
		AuthSession owner = auth.authenticate("pnet_owner_" + suffix);
		AuthSession friend = auth.authenticate("pnet_friend_" + suffix);
		connect(owner, friend);
		String ownerUsername = username(owner);
		assertThat(listed(get("/users/{username}/connections", ownerUsername))).containsExactly(friend.userId());

		ban(ownerUsername);

		mockMvc.perform(get("/users/{username}/connections", ownerUsername)).andExpect(status().isNotFound());
		mockMvc.perform(get("/users/{username}/followers", ownerUsername)).andExpect(status().isNotFound());
		mockMvc.perform(get("/users/{username}/experiences", ownerUsername)).andExpect(status().isNotFound());
		mockMvc.perform(get("/users/by-id/{id}", owner.userId()).header("X-Session-Token", friend.token()))
			.andExpect(status().isNotFound());
	}

	@Test
	void bannedPeopleLeaveOtherProfilesNetwork() throws Exception {
		AuthSession owner = auth.authenticate("pnet_list_" + suffix);
		AuthSession kept = auth.authenticate("pnet_kept_" + suffix);
		AuthSession banned = auth.authenticate("pnet_gone_" + suffix);
		connect(kept, owner);
		connect(banned, owner);

		ban(username(banned));

		String ownerUsername = username(owner);
		assertThat(listed(get("/users/{username}/connections", ownerUsername))).containsExactly(kept.userId());
		assertThat(listed(get("/users/{username}/followers", ownerUsername))).containsExactly(kept.userId());
	}

	@Test
	void limitIsFilledPastLeftOutPeople() throws Exception {
		AuthSession owner = auth.authenticate("pnet_fill_" + suffix);
		AuthSession older = auth.authenticate("pnet_older_" + suffix);
		AuthSession newer = auth.authenticate("pnet_newer_" + suffix);
		connect(owner, older);
		connect(owner, newer);
		String ownerUsername = username(owner);
		assertThat(listed(get("/users/{username}/connections", ownerUsername).param("limit", "1")))
			.containsExactly(newer.userId());

		ban(username(newer));

		assertThat(listed(get("/users/{username}/connections", ownerUsername).param("limit", "1")))
			.as("the newest connection is banned, so the next database page fills the slot")
			.containsExactly(older.userId());
	}

	@Test
	void viewerBlocksHidePeopleAndTheProfile() throws Exception {
		AuthSession owner = auth.authenticate("pnet_viewed_" + suffix);
		AuthSession friend = auth.authenticate("pnet_blocked_" + suffix);
		AuthSession viewer = auth.authenticate("pnet_viewer_" + suffix);
		AuthSession blockedViewer = auth.authenticate("pnet_bviewer_" + suffix);
		connect(owner, friend);
		block(viewer, friend);
		block(owner, blockedViewer);
		String ownerUsername = username(owner);

		assertThat(listed(get("/users/{username}/connections", ownerUsername))).containsExactly(friend.userId());
		assertThat(listed(get("/users/{username}/connections", ownerUsername)
			.header("X-Session-Token", viewer.token()))).isEmpty();
		mockMvc.perform(get("/users/{username}/connections", ownerUsername)
				.header("X-Session-Token", blockedViewer.token()))
			.andExpect(status().isNotFound());
	}

	private List<String> listed(MockHttpServletRequestBuilder request) throws Exception {
		JsonNode body = objectMapper.readTree(mockMvc.perform(request)
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString());
		List<String> ids = new ArrayList<>();
		body.forEach(item -> ids.add(item.path("id").asString()));
		return ids;
	}

	private void connect(AuthSession requester, AuthSession receiver) throws Exception {
		mockMvc.perform(post("/network/connections/{target}", receiver.userId())
				.header("X-Session-Token", requester.token())
				.header("Idempotency-Key", "pnet-req-" + requester.userId() + receiver.userId()))
			.andExpect(status().isAccepted());
		mockMvc.perform(put("/network/connections/{target}/accept", requester.userId())
				.header("X-Session-Token", receiver.token())
				.header("Idempotency-Key", "pnet-acc-" + requester.userId() + receiver.userId()))
			.andExpect(status().isOk());
	}

	private void block(AuthSession blocker, AuthSession target) throws Exception {
		mockMvc.perform(post("/network/blocks/{target}", target.userId())
				.header("X-Session-Token", blocker.token())
				.header("Idempotency-Key", "pnet-block-" + blocker.userId() + target.userId()))
			.andExpect(status().isCreated());
	}

	private void ban(String username) throws Exception {
		AuthSession admin = auth.authenticate("group-admin");
		mockMvc.perform(post("/admin/users/{username}/sanction", username)
				.header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"type", "BAN", "reason", "Conta de teste banida na triagem."))))
			.andExpect(status().isOk());
	}

	private String username(AuthSession session) throws Exception {
		return objectMapper.readTree(mockMvc.perform(get("/me").header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString()).get("username").asString();
	}
}
