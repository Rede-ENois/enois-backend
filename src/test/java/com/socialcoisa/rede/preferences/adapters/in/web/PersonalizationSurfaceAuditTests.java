package com.socialcoisa.rede.preferences.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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

/**
 * Auditoria das superfícies que listam conteúdo depois das preferências novas (Fase F):
 * a preferência filtra DESCOBERTA, não apaga o que a pessoa guardou nem o evento que ela
 * provocou. Salvos seguem o que foi salvo e notificações seguem a publicação, inclusive
 * quando a publicação é BUSINESS.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PersonalizationSurfaceAuditTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private TestAuthHelper auth;
	/** O H2 dos testes é compartilhado entre classes: comunidade criada aqui é apagada aqui. */
	private final java.util.List<String[]> createdCommunities = new java.util.ArrayList<>();

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	@AfterEach
	void cleanUp() throws Exception {
		for (String[] created : createdCommunities) {
			mockMvc.perform(delete("/communities/" + created[0])
					.header("X-Session-Token", created[1])
					.header("Idempotency-Key", "cleanup-" + created[0]));
		}
		createdCommunities.clear();
	}

	private String createTechnologyCommunity(TestAuthHelper.AuthSession owner) throws Exception {
		MvcResult result = mockMvc.perform(post("/communities")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "audit-comm-" + System.nanoTime())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"name", "Audit Tech", "description", "Comunidade de teste", "topic", "TECNOLOGIA"))))
			.andExpect(status().isCreated())
			.andReturn();
		String id = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
		createdCommunities.add(new String[] { id, owner.token() });
		return id;
	}

	private String publish(TestAuthHelper.AuthSession author, String type, String communityId) throws Exception {
		Map<String, Object> body = new java.util.HashMap<>(Map.of(
			"type", type, "content", "Conteúdo de tecnologia para auditoria.",
			"tags", List.of(), "mediaIds", List.of()));
		if (communityId != null) {
			body.put("communityId", communityId);
		} else {
			body.put("topic", "TECNOLOGIA");
		}
		MvcResult result = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "audit-post-" + System.nanoTime())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(body)))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private void blockTopic(TestAuthHelper.AuthSession user, String topic) throws Exception {
		mockMvc.perform(put("/me/preferences")
				.header("X-Session-Token", user.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("blockedTopics", List.of(topic)))))
			.andExpect(status().isOk());
	}

	@Test
	void savedPostsFollowWhatWasSavedEvenWhenTheTopicIsBlocked() throws Exception {
		var author = auth.authenticate("audit_author");
		var reader = auth.authenticate("audit_reader");

		String communityId = createTechnologyCommunity(author);
		String postId = publish(author, "INSIGHT", communityId);

		mockMvc.perform(post("/feed/posts/" + postId + "/saves")
				.header("X-Session-Token", reader.token())
				.header("Idempotency-Key", "audit-save-" + postId))
			.andExpect(status().isOk());

		blockTopic(reader, "TECNOLOGIA");

		// Descoberta: o assunto bloqueado some do feed.
		mockMvc.perform(get("/feed").param("tab", "ALL").header("X-Session-Token", reader.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.id == '" + postId + "')]").isEmpty());

		// Salvos: continua lá, porque a pessoa guardou de propósito. Preferência não é expurgo.
		mockMvc.perform(get("/feed/saved").header("X-Session-Token", reader.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.id == '" + postId + "')]").isNotEmpty());
	}

	@Test
	void interactionNotificationFollowsTheBusinessPost() throws Exception {
		var author = auth.authenticate("audit_biz_author");
		var reader = auth.authenticate("audit_biz_reader");

		mockMvc.perform(post("/me/business-profile")
				.header("X-Session-Token", author.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"username", "audit.biz.pro", "name", "Audit Profissional"))))
			.andExpect(status().isOk());
		mockMvc.perform(patch("/me/business-profile")
				.header("X-Session-Token", author.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("headline", "Headline audit"))))
			.andExpect(status().isOk());
		mockMvc.perform(post("/me/business-profile/activate").header("X-Session-Token", author.token()))
			.andExpect(status().isOk());

		String businessId = publish(author, "BUSINESS", null);

		mockMvc.perform(post("/feed/posts/" + businessId + "/comments")
				.header("X-Session-Token", reader.token())
				.header("Idempotency-Key", "audit-comment-" + businessId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"content", "Comentário no post corporativo.", "mediaIds", List.of()))))
			.andExpect(status().isCreated());

		// A notificação é criada pela outbox (worker de 5s) e aponta para a publicação,
		// independente da identidade de autoria.
		JsonNode found = null;
		for (int attempt = 0; attempt < 20 && found == null; attempt++) {
			MvcResult result = mockMvc.perform(get("/notifications")
					.header("X-Session-Token", author.token()))
				.andExpect(status().isOk())
				.andReturn();
			for (JsonNode item : objectMapper.readTree(result.getResponse().getContentAsString())
				.get("items")) {
				if ("NEW_COMMENT".equals(item.path("kind").asText())
						&& item.path("href").asText().contains(businessId)) {
					found = item;
					break;
				}
			}
			if (found == null) {
				Thread.sleep(500);
			}
		}
		assertThat(found).as("notificação de comentário no post BUSINESS").isNotNull();
		assertThat(found.path("href").asText()).isEqualTo("/api/feed/posts/" + businessId);
	}

	@Test
	void publicSearchNeverReturnsPosts() throws Exception {
		var author = auth.authenticate("audit_search_author");
		mockMvc.perform(post("/me/business-profile")
				.header("X-Session-Token", author.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"username", "audit.search.pro", "name", "Busca Profissional"))))
			.andExpect(status().isOk());
		mockMvc.perform(patch("/me/business-profile")
				.header("X-Session-Token", author.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("headline", "Headline da busca"))))
			.andExpect(status().isOk());
		mockMvc.perform(post("/me/business-profile/activate").header("X-Session-Token", author.token()))
			.andExpect(status().isOk());
		String businessId = publish(author, "BUSINESS", null);

		MvcResult result = mockMvc.perform(get("/search").param("q", "auditoria"))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode items = objectMapper.readTree(result.getResponse().getContentAsString()).get("items");

		// A busca pública cobre usuários/comunidades/vagas/eventos/mentores, nunca posts.
		// Assim o BUSINESS não vaza para o contexto social pela busca.
		for (JsonNode item : items) {
			assertThat(item.path("type").asText()).isNotEqualTo("posts");
			assertThat(item.path("id").asText()).isNotEqualTo(businessId);
		}
	}
}
