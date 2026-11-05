package com.socialcoisa.rede.feed.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A personalizacao do nome do perfil acompanha a pessoa em TODO lugar onde o nome aparece: a
 * publicacao, a leitura do post, o comentario e a notificacao do autor.
 *
 * Quem nao personalizou nao manda estilo nenhum — o front desenha o nome como sempre.
 */
@SpringBootTest
@AutoConfigureMockMvc
class NameStyleEverywhereTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	private void personalize(String token) throws Exception {
		mockMvc.perform(put("/cosmetics/profile/name-style")
				.header("X-Session-Token", token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(
					Map.of("theme", "NEON", "font", "DISPLAY", "color", "#FF4FA3"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.theme").value("NEON"));
	}

	private String publicar(String token, String chave, String content) throws Exception {
		MvcResult publicado = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", token)
				.header("Idempotency-Key", chave)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA",
					"type", "INSIGHT", "content", content, "tags", List.of(),
					"mediaIds", List.of()))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(publicado.getResponse().getContentAsString()).get("id").asText();
	}

	@Test
	void publicacaoComentarioELeituraCarregamOPersonalizacaoDoNome() throws Exception {
		var autor = auth.authenticate("name_style_autor");
		personalize(autor.token());

		MvcResult publicado = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", autor.token())
				.header("Idempotency-Key", "name-style-pub-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA",
					"type", "INSIGHT", "content", "Post com nome personalizado.", "tags", List.of(),
					"mediaIds", List.of()))))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.authorStyle.theme").value("NEON"))
			.andExpect(jsonPath("$.authorStyle.font").value("DISPLAY"))
			.andExpect(jsonPath("$.authorStyle.color").value("#ff4fa3"))
			.andReturn();
		String postId = objectMapper.readTree(publicado.getResponse().getContentAsString()).get("id").asText();

		// A leitura enriquecida (feed/post unico) precisa carregar o mesmo estilo.
		mockMvc.perform(get("/feed/posts/" + postId).header("X-Session-Token", autor.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.authorStyle.theme").value("NEON"))
			.andExpect(jsonPath("$.authorStyle.color").value("#ff4fa3"));

		mockMvc.perform(post("/feed/posts/" + postId + "/comments")
				.header("X-Session-Token", autor.token())
				.header("Idempotency-Key", "name-style-comment-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"content", "Comentario com o mesmo estilo.", "mediaIds", List.of(),
					"mentionedUserIds", List.of()))))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.authorStyle.theme").value("NEON"))
			.andExpect(jsonPath("$.authorStyle.font").value("DISPLAY"));
	}

	@Test
	void notificacaoDoAutorCarregaOPersonalizacaoDoNome() throws Exception {
		var dono = auth.authenticate("name_style_dono");
		var comentarista = auth.authenticate("name_style_comentarista");
		personalize(comentarista.token());

		String postId = publicar(dono.token(), "name-style-notif-pub", "Post do dono sem estilo.");

		mockMvc.perform(post("/feed/posts/" + postId + "/comments")
				.header("X-Session-Token", comentarista.token())
				.header("Idempotency-Key", "name-style-notif-comment")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"content", "Comentei marcando o dono para gerar a notificacao.", "mediaIds", List.of(),
					"mentionedUserIds", List.of(dono.userId())))))
			.andExpect(status().isCreated());

		MvcResult notificacoes = mockMvc.perform(get("/notifications").header("X-Session-Token", dono.token()))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode items = objectMapper.readTree(notificacoes.getResponse().getContentAsString()).get("items");
		JsonNode doComentarista = null;
		for (JsonNode item : items) {
			if (comentarista.userId().equals(item.path("actorId").asString())) {
				doComentarista = item;
				break;
			}
		}
		assertThat(doComentarista).as("notificacao do comentarista").isNotNull();
		assertThat(doComentarista.path("actorStyle").path("theme").asString()).isEqualTo("NEON");
		assertThat(doComentarista.path("actorStyle").path("color").asString()).isEqualTo("#ff4fa3");
	}

	@Test
	void quemNaoPersonalizouNaoMandaEstilo() throws Exception {
		var autor = auth.authenticate("name_style_sem");

		mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", autor.token())
				.header("Idempotency-Key", "name-style-sem-pub")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA",
					"type", "INSIGHT", "content", "Post sem personalizacao.", "tags", List.of(),
					"mediaIds", List.of()))))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.authorStyle").doesNotExist());
	}
}
