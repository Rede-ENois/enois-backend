package com.socialcoisa.rede.groups;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestAuthHelper.AuthSession;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** The personal group list and the group address stay correct next to the fixed `/groups/mine` route. */
@SpringBootTest
@AutoConfigureMockMvc
class GroupMineListTests {

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
	void groupNamedAfterTheFixedRouteGetsAnAddressOfItsOwn() throws Exception {
		AuthSession owner = auth.authenticate("gmine_slug_" + suffix);
		JsonNode created = createGroup(owner, "Mine");
		String slug = created.get("slug").asString();

		assertThat(slug).isNotEqualTo("mine").startsWith("mine-");
		mockMvc.perform(get("/groups/{slug}", slug))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(created.get("id").asString()));
	}

	@Test
	void mineListsOpenMembershipsNewestFirstWithRoleAndCount() throws Exception {
		AuthSession me = auth.authenticate("gmine_me_" + suffix);
		AuthSession otherOwner = auth.authenticate("gmine_owner_" + suffix);
		AuthSession closedOwner = auth.authenticate("gmine_closed_" + suffix);
		String owned = createGroup(me, "Meu grupo " + suffix).get("id").asString();
		String joined = createGroup(otherOwner, "Grupo dos outros " + suffix).get("id").asString();
		String closed = createGroup(closedOwner, "Grupo encerrado " + suffix).get("id").asString();
		join(me, joined);
		join(me, closed);
		mockMvc.perform(post("/groups/{id}/close", closed)
				.header("X-Session-Token", closedOwner.token())
				.header("Idempotency-Key", "gmine-close-" + suffix))
			.andExpect(status().isOk());

		mockMvc.perform(get("/groups/mine").header("X-Session-Token", me.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(2))
			.andExpect(jsonPath("$.items[0].id").value(joined))
			.andExpect(jsonPath("$.items[0].viewerRole").value("MEMBER"))
			.andExpect(jsonPath("$.items[0].members").value(2))
			.andExpect(jsonPath("$.items[1].id").value(owned))
			.andExpect(jsonPath("$.items[1].viewerRole").value("OWNER"))
			.andExpect(jsonPath("$.items[1].members").value(1));

		mockMvc.perform(get("/groups/mine").param("limit", "1").header("X-Session-Token", me.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].id").value(joined));
	}

	private JsonNode createGroup(AuthSession owner, String name) throws Exception {
		return objectMapper.readTree(mockMvc.perform(post("/groups")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "gmine-create-" + owner.userId() + name.hashCode())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"name", name, "description", "grupo de teste", "joinMode", "OPEN"))))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString());
	}

	private void join(AuthSession user, String groupId) throws Exception {
		mockMvc.perform(post("/groups/{id}/join", groupId)
				.header("X-Session-Token", user.token())
				.header("Idempotency-Key", "gmine-join-" + groupId + user.userId()))
			.andExpect(status().isOk());
	}
}
