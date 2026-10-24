package com.socialcoisa.rede.moderation.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * O painel de moderação inteiro (filas, recursos, disputas, usuários, monitoramento) responde ao
 * MODERADOR; a candidatura continua exclusiva do admin; usuário comum fica fora de tudo.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ModeratorPermissionsTests {

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry registry) {
		registry.add("rede.admin.emails", () -> "modperm_admin@test.com");
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	private TestAuthHelper auth;
	private String moderatorUserId;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	@AfterEach
	void limpaModerador() {
		if (moderatorUserId == null) {
			return;
		}
		try {
			var admin = auth.authenticate("modperm_admin");
			mockMvc.perform(post("/admin/moderators/" + moderatorUserId + "/revoke")
					.header("X-Session-Token", admin.token()));
		}
		catch (Throwable cleanupFalhou) {
			// A limpeza não pode mascarar o resultado do teste; o banco compartilhado tolera.
		}
		finally {
			moderatorUserId = null;
		}
	}

	private TestAuthHelper.AuthSession admin() throws Exception {
		return auth.authenticate("modperm_admin");
	}

	private TestAuthHelper.AuthSession moderador() throws Exception {
		var admin = admin();
		var mod = auth.authenticate("modperm_mod");
		moderatorUserId = mod.userId();
		String modUsername = jdbc.queryForObject("select username from users where email = ?",
			String.class, "modperm_mod@test.com");
		// Se uma execução anterior morreu antes da limpeza, o cargo já existe: revoke tenta de novo.
		try {
			mockMvc.perform(post("/admin/moderators/" + moderatorUserId + "/revoke")
					.header("X-Session-Token", admin.token()));
		}
		catch (Throwable sobraIgnorada) {
			// Sem sobra: o revoke não encontra o cargo e isso é esperado aqui.
		}
		mockMvc.perform(post("/admin/moderators")
				.header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("username", modUsername,
					"note", "Promovido para o teste de permissões do painel."))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("APPROVED"));

		Integer aprovadas = jdbc.queryForObject(
			"select count(*) from moderator_applications where user_id = ? and status = 'APPROVED'",
			Integer.class, mod.userId());
		return mod;
	}

	@Test
	void moderadorVeTodasAsFilasDoPainel() throws Exception {
		var mod = moderador();
		var plain = auth.authenticate("modperm_plain");
		String plainUsername = jdbc.queryForObject("select username from users where email = ?",
			String.class, "modperm_plain@test.com");

		String[] rotas = {
			"/admin/appeals",
			"/admin/support",
			"/admin/emails",
			"/admin/carteira/disputas",
			"/admin/blocked-ips",
			"/admin/users/" + plainUsername + "/ips",
			"/admin/monitoring/overview",
			"/admin/monitoring/events",
		};
		java.util.List<String> negadas = new java.util.ArrayList<>();
		for (String rota : rotas) {
			var resultado = mockMvc.perform(get(rota).header("X-Session-Token", mod.token())).andReturn();
			int status = resultado.getResponse().getStatus();
			if (status != 200) {
				negadas.add(rota + " -> " + status);
			}
		}
		if (!negadas.isEmpty()) {
			org.junit.jupiter.api.Assertions.fail("Rotas negadas ao moderador: " + String.join(", ", negadas));
		}
	}

	@Test
	void moderadorPodeAplicarPunicaoDireta() throws Exception {
		var mod = moderador();
		// O regulamento nega punir a própria conta: a regra de negócio responde, não o guarda —
		// prova de que o moderador passou pela autorização.
		String modUsername = jdbc.queryForObject("select username from users where email = ?",
			String.class, "modperm_mod@test.com");
		mockMvc.perform(post("/admin/users/" + modUsername + "/sanction")
				.header("X-Session-Token", mod.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("type", "RESTRICTION",
					"reason", "Tentativa de autopunição no teste de permissões."))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("CANNOT_SANCTION_SELF"));
	}

	@Test
	void candidaturaContinuaExclusivaDoAdmin() throws Exception {
		var mod = moderador();
		mockMvc.perform(get("/admin/moderator-applications").header("X-Session-Token", mod.token()))
			.andExpect(status().isForbidden());
		mockMvc.perform(post("/admin/moderator-applications/nao-existe/approve")
				.header("X-Session-Token", mod.token()))
			.andExpect(status().isForbidden());
	}

	@Test
	void usuarioComumFicaForaDoPainel() throws Exception {
		var plain = auth.authenticate("modperm_plain");
		mockMvc.perform(get("/admin/appeals").header("X-Session-Token", plain.token()))
			.andExpect(status().isForbidden());
		mockMvc.perform(get("/admin/support").header("X-Session-Token", plain.token()))
			.andExpect(status().isForbidden());
		mockMvc.perform(get("/admin/monitoring/overview").header("X-Session-Token", plain.token()))
			.andExpect(status().isForbidden());
	}

}
