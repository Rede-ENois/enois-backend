package com.socialcoisa.rede.feed.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
 * "Para voce" so mostra post em comunidade de que o leitor PARTICIPA (ou fora de comunidade) —
 * feedback do Lucas no hub: conexao postando em comunidade estranha nao pode virar vitrine no
 * feed. "Seguindo" continua mostrando tudo de quem a pessoa segue.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ForYouCommunityFilterTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private TestAuthHelper auth;

	private String joinedCommunityId;
	private String foreignCommunityId;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	@AfterEach
	void cleanUp() {
		if (joinedCommunityId != null) {
			TestDatabase.deleteCommunity(joinedCommunityId);
		}
		if (foreignCommunityId != null) {
			TestDatabase.deleteCommunity(foreignCommunityId);
		}
	}

	@Test
	void forYouHidesForeignCommunitiesAndKeepsJoinedOnes() throws Exception {
		var author = auth.authenticate("foryou_author");
		var viewer = auth.authenticate("foryou_viewer");
		becomeFriends(author, viewer);

		joinedCommunityId = createCommunity(author, "Comunidade Participada For You");
		foreignCommunityId = createCommunity(author, "Comunidade Estranha For You");
		join(viewer, joinedCommunityId);

		String postJoined = publishInCommunity(author, joinedCommunityId, "foryou-post-joined");
		String postForeign = publishInCommunity(author, foreignCommunityId, "foryou-post-foreign");
		String postGlobal = publish(author, "Post fora de comunidade foryou.", "foryou-post-global");

		// Para voce: entra o post na comunidade participada e o fora de comunidade; o da comunidade
		// estranha sai.
		String forYou = feed(viewer, "FOR_YOU");
		assertThatIds(forYou).contains(postJoined, postGlobal).doesNotContain(postForeign);

		// Seguindo: tudo de quem a pessoa segue, sem filtro de comunidade.
		String following = feed(viewer, "FOLLOWING");
		assertThatIds(following).contains(postJoined, postForeign, postGlobal);
	}

	@Test
	void authorSeesOwnPostInForeignCommunityOnlyViaCommunityFeed() throws Exception {
		var author = auth.authenticate("foryou_author2");

		foreignCommunityId = createCommunity(author, "Comunidade Autora For You");
		String postForeign = publishInCommunity(author, foreignCommunityId, "foryou2-post");
		String postGlobal = publish(author, "Post global do autor foryou2.", "foryou2-post-global");

		// O autor E membro da comunidade onde postou, entao o post entra no "para voce" dele.
		String forYou = feed(author, "FOR_YOU");
		assertThatIds(forYou).contains(postForeign, postGlobal);
	}

	private String feed(TestAuthHelper.AuthSession viewer, String tab) throws Exception {
		MvcResult result = mockMvc.perform(get("/feed")
				.header("X-Session-Token", viewer.token())
				.param("tab", tab)
				.param("limit", "20"))
			.andExpect(status().isOk()).andReturn();
		JsonNode items = objectMapper.readTree(result.getResponse().getContentAsString()).path("items");
		StringBuilder ids = new StringBuilder(",");
		for (int i = 0; i < items.size(); i++) {
			ids.append(items.get(i).path("id").asString()).append(",");
		}
		return ids.toString();
	}

	private org.assertj.core.api.AbstractCharSequenceAssert<?, String> assertThatIds(String csv) {
		return org.assertj.core.api.Assertions.assertThat(csv);
	}

	private void becomeFriends(TestAuthHelper.AuthSession a, TestAuthHelper.AuthSession b) throws Exception {
		mockMvc.perform(post("/network/connections/{target}", b.userId())
				.header("X-Session-Token", a.token())
				.header("Idempotency-Key", "foryou-req-" + a.userId()))
			.andExpect(status().isAccepted());
		mockMvc.perform(put("/network/connections/{target}/accept", a.userId())
				.header("X-Session-Token", b.token())
				.header("Idempotency-Key", "foryou-acc-" + b.userId()))
			.andExpect(status().isOk());
	}

	private void join(TestAuthHelper.AuthSession session, String communityId) throws Exception {
		mockMvc.perform(post("/communities/" + communityId + "/members")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "foryou-join-" + session.userId()))
			.andExpect(status().isOk());
	}

	private String createCommunity(TestAuthHelper.AuthSession owner, String name) throws Exception {
		String payload = objectMapper.writeValueAsString(Map.of(
			"name", name,
			"description", "Comunidade do teste do filtro do para voce.",
			"topic", "Tecnologia"));
		MvcResult result = mockMvc.perform(post("/communities")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "foryou-comm-" + name)
				.contentType(MediaType.APPLICATION_JSON)
				.content(payload))
			.andExpect(status().isCreated()).andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private String publishInCommunity(TestAuthHelper.AuthSession author, String communityId, String idem)
			throws Exception {
		String payload = objectMapper.writeValueAsString(Map.of(
			"type", "INSIGHT",
			"content", "Post de teste dentro da comunidade (" + idem + ").",
			"tags", List.of(),
			"mediaIds", List.of(),
			"communityId", communityId));
		MvcResult result = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", idem)
				.contentType(MediaType.APPLICATION_JSON)
				.content(payload))
			.andExpect(status().isCreated()).andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private String publish(TestAuthHelper.AuthSession author, String content, String idem) throws Exception {
		String payload = objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA",
			"type", "INSIGHT",
			"content", content,
			"tags", List.of(),
			"mediaIds", List.of()));
		MvcResult result = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", idem)
				.contentType(MediaType.APPLICATION_JSON)
				.content(payload))
			.andExpect(status().isCreated()).andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}
}
