package com.socialcoisa.rede.feed.adapters.in.web;

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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.TestPropertySource;

import tools.jackson.databind.ObjectMapper;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

/**
 * Contrato do ciclo comentario da issue #461: o POST recusado por DUPLICATE_CONTENT (409) nao pode
 * interferir na listagem, e o comentario aceito aparece na proxima leitura.
 *
 * <p>O sintoma relatado (POST 201 e a lista ainda "nao carrega") se mostrou comportamento do
 * frontend, corrigido la — este teste trava o contrato no lado do backend: listagem saudavel antes,
 * durante e depois de uma recusa de duplicidade. A deduplicacao vem LIGADA aqui porque a suite
 * base a desliga (as classes de limite publicam o mesmo texto de proposito).</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "rede.rate.duplicate-window-minutes=60")
class CommentContractTests {

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
	void comentarioAceitoApareceNaListaEDuplicadoNaoQuebraNada() throws Exception {
		var author = auth.authenticate("ccontract_author");
		String postId = publish(author, "Post do contrato de comentarios.");

		// 1) Primeiro comentario: 201 e aparece na listagem seguinte.
		String texto = "O selo de contribuicao vem depois de quantos PRs?";
		mockMvc.perform(post("/feed/posts/" + postId + "/comments")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "ccontract-c1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("content", texto))))
			.andExpect(status().isCreated());

		mockMvc.perform(get("/feed/posts/" + postId + "/comments"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.content == '" + texto + "')]").isNotEmpty());

		// 2) O mesmo texto de novo: 409 DUPLICATE_CONTENT, sem alterar a lista.
		mockMvc.perform(post("/feed/posts/" + postId + "/comments")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "ccontract-c2")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("content", texto))))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DUPLICATE_CONTENT"));

		MvcResult depoisDuplicado = mockMvc.perform(get("/feed/posts/" + postId + "/comments"))
			.andExpect(status().isOk())
			.andReturn();
		var pagina = objectMapper.readTree(depoisDuplicado.getResponse().getContentAsString());
		assertThat(pagina.get("items").size()).isEqualTo(1);

		// 3) Texto diferente: 201 e a listagem passa a ter os dois.
		String outro = "...quantos PRs?";
		mockMvc.perform(post("/feed/posts/" + postId + "/comments")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "ccontract-c3")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("content", outro))))
			.andExpect(status().isCreated());

		MvcResult listagemFinal = mockMvc.perform(get("/feed/posts/" + postId + "/comments"))
			.andExpect(status().isOk())
			.andReturn();
		var paginaFinal = objectMapper.readTree(listagemFinal.getResponse().getContentAsString());
		assertThat(paginaFinal.get("items").size()).isEqualTo(2);
		assertThat(paginaFinal.toString()).contains(texto).contains(outro);

		// 4) A listagem autenticada (com sessao, como o app le) continua saudavel.
		mockMvc.perform(get("/feed/posts/" + postId + "/comments")
				.header("X-Session-Token", author.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.size()").value(2));
	}

	private String publish(TestAuthHelper.AuthSession session, String content) throws Exception {
		MvcResult result = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "ccontract-pub-" + content.hashCode())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA",
					"type", "INSIGHT", "content", content, "tags", java.util.List.of(), "mediaIds", java.util.List.of()))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}
}
