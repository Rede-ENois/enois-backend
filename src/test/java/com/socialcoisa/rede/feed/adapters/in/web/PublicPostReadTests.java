package com.socialcoisa.rede.feed.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

import tools.jackson.databind.ObjectMapper;

/**
 * O post e o destino do link compartilhado: quem recebe nao tem (nem deveria precisar de) sessao.
 *
 * Antes a leitura exigia autenticacao e o destinatario caia no login — ou, quando tinha sessao sem
 * identidade verificada, recebia "post nao encontrado".
 */
@SpringBootTest
@AutoConfigureMockMvc
class PublicPostReadTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	@Test
	void anyoneCanReadAPostAndItsReactionsWithoutASession() throws Exception {
		var author = auth.authenticate("public_post_author");
		String postId = publish(author, "Post publicado para o link compartilhado");

		// Sem nenhum cabecalho de sessao: e assim que quem recebe o link abre a pagina.
		mockMvc.perform(get("/feed/posts/" + postId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(postId))
			.andExpect(jsonPath("$.content").value("Post publicado para o link compartilhado"))
			.andExpect(jsonPath("$.authorUsername").value("public-post-author"));

		mockMvc.perform(get("/feed/posts/" + postId + "/reactions"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.postId").value(postId));

		// Com sessao continua funcionando (e acrescenta o estado do visitante).
		mockMvc.perform(get("/feed/posts/" + postId).header("X-Session-Token", author.token()))
			.andExpect(status().isOk());
	}

	@Test
	void writingStillRequiresASession() throws Exception {
		var author = auth.authenticate("public_post_writer");
		String postId = publish(author, "Post para conferir que escrever exige sessao");

		// Comentar, reagir e compartilhar continuam exigindo sessao: leitura e publica, acao nao.
		mockMvc.perform(post("/feed/posts/" + postId + "/comments")
				.header("Idempotency-Key", "public-comment")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("content", "comentario anonimo"))))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(post("/feed/posts/" + postId + "/shares")
				.header("Idempotency-Key", "public-share"))
			.andExpect(status().isUnauthorized());
	}

	private String publish(TestAuthHelper.AuthSession session, String content) throws Exception {
		MvcResult result = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "public-post-" + content.hashCode())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA",
					"type", "INSIGHT",
					"content", content,
					"tags", List.of(),
					"mediaIds", List.of()))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}
}