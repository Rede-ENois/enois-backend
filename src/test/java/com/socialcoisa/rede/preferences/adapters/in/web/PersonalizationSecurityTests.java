package com.socialcoisa.rede.preferences.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import tools.jackson.databind.ObjectMapper;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

/**
 * Critérios 10.5 do plano de extensão: preferência é de EXPERIÊNCIA, nunca de autorização.
 * Ninguém altera as preferências de outra conta, desativar um módulo não bloqueia a API, e
 * assunto bloqueado não esconde o conteúdo acessado por link direto (sem censura global).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Tag("critico")
class PersonalizationSecurityTests {

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

	@Test
	void preferencesRequireASession() throws Exception {
		// Sem sessão é 401 UNAUTHENTICATED (não "400 cabeçalho ausente"): o contrato de
		// autorização é o mesmo de todo o resto da API.
		mockMvc.perform(get("/me/preferences"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		mockMvc.perform(put("/me/preferences")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of(
					"likedTopics", java.util.List.of("TECNOLOGIA")))))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	void cannotChangeAnotherAccountsPreferences() throws Exception {
		var victim = auth.authenticate("persec_victim");
		var attacker = auth.authenticate("persec_attacker");

		// A vítima tem uma preferência própria.
		mockMvc.perform(put("/me/preferences")
				.header("X-Session-Token", victim.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of(
					"likedTopics", java.util.List.of("TECNOLOGIA")))))
			.andExpect(status().isOk());

		// O atacante manda um payload com o userId da vítima: o backend ignora — a conta é a
		// da SESSÃO, nunca a do corpo.
		mockMvc.perform(put("/me/preferences")
				.header("X-Session-Token", attacker.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of(
					"userId", victim.userId(),
					"blockedTopics", java.util.List.of("DESIGN")))))
			.andExpect(status().isOk());

		// A vítima continua com a preferência dela, sem bloqueios.
		mockMvc.perform(get("/me/preferences").header("X-Session-Token", victim.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.likedTopics[0]").value("TECNOLOGIA"))
			.andExpect(jsonPath("$.blockedTopics.length()").value(0));
	}

	@Test
	void disablingModulesDoesNotBlockTheApi() throws Exception {
		var user = auth.authenticate("persec_modules");

		// Desativa TUDO na experiência pessoal.
		mockMvc.perform(put("/me/preferences")
				.header("X-Session-Token", user.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of(
					"disabledModules", java.util.List.of("MESSAGES", "JOBS", "COMMUNITIES", "EVENTS")))))
			.andExpect(status().isOk());

		// A preferência é de interface: a API continua respondendo normalmente (o backend
		// mantém as regras de acesso dele, independentes da tela).
		mockMvc.perform(get("/conversations").header("X-Session-Token", user.token()))
			.andExpect(status().isOk());
		mockMvc.perform(get("/jobs").header("X-Session-Token", user.token()))
			.andExpect(status().isOk());
		mockMvc.perform(get("/communities").header("X-Session-Token", user.token()))
			.andExpect(status().isOk());
		mockMvc.perform(get("/events").header("X-Session-Token", user.token()))
			.andExpect(status().isOk());
	}

	@Test
	void blockedTopicDoesNotHideThePostByDirectLink() throws Exception {
		var author = auth.authenticate("persec_author");
		var reader = auth.authenticate("persec_reader");

		// Post publicado numa comunidade com assunto: o leitor bloqueia o assunto.
		MvcResult community = mockMvc.perform(post("/communities")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "persec-comm")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of(
					"name", "Persec Tech", "description", "Comunidade de teste", "topic", "TECNOLOGIA"))))
			.andExpect(status().isCreated())
			.andReturn();
		String communityId = objectMapper.readTree(community.getResponse().getContentAsString())
			.get("id").asText();
		createdCommunities.add(new String[] { communityId, author.token() });

		MvcResult post = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "persec-post")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of(
					"type", "INSIGHT", "content", "Conteúdo de tecnologia.", "tags", java.util.List.of(),
					"mediaIds", java.util.List.of(), "communityId", communityId))))
			.andExpect(status().isCreated())
			.andReturn();
		String postId = objectMapper.readTree(post.getResponse().getContentAsString()).get("id").asText();

		mockMvc.perform(put("/me/preferences")
				.header("X-Session-Token", reader.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of(
					"blockedTopics", java.util.List.of("TECNOLOGIA")))))
			.andExpect(status().isOk());

		// Link direto continua abrindo para qualquer pessoa: o filtro é do feed, não uma
		// censura do conteúdo (a publicação segue no ar).
		mockMvc.perform(get("/feed/posts/" + postId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(postId));
	}
}
