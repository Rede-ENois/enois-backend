package com.socialcoisa.rede.freelances.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * O dono edita e apaga o próprio freela; quem não é dono recebe 403; freela encerrado não edita;
 * a edição passa pelas mesmas validações da criação.
 */
@SpringBootTest
@AutoConfigureMockMvc
class FreelanceEditDeleteTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	private String create(TestAuthHelper.AuthSession owner) throws Exception {
		String body = mockMvc.perform(post("/freelances")
				.header("X-Session-Token", owner.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"title", "Landing page",
					"scope", "Construir a landing page com formulário e integração de e-mail.",
					"budgetMin", 1000,
					"budgetMax", 2000,
					"deadline", LocalDate.now().plusDays(7).toString()))))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
		return objectMapper.readTree(body).get("id").asString();
	}

	@Test
	void ownerEditsAndDeletesTheFreelance() throws Exception {
		var owner = auth.authenticate("frl_edit_owner1");
		String id = create(owner);

		mockMvc.perform(patch("/freelances/" + id)
				.header("X-Session-Token", owner.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"title", "Landing page + blog",
					"scope", "Construir a landing page e um blog simples com painel de publicação.",
					"budgetMin", 2500,
					"budgetMax", 4000,
					"deadline", LocalDate.now().plusDays(14).toString(),
					"paymentMethod", "CASH"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.title").value("Landing page + blog"))
			.andExpect(jsonPath("$.budgetMin").value(2500))
			.andExpect(jsonPath("$.paymentMethod").value("CASH"));

		mockMvc.perform(get("/me/freelances").header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.id == '" + id + "')].title").value("Landing page + blog"));

		mockMvc.perform(delete("/freelances/" + id).header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("DELETED"));

		mockMvc.perform(get("/me/freelances").header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.id == '" + id + "')]").isEmpty());
	}

	@Test
	void nonOwnerCannotEditOrDeleteAndClosedFreelanceDoesNotEdit() throws Exception {
		var owner = auth.authenticate("frl_edit_owner2");
		var other = auth.authenticate("frl_edit_other2");
		String id = create(owner);

		Map<String, Object> editBody = Map.of(
			"title", "Tentativa",
			"scope", "Escopo longo o bastante para passar da validacao de vinte caracteres.",
			"budgetMin", 100, "budgetMax", 200,
			"deadline", LocalDate.now().plusDays(3).toString());

		mockMvc.perform(patch("/freelances/" + id)
				.header("X-Session-Token", other.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(editBody)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NOT_OWNER"));

		mockMvc.perform(delete("/freelances/" + id).header("X-Session-Token", other.token()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NOT_OWNER"));

		// Encerrado não edita (o freela já saiu da vitrine).
		mockMvc.perform(post("/freelances/" + id + "/close").header("X-Session-Token", owner.token()))
			.andExpect(status().isOk());

		mockMvc.perform(patch("/freelances/" + id)
				.header("X-Session-Token", owner.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(editBody)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("FREELANCE_CLOSED"));

		// A edição roda as validações da criação: faixa de valor invertida recusa.
		var owner3 = auth.authenticate("frl_edit_owner3");
		String id3 = create(owner3);
		mockMvc.perform(patch("/freelances/" + id3)
				.header("X-Session-Token", owner3.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"title", "Faixa invertida",
					"scope", "Escopo longo o bastante para passar da validacao de vinte caracteres.",
					"budgetMin", 500, "budgetMax", 100,
					"deadline", LocalDate.now().plusDays(3).toString()))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_BUDGET"));
	}
}
