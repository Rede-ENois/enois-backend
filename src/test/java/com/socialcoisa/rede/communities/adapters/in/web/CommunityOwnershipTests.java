package com.socialcoisa.rede.communities.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestDatabase;

/**
 * Propriedade, saida e transferencia de administracao das comunidades (BUG-10..13). O dono nao pode
 * sair deixando a comunidade sem administracao, e a transferencia passa o controle de verdade.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Tag("critico")
class CommunityOwnershipTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private TestAuthHelper auth;

	private String createdCommunityId;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	@AfterEach
	void cleanUp() {
		if (createdCommunityId != null) {
			TestDatabase.deleteCommunity(createdCommunityId);
		}
	}

	@Test
	void ownerCannotLeaveWhileOthersRemain() throws Exception {
		var owner = auth.authenticate("comm_own_leave_owner");
		var member = auth.authenticate("comm_own_leave_member");
		createdCommunityId = createCommunity(owner, "Comunidade com dono ativo");
		join(member, createdCommunityId, "own-join-1");

		mockMvc.perform(delete("/communities/" + createdCommunityId + "/members")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "own-leave-1"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("OWNERSHIP_TRANSFER_REQUIRED"));
	}

	@Test
	void transferMovesControlToNewOwner() throws Exception {
		var owner = auth.authenticate("comm_own_transfer_owner");
		var member = auth.authenticate("comm_own_transfer_member");
		createdCommunityId = createCommunity(owner, "Comunidade para transferir");
		join(member, createdCommunityId, "tr-join-1");

		mockMvc.perform(post("/communities/" + createdCommunityId + "/transfer")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "tr-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("newOwnerId", member.userId()))))
			.andExpect(status().isOk());

		// O novo dono edita.
		mockMvc.perform(patch("/communities/" + createdCommunityId)
				.header("X-Session-Token", member.token())
				.header("Idempotency-Key", "tr-edit-new")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", "Renomeada pelo novo dono"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Renomeada pelo novo dono"));

		// O antigo dono perdeu o controle de edicao.
		mockMvc.perform(patch("/communities/" + createdCommunityId)
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "tr-edit-old")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", "Tentativa do antigo dono"))))
			.andExpect(status().isForbidden());
	}

	@Test
	void onlyOwnerCanDeleteAndMembersAreRemoved() throws Exception {
		var owner = auth.authenticate("comm_own_del_owner");
		var member = auth.authenticate("comm_own_del_member");
		createdCommunityId = createCommunity(owner, "Comunidade que sera excluida");
		join(member, createdCommunityId, "del-join-1");

		// Nao-dono nao exclui.
		mockMvc.perform(delete("/communities/" + createdCommunityId)
				.header("X-Session-Token", member.token())
				.header("Idempotency-Key", "del-forbidden"))
			.andExpect(status().isForbidden());

		mockMvc.perform(delete("/communities/" + createdCommunityId)
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "del-owner"))
			.andExpect(status().isOk());

		// A comunidade sumiu e nao sobrou vinculo orfao.
		mockMvc.perform(get("/communities/" + createdCommunityId))
			.andExpect(status().isNotFound());
		createdCommunityId = null;
	}

	@Test
	void mineListsOnlyJoinedCommunities() throws Exception {
		var creator = auth.authenticate("comm_mine_creator");
		var joiner = auth.authenticate("comm_mine_joiner");
		createdCommunityId = createCommunity(creator, "Comunidade do criador");
		String otherId = createCommunity(joiner, "Comunidade do entrante");
		join(joiner, createdCommunityId, "mine-join-1");

		MvcResult result = mockMvc.perform(get("/communities/mine").header("X-Session-Token", joiner.token()))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode items = objectMapper.readTree(result.getResponse().getContentAsString()).get("items");

		java.util.List<String> ids = new java.util.ArrayList<>();
		for (int i = 0; i < items.size(); i++) ids.add(items.get(i).get("id").asText());
		assertThat(ids).contains(createdCommunityId, otherId);

		// O criador ve so a propria.
		MvcResult creatorView = mockMvc.perform(get("/communities/mine").header("X-Session-Token", creator.token()))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode creatorItems = objectMapper.readTree(creatorView.getResponse().getContentAsString()).get("items");
		java.util.List<String> creatorIds = new java.util.ArrayList<>();
		for (int i = 0; i < creatorItems.size(); i++) creatorIds.add(creatorItems.get(i).get("id").asText());
		assertThat(creatorIds).contains(createdCommunityId).doesNotContain(otherId);

		TestDatabase.deleteCommunity(otherId);
	}

	@Test
	void anonymousMemberListOmitsInternalUserIds() throws Exception {
		var owner = new TestAuthHelper(mockMvc, objectMapper).authenticate("own_members_owner");
		String communityId = createCommunity(owner, "Membros anonimos " + java.util.UUID.randomUUID());

		mockMvc.perform(get("/communities/" + communityId + "/members"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].username").isNotEmpty())
			.andExpect(jsonPath("$.items[0].role").value("ADMIN"))
			.andExpect(jsonPath("$.items[0].userId").doesNotExist());

		mockMvc.perform(get("/communities/" + communityId + "/members").header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].userId").value(owner.userId()));

		TestDatabase.deleteCommunity(communityId);
	}

	private String createCommunity(TestAuthHelper.AuthSession owner, String name) throws Exception {
		MvcResult result = mockMvc.perform(post("/communities")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "create-" + name.hashCode())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"name", name, "description", "Descricao da comunidade.", "topic", "TECNOLOGIA"))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private void join(TestAuthHelper.AuthSession session, String communityId, String idem) throws Exception {
		mockMvc.perform(post("/communities/" + communityId + "/members")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", idem))
			.andExpect(status().isOk());
	}
}
