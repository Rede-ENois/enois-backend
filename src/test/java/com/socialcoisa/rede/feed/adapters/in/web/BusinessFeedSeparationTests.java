package com.socialcoisa.rede.feed.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * O coração do plano: a separação dos feeds no BACKEND. Uma mesma conta publica um post social
 * e um BUSINESS; o social aparece no "Tudo" e no perfil social; o BUSINESS não aparece em
 * nenhum deles e só existe no feed corporativo e na página do perfil profissional.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BusinessFeedSeparationTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}


	private void createAndActivate(TestAuthHelper.AuthSession author, String username, String name)
			throws Exception {
		mockMvc.perform(post("/me/business-profile")
				.header("X-Session-Token", author.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of("username", username, "name", name))))
			.andExpect(status().isOk());
		mockMvc.perform(patch("/me/business-profile")
				.header("X-Session-Token", author.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of(
					"headline", "Headline profissional de " + name))))
			.andExpect(status().isOk());
		mockMvc.perform(patch("/me/business-profile")
				.header("X-Session-Token", author.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of(
					"headline", "Headline profissional"))))
			.andExpect(status().isOk());
		mockMvc.perform(post("/me/business-profile/activate")
				.header("X-Session-Token", author.token()))
			.andExpect(status().isOk());
	}

	private String publish(TestAuthHelper.AuthSession author, String type, String content) throws Exception {
		MvcResult result = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "biz-" + type + "-" + System.nanoTime())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of("topic", "TECNOLOGIA",
					"type", type, "content", content, "tags", java.util.List.of(), "mediaIds", java.util.List.of()))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	@Test
	void socialEDusinessDaMesmaContaSeSeparam() throws Exception {
		var author = auth.authenticate("bizsep_author");

		// A conta ainda não tem perfil profissional: BUSINESS é recusado com explicação.
		mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "biz-sem-perfil")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of("topic", "TECNOLOGIA",
					"type", "BUSINESS", "content", "Anúncio corporativo.", "tags", java.util.List.of(),
					"mediaIds", java.util.List.of()))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NO_ACTIVE_CORPORATE_PROFILE"));

		// Cria e ativa o perfil profissional da mesma conta.
		mockMvc.perform(post("/me/business-profile")
				.header("X-Session-Token", author.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of(
					"username", "bizsep.pro", "name", "Autor Profissional"))))
			.andExpect(status().isOk());
		mockMvc.perform(patch("/me/business-profile")
				.header("X-Session-Token", author.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of(
					"headline", "Headline profissional"))))
			.andExpect(status().isOk());
		mockMvc.perform(post("/me/business-profile/activate")
				.header("X-Session-Token", author.token()))
			.andExpect(status().isOk());

		String socialId = publish(author, "INSIGHT", "Post social da mesma pessoa.");
		String businessId = publish(author, "BUSINESS", "Post corporativo da mesma pessoa.");

		// Feed "Tudo": o social entra, o BUSINESS nunca. E o social é assinado pela identidade
		// pessoal (apresentação social), não pelo perfil profissional.
		mockMvc.perform(get("/feed").param("tab", "ALL").header("X-Session-Token", author.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.id == '" + socialId + "')]").isNotEmpty())
			.andExpect(jsonPath("$.items[?(@.id == '" + socialId + "')].authorIdentity").value("SOCIAL"))
			.andExpect(jsonPath("$.items[?(@.id == '" + businessId + "')]").isEmpty());

		// Abas sociais por tipo (Discussões = QUESTION, Projetos = PROJECT): o BUSINESS
		// nunca entra — o filtro por tipo é estrutural, mas o teste fecha a porta.
		for (String tab : java.util.List.of("DISCUSSIONS", "PROJECTS")) {
			mockMvc.perform(get("/feed").param("tab", tab).header("X-Session-Token", author.token()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[?(@.id == '" + businessId + "')]").isEmpty());
		}

		// Feed corporativo: o BUSINESS entra, o social nunca. A assinatura é a profissional —
		// nome, @ e identidade do alterego, não a apresentação pessoal.
		mockMvc.perform(get("/feed/corporate").header("X-Session-Token", author.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.id == '" + businessId + "')]").isNotEmpty())
			.andExpect(jsonPath("$.items[?(@.id == '" + businessId + "')].authorIdentity").value("CORPORATE"))
			.andExpect(jsonPath("$.items[?(@.id == '" + businessId + "')].authorName")
				.value("Autor Profissional"))
			.andExpect(jsonPath("$.items[?(@.id == '" + businessId + "')].authorUsername").value("bizsep.pro"))
			.andExpect(jsonPath("$.items[?(@.id == '" + socialId + "')]").isEmpty());

		// Perfil social (página pública do autor): só o social.
		mockMvc.perform(get("/users/bizsep-author/posts"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.id == '" + socialId + "')]").isNotEmpty())
			.andExpect(jsonPath("$.items[?(@.id == '" + businessId + "')]").isEmpty());

		// Página do perfil profissional: só o BUSINESS, com a assinatura profissional.
		mockMvc.perform(get("/corporate/bizsep.pro/posts"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.id == '" + businessId + "')]").isNotEmpty())
			.andExpect(jsonPath("$.items[?(@.id == '" + businessId + "')].authorName")
				.value("Autor Profissional"))
			.andExpect(jsonPath("$.items[?(@.id == '" + businessId + "')].authorIdentity").value("CORPORATE"))
			.andExpect(jsonPath("$.items[?(@.id == '" + socialId + "')]").isEmpty());
	}

	@Test
	void editarNaoTrocaIdentidade() throws Exception {
		var author = auth.authenticate("bizsep_editor");
		mockMvc.perform(post("/me/business-profile")
				.header("X-Session-Token", author.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of(
					"username", "bizsep.ed", "name", "Editor Profissional"))))
			.andDo(r -> System.out.println("CREATE RESP " + r.getResponse().getStatus() + " "
				+ r.getResponse().getContentAsString()))
			.andExpect(status().isOk());
		mockMvc.perform(patch("/me/business-profile")
				.header("X-Session-Token", author.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of(
					"headline", "Headline profissional"))))
			.andExpect(status().isOk());
		mockMvc.perform(post("/me/business-profile/activate")
				.header("X-Session-Token", author.token()))
			.andExpect(status().isOk());

		String businessId = publish(author, "BUSINESS", "Corporativo original.");

		// Tentar virar o post BUSINESS em social pela edição: recusado no servidor.
		mockMvc.perform(patch("/feed/posts/" + businessId)
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "biz-edit-troca")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of(
					"type", "INSIGHT", "content", "tentativa de virar social", "tags", java.util.List.of()))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("IDENTITY_TYPE_MISMATCH"));

		// Editar o conteúdo mantendo o tipo: ok, e continua no feed corporativo.
		mockMvc.perform(patch("/feed/posts/" + businessId)
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "biz-edit-ok")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of(
					"type", "BUSINESS", "content", "Corporativo editado.", "tags", java.util.List.of()))))
			.andExpect(status().isOk());

		mockMvc.perform(get("/feed/corporate").header("X-Session-Token", author.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.id == '" + businessId + "')].content").value("Corporativo editado."));
	}

	@Test
	void perfilProfissionalInativoNaoTemPagina() throws Exception {
		var author = auth.authenticate("bizsep_inativo");
		mockMvc.perform(post("/me/business-profile")
				.header("X-Session-Token", author.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of(
					"username", "bizsep.off", "name", "Perfil em Construção"))))
			.andExpect(status().isOk());
		// Ativa, publica, desativa: a página some (404), o feed corporativo também o esconde? Não:
		// a página 404; o post permanece (desativar não apaga nada).
		mockMvc.perform(patch("/me/business-profile")
				.header("X-Session-Token", author.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of(
					"headline", "Headline profissional"))))
			.andExpect(status().isOk());
		mockMvc.perform(post("/me/business-profile/activate")
				.header("X-Session-Token", author.token()))
			.andExpect(status().isOk());
		publish(author, "BUSINESS", "Post de um perfil que vai ser desativado.");
		mockMvc.perform(post("/me/business-profile/deactivate")
				.header("X-Session-Token", author.token()))
			.andExpect(status().isOk());

		mockMvc.perform(get("/corporate/bizsep.off/posts"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PROFILE_NOT_FOUND"));
	}
}
