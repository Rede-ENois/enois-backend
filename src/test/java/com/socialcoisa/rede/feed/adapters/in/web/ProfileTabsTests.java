package com.socialcoisa.rede.feed.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

import tools.jackson.databind.ObjectMapper;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

/**
 * Tabs de categorias no perfil: ALL retrocompativel, REPLIES privado, filtros sem perder dados.
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

	@Test
	void abaDefaultRetrocompativel() throws Exception {
		var session = auth.authenticate("proftabs_user1");
		// Sem tab = ALL = mesmo resultado de antes.
		mockMvc.perform(get("/users/proftabs_user1/posts")
				.header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items").isArray());

		// Com tab=ALL explícito, mesma coisa.
		mockMvc.perform(get("/users/proftabs_user1/posts?tab=ALL")
				.header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items").isArray());
	}

	@Test
	void repliesExigemViewerDono() throws Exception {
		var owner = auth.authenticate("proftabs_owner");
		var visitor = auth.authenticate("proftabs_visitor");

		// Dono ve seus replies.
		mockMvc.perform(get("/users/proftabs_owner/posts?tab=REPLIES")
				.header("X-Session-Token", owner.token()))
			.andExpect(status().isOk());

		// Visitante leva 403.
		mockMvc.perform(get("/users/proftabs_owner/posts?tab=REPLIES")
				.header("X-Session-Token", visitor.token()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("REPLIES_PRIVATE"));

		// Sem sessão leva 403.
		mockMvc.perform(get("/users/proftabs_owner/posts?tab=REPLIES"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("REPLIES_PRIVATE"));
	}

	@Test
	void tabInvalidaRetorna400() throws Exception {
		mockMvc.perform(get("/users/proftabs_user1/posts?tab=INEXISTENTE"))
			.andExpect(status().isBadRequest());
	}
}
