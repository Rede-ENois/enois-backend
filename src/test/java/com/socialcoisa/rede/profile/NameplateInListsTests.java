package com.socialcoisa.rede.profile;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * The equipped nameplate travels with the name style in every list that shows people, and those lists
 * load their authors in one query instead of one per row.
 */
@SpringBootTest
@AutoConfigureMockMvc
class NameplateInListsTests {

	private static final String PLATE = "placa-mata";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@MockitoSpyBean
	private UserProfileRepository profiles;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	/** Fixture: equips the plate straight on the profile (buying it is covered by the shop tests). */
	private TestAuthHelper.AuthSession withPlate(String name) throws Exception {
		var user = auth.authenticate(name);
		UserProfile profile = profiles.findById(user.userId()).orElseThrow();
		profile.updateProfileCosmetics(null, PLATE, null, null, Instant.now());
		profiles.save(profile);
		return user;
	}

	/** The viewer's own profile is read once per request; any other id read alone means a query per row. */
	private void verifyNoPerRowLookup(TestAuthHelper.AuthSession viewer) {
		verify(profiles, never()).findById(argThat((String id) -> !id.equals(viewer.userId())));
	}

	private String created(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private String send(TestAuthHelper.AuthSession user, String path, String key, Map<String, ?> body)
			throws Exception {
		return created(mockMvc.perform(post(path)
				.header("X-Session-Token", user.token())
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(body)))
			.andExpect(status().isCreated())
			.andReturn());
	}

	@Test
	void groupListsCarryThePlateAndLoadAuthorsInOneQuery() throws Exception {
		var owner = withPlate("plate-group-owner");
		String groupId = send(owner, "/groups", "plate-group", Map.of("name", "Grupo Plaquinha", "joinMode", "OPEN"));
		String topicId = send(owner, "/groups/" + groupId + "/topics", "plate-topic", Map.of("name", "Geral"));
		String postId = send(owner, "/groups/" + groupId + "/posts", "plate-gpost",
			Map.of("topicId", topicId, "content", "Post com plaquinha", "mediaIds", List.of()));
		send(owner, "/groups/" + groupId + "/posts/" + postId + "/comments", "plate-gcomment",
			Map.of("content", "Comentario com plaquinha"));
		var member = auth.authenticate("plate-group-member");
		mockMvc.perform(post("/groups/" + groupId + "/join")
				.header("X-Session-Token", member.token())
				.header("Idempotency-Key", "plate-join"))
			.andExpect(status().isOk());
		send(member, "/groups/" + groupId + "/posts/" + postId + "/comments", "plate-gcomment-2",
			Map.of("content", "Comentario sem plaquinha"));

		clearInvocations(profiles);
		mockMvc.perform(get("/groups/" + groupId + "/members").header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(2))
			.andExpect(jsonPath("$.items[?(@.userId == '" + owner.userId() + "')].nameStyle.nameplate").value(PLATE));
		mockMvc.perform(get("/groups/" + groupId + "/posts").header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].authorStyle.nameplate").value(PLATE));
		mockMvc.perform(get("/groups/" + groupId + "/posts/" + postId + "/comments")
				.header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.content == 'Comentario com plaquinha')].authorStyle.nameplate")
				.value(PLATE));
		verifyNoPerRowLookup(owner);
	}

	@Test
	void feedCommentsCarryThePlateAndLoadAuthorsInOneQuery() throws Exception {
		var author = withPlate("plate-feed-author");
		String postId = send(author, "/feed/posts", "plate-post",
			Map.of("topic", "TECNOLOGIA", "type", "INSIGHT", "content", "Post do feed com plaquinha"));
		send(author, "/feed/posts/" + postId + "/comments", "plate-comment", Map.of("content", "Comentario"));
		var other = auth.authenticate("plate-feed-other");
		send(other, "/feed/posts/" + postId + "/comments", "plate-comment-2", Map.of("content", "Outro comentario"));

		clearInvocations(profiles);
		mockMvc.perform(get("/feed/posts/" + postId + "/comments").header("X-Session-Token", author.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.content == 'Comentario')].authorStyle.nameplate").value(PLATE));
		verifyNoPerRowLookup(author);
	}

	@Test
	void communityMembersCarryTheNameStyle() throws Exception {
		var owner = withPlate("plate-community-owner");
		String communityId = send(owner, "/communities", "plate-community",
			Map.of("name", "Comunidade Plaquinha", "description", "Teste", "topic", "TECNOLOGIA"));

		mockMvc.perform(get("/communities/" + communityId + "/members"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].nameStyle.nameplate").value(PLATE))
			.andExpect(jsonPath("$.items[0].nameStyle.theme").doesNotExist());
	}
}
