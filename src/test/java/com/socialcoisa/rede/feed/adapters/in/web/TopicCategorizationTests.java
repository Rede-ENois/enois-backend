package com.socialcoisa.rede.feed.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.ObjectMapper;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

/**
 * A categorização do post (pedido da dona, 2026-10-20): o post AVULSO nasce com o assunto que o
 * AUTOR declarou no compositor — válido contra a taxonomia; o post de COMUNIDADE herda o dela e
 * o compositor não escolhe. Na edição as regras são as mesmas. E os novos recortes do feed
 * (notícias, tutoriais, oportunidades, avaliações, diversão) só mostram o tipo deles.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TopicCategorizationTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private TestAuthHelper auth;
	/** O H2 dos testes é compartilhado entre classes: comunidade criada aqui é apagada aqui. */
	private final List<String[]> createdCommunities = new ArrayList<>();

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

	private String publish(String topic, String content) throws Exception {
		java.util.Map<String, Object> body = new java.util.HashMap<>(java.util.Map.of(
			"type", "INSIGHT", "content", content, "tags", List.of(), "mediaIds", List.of()));
		if (topic != null) {
			body.put("topic", topic);
		}
		MvcResult result = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", author().token())
				.header("Idempotency-Key", "topic-" + System.nanoTime())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(body)))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private TestAuthHelper.AuthSession author() throws Exception {
		return auth.authenticate("topic_author");
	}

	@Test
	void standalonePostCarriesTheDeclaredTopic() throws Exception {
		String postId = publish("GAMES", "Post de jogos com assunto declarado.");
		mockMvc.perform(get("/feed/posts/" + postId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.topic").value("GAMES"));
	}

	@Test
	void standalonePostWithoutTopicIsRejected() throws Exception {
		mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", author().token())
				.header("Idempotency-Key", "topic-missing")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of(
					"type", "INSIGHT", "content", "post sem assunto.", "tags", List.of(), "mediaIds", List.of()))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("TOPIC_REQUIRED"));
	}

	@Test
	void invalidTopicIsRejected() throws Exception {
		mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", author().token())
				.header("Idempotency-Key", "topic-invalid")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of(
					"type", "INSIGHT", "content", "conteúdo", "tags", List.of(), "mediaIds", List.of(),
					"topic", "TECNOLOGIAS"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_TOPIC"));
	}

	@Test
	void communityPostInheritsTheCommunityTopic() throws Exception {
		var owner = author();
		MvcResult community = mockMvc.perform(post("/communities")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "topic-comm-" + System.nanoTime())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of(
					"name", "Comunidade de Jogos", "description", "testes", "topic", "GAMES"))))
			.andExpect(status().isCreated())
			.andReturn();
		String communityId = objectMapper.readTree(community.getResponse().getContentAsString()).get("id").asText();
		createdCommunities.add(new String[] { communityId, owner.token() });

		// Mandar assunto junto com comunidade: recusado (o assunto é o DELA).
		mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "topic-comm-conflict")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of(
					"type", "INSIGHT", "content", "conteúdo", "tags", List.of(), "mediaIds", List.of(),
					"communityId", communityId, "topic", "ART"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("TOPIC_FROM_COMMUNITY"));

		// Sem assunto: herda.
		MvcResult post = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "topic-comm-inherit")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of(
					"type", "INSIGHT", "content", "post na comunidade", "tags", List.of(),
					"mediaIds", List.of(), "communityId", communityId))))
			.andExpect(status().isCreated())
			.andReturn();
		String postId = objectMapper.readTree(post.getResponse().getContentAsString()).get("id").asText();
		mockMvc.perform(get("/feed/posts/" + postId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.topic").value("GAMES"));
	}

	@Test
	void editingChangesTheTopicOfAStandalonePostOnly() throws Exception {
		var author = author();
		String postId = publish("GAMES", "post para editar o assunto.");

		mockMvc.perform(patch("/feed/posts/" + postId)
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "topic-edit-" + System.nanoTime())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of(
					"type", "INSIGHT", "content", "post para editar o assunto.", "tags", List.of(),
					"topic", "ART"))))
			.andExpect(status().isOk());
		mockMvc.perform(get("/feed/posts/" + postId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.topic").value("ART"));

		// Assunto inválido na edição: recusado, e o post continua com o anterior.
		mockMvc.perform(patch("/feed/posts/" + postId)
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "topic-edit-invalid")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of(
					"type", "INSIGHT", "content", "post para editar o assunto.", "tags", List.of(),
					"topic", "JOGOS"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_TOPIC"));
		mockMvc.perform(get("/feed/posts/" + postId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.topic").value("ART"));
	}

	@Test
	void newFeedTabsShowOnlyTheirType() throws Exception {
		publish("TECNOLOGIA", "notícia do dia.");
		String newsId = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", author().token())
				.header("Idempotency-Key", "topic-news-" + System.nanoTime())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of(
					"type", "NEWS", "content", "notícia classificada.", "tags", List.of(),
					"mediaIds", List.of(), "topic", "GAMES"))))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();

		String newsPostId = objectMapper.readTree(newsId).get("id").asText();
		String token = author().token();

		// A aba de notícias mostra a notícia; a aba de diversão não mostra nada dela.
		// (O feed é autenticado: toda leitura de aba leva a sessão, como no app.)
		mockMvc.perform(get("/feed").param("tab", "NEWS").header("X-Session-Token", token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.id == '" + newsPostId + "')]").isNotEmpty());
		mockMvc.perform(get("/feed").param("tab", "FUN").header("X-Session-Token", token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.id == '" + newsPostId + "')]").isEmpty());
	}
}
