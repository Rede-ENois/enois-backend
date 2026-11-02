package com.socialcoisa.rede.profile.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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

import tools.jackson.databind.ObjectMapper;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

/**
 * O card de publicacoes do perfil media o tamanho da pagina carregada pelo front (10), nao o
 * inteiro: quem tinha mais posts via um numero menor que a realidade. A contagem agora vem do
 * backend e obedece aos MESMOS filtros da listagem social do perfil.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProfilePostsCountTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	private void publish(String token, String type, String content) throws Exception {
		mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", token)
				.header("Idempotency-Key", "pcount-" + System.nanoTime())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA",
					"type", type, "content", content, "tags", List.of(), "mediaIds", List.of()))))
			.andExpect(status().isCreated());
	}

	private void activateCorporateProfile(String token, String businessUsername) throws Exception {
		mockMvc.perform(post("/me/business-profile")
				.header("X-Session-Token", token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"username", businessUsername, "name", "Autor Profissional"))))
			.andExpect(status().isOk());
		mockMvc.perform(patch("/me/business-profile")
				.header("X-Session-Token", token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("headline", "Headline profissional"))))
			.andExpect(status().isOk());
		mockMvc.perform(post("/me/business-profile/activate").header("X-Session-Token", token))
			.andExpect(status().isOk());
	}

	@Test
	void inteiroNaoParaNaPagina() throws Exception {
		var author = auth.authenticate("pcount_autor");
		for (int i = 1; i <= 12; i++) {
			publish(author.token(), "INSIGHT", "Post numero " + i + " do autor.");
		}

		// A pagina de 10 continua paginada; o numero do card e o inteiro das 12 publicacoes.
		mockMvc.perform(get("/users/pcount-autor"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.postsCount").value(12));

		mockMvc.perform(get("/users/pcount-autor/posts").param("limit", "10"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(10))
			.andExpect(jsonPath("$.nextCursor").isNotEmpty());
	}

	@Test
	void contagemSegueOsFiltrosDaListagemSocial() throws Exception {
		var author = auth.authenticate("pcount_filtros");
		activateCorporateProfile(author.token(), "pcount.pro");

		publish(author.token(), "INSIGHT", "Social que entra na conta.");
		publish(author.token(), "QUESTION", "Outro social que entra.");
		publish(author.token(), "BUSINESS", "Anuncio do alterego profissional.");

		// O BUSINESS so existe no feed corporativo e na pagina profissional: nem lista, nem conta.
		mockMvc.perform(get("/users/pcount-filtros"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.postsCount").value(2));
	}

	@Test
	void perfilSemPostsContaZero() throws Exception {
		var author = auth.authenticate("pcount_vazio");

		mockMvc.perform(get("/users/pcount-vazio"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.postsCount").value(0));
	}
}
