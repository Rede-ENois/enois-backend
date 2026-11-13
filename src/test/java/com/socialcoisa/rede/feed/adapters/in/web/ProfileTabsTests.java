package com.socialcoisa.rede.feed.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.ObjectMapper;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

/**
 * Tabs de categorias no perfil: ALL retrocompativel, REPLIES privado, tab invalida.
 *
 * O username do cadastro nao e o name passado ao signup: o backend gera com regras proprias.
 * Por isso cada teste busca /me pra descobrir o username real antes de consultar o perfil.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProfileTabsTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	/** Descobre o username real da conta recem-criada. */
	private String usernameOf(TestAuthHelper.AuthSession session) throws Exception {
		var result = mockMvc.perform(get("/me")
				.header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andReturn();
		var body = objectMapper.readTree(result.getResponse().getContentAsString());
		return body.get("username").asText();
	}

	@Test
	void abaDefaultRetrocompativel() throws Exception {
		var session = auth.authenticate("proftabs_compat");
		String username = usernameOf(session);

		// Sem tab = ALL = mesmo resultado de antes.
		mockMvc.perform(get("/users/" + username + "/posts")
				.header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items").isArray());

		// Com tab=ALL explicito, mesma coisa.
		mockMvc.perform(get("/users/" + username + "/posts?tab=ALL")
				.header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items").isArray());
	}

	@Test
	void repliesExigemViewerDono() throws Exception {
		var owner = auth.authenticate("proftabs_dono");
		var visitor = auth.authenticate("proftabs_visitante");
		String ownerUsername = usernameOf(owner);

		// Dono ve seus replies.
		mockMvc.perform(get("/users/" + ownerUsername + "/posts?tab=REPLIES")
				.header("X-Session-Token", owner.token()))
			.andExpect(status().isOk());

		// Visitante leva 403.
		mockMvc.perform(get("/users/" + ownerUsername + "/posts?tab=REPLIES")
				.header("X-Session-Token", visitor.token()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("REPLIES_PRIVATE"));

		// Sem sessao leva 403.
		mockMvc.perform(get("/users/" + ownerUsername + "/posts?tab=REPLIES"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("REPLIES_PRIVATE"));
	}

	@Test
	void tabInvalidaRetorna400() throws Exception {
		var session = auth.authenticate("proftabs_invalid");
		String username = usernameOf(session);

		mockMvc.perform(get("/users/" + username + "/posts?tab=INEXISTENTE"))
			.andExpect(status().isBadRequest());
	}
}
