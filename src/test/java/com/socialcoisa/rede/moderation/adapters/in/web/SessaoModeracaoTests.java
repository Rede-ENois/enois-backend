package com.socialcoisa.rede.moderation.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.identity.application.port.out.AuthSessionRepository;
import com.socialcoisa.rede.moderation.application.port.out.SanctionRepository;
import com.socialcoisa.rede.shared.web.SessionCookies;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {"rede.admin.emails=sessao705_admin@test.com",
	"rede.moderation.suspension.expiry-initial-delay-ms=2000000000"})
@AutoConfigureMockMvc(print = org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
class SessaoModeracaoTests {
	@Autowired MockMvc mvc;
	@Autowired ObjectMapper json;
	@Autowired AuthSessionRepository sessoes;
	@Autowired SanctionRepository sancoes;
	@Autowired Clock relogio;

	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.ValueSource(strings = {"BAN", "PERMABAN"})
	void conversaoAdministrativaRevogaTambemSessaoLimitada(String tipo) throws Exception {
		String nome = "sessao705_conversao_" + tipo.toLowerCase(java.util.Locale.ROOT);
		var titular = new TestAuthHelper(mvc, json).authenticate(nome);
		var admin = new TestAuthHelper(mvc, json).authenticate("sessao705_admin");
		suspender(nome.replace('_', '-'), admin.token());
		var cookie = new Cookie(SessionCookies.SESSION, TestAuthHelper.sessionToken(entrar(nome)));
		mvc.perform(get("/me/sanctions").cookie(cookie)).andExpect(status().isOk());
		String id = sancoes.findActiveByUser(titular.userId()).orElseThrow().id();
		mvc.perform(post("/admin/sanctions/" + id + "/edit").header("X-Session-Token", admin.token())
			.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("type", tipo,
				"reason", "Decisao administrativa definitiva.")))).andExpect(status().isOk());
		mvc.perform(get("/me/sanctions").cookie(cookie)).andExpect(status().isUnauthorized());
		assertThat(sessoes.findActiveSanctionsUserId(cookie.getValue(), relogio.instant())).isEmpty();
	}

	@Test
	void aliasVersionadoPreservaConsultaRecursoLogoutERecusaAcessoNormal() throws Exception {
		var titular = new TestAuthHelper(mvc, json).authenticate("sessao705_alias");
		var admin = new TestAuthHelper(mvc, json).authenticate("sessao705_admin");
		suspender("sessao705-alias", admin.token());
		var cookie = new Cookie(SessionCookies.SESSION, TestAuthHelper.sessionToken(entrar("sessao705_alias")));
		mvc.perform(get("/v1/me/sanctions").cookie(cookie)).andExpect(status().isOk())
			.andExpect(jsonPath("$.sanctions[0].userId").value(titular.userId()));
		mvc.perform(get("/v1/auth/me").cookie(cookie)).andExpect(status().isForbidden());
		mvc.perform(post("/v1/me/sanctions/appeals").cookie(cookie).header("X-CSRF-Protection", "1")
			.contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"Solicito revisao pelo alias.\"}"))
			.andExpect(status().isOk());
		mvc.perform(post("/v1/auth/logout").cookie(cookie).header("X-CSRF-Protection", "1")
			.header("Idempotency-Key", "sessao705_alias_logout")).andExpect(status().isOk());
		mvc.perform(get("/me/sanctions").cookie(cookie)).andExpect(status().isUnauthorized());
	}

	@Test
	void autenticaTitularAposRevogacaoSomenteParaConsultaERecurso() throws Exception {
		var titular = new TestAuthHelper(mvc, json).authenticate("sessao705_titular");
		var outro = new TestAuthHelper(mvc, json).authenticate("sessao705_outro");
		var admin = new TestAuthHelper(mvc, json).authenticate("sessao705_admin");
		suspender("sessao705-titular", admin.token());
		mvc.perform(get("/me/sanctions").header("X-Session-Token", titular.token()))
			.andExpect(status().isUnauthorized());
		mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content(json.writeValueAsString(Map.of("email", "sessao705_titular@test.com", "password", "Incorreta705!"))))
			.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

		var login = entrar("sessao705_titular");
		String token = TestAuthHelper.sessionToken(login);
		assertThat(login.getResponse().getContentAsString()).doesNotContain(token, "refreshToken");
		assertThat(sessoes.findActiveUserId(token, relogio.instant())).isEmpty();
		Cookie cookie = new Cookie(SessionCookies.SESSION, token);
		mvc.perform(get("/me/sanctions").cookie(cookie))
			.andExpect(status().isOk()).andExpect(jsonPath("$.sanctions[0].userId").value(titular.userId()))
			.andExpect(jsonPath("$.sanctions[0].canAppeal").value(true));
		mvc.perform(get("/me/sanctions").header("X-Session-Token", outro.token()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.sanctions").isEmpty());
		for (String rota : List.of("/auth/me", "/auth/2fa/status", "/conversations", "/admin/appeals")) {
			mvc.perform(get(rota).cookie(cookie)).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ACCOUNT_SUSPENDED"));
		}
		mvc.perform(post("/feed/posts").cookie(cookie).header("X-CSRF-Protection", "1")
			.contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCOUNT_SUSPENDED"));
		mvc.perform(post("/auth/refresh").cookie(new Cookie(SessionCookies.REFRESH, titular.refreshToken()))
			.header("X-CSRF-Protection", "1")).andExpect(status().isUnauthorized());
		mvc.perform(post("/me/sanctions/appeals").cookie(cookie)
			.contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"Solicito revisao desta decisao.\"}"))
			.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_REQUIRED"));
		var recurso = mvc.perform(post("/me/sanctions/appeals").cookie(cookie).header("X-CSRF-Protection", "1")
			.contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"Solicito revisao desta decisao.\"}"))
			.andExpect(status().isOk()).andReturn();
		String id = json.readTree(recurso.getResponse().getContentAsString()).path("id").asText();
		mvc.perform(post("/me/sanctions/appeals").cookie(cookie).header("X-CSRF-Protection", "1")
			.contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"Solicito revisao novamente.\"}"))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("APPEAL_ALREADY_PENDING"));
		mvc.perform(get("/me/sanctions").cookie(cookie))
			.andExpect(status().isOk()).andExpect(jsonPath("$.sanctions[0].canAppeal").value(false))
			.andExpect(jsonPath("$.appeals[0].status").value("PENDING"));
		mvc.perform(post("/admin/appeals/" + id + "/approve").header("X-Session-Token", admin.token()))
			.andExpect(status().isOk());
		mvc.perform(get("/me/sanctions").cookie(cookie)).andExpect(status().isOk())
			.andExpect(jsonPath("$.sanctions[0].status").value("REMOVED"));
		mvc.perform(get("/auth/me").cookie(cookie)).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("SESSION_RESTRICTED"));
		mvc.perform(post("/auth/logout").cookie(cookie).header("X-CSRF-Protection", "1")
			.header("Idempotency-Key", "sessao705_logout"))
			.andExpect(status().isOk());
		mvc.perform(get("/me/sanctions").cookie(cookie)).andExpect(status().isUnauthorized());
	}

	private MvcResult entrar(String nome) throws Exception {
		return mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content(json.writeValueAsString(Map.of("email", nome + "@test.com", "password", "Test1234!"))))
			.andExpect(status().isOk()).andExpect(jsonPath("$.accessScope").value("SANCTIONS_ONLY")).andReturn();
	}

	private void suspender(String usuario, String admin) throws Exception {
		mvc.perform(post("/admin/users/" + usuario + "/sanction").header("X-Session-Token", admin)
			.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
				"type", "SUSPENDED", "reason", "Conduta repetida contraria as diretrizes."))))
			.andExpect(status().isOk());
	}
}
