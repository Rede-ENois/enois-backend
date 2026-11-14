package com.socialcoisa.rede.identity.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.identity.application.port.out.AuthSessionRepository;
import com.socialcoisa.rede.identity.application.port.out.TokenValidationResult;
import com.socialcoisa.rede.identity.application.port.out.TokenValidator;
import com.socialcoisa.rede.identity.domain.OAuthProvider;
import com.socialcoisa.rede.moderation.application.port.out.SanctionRepository;
import com.socialcoisa.rede.moderation.domain.UserSanction;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {"rede.oauth.google.client-id=sintetico705", "rede.oauth.google.client-secret=sintetico705",
	"rede.oauth.google.redirect-uri=http://localhost:8081/auth/oauth/google/callback",
	"rede.oauth.linkedin.client-id=sintetico705", "rede.oauth.linkedin.client-secret=sintetico705",
	"rede.oauth.linkedin.redirect-uri=http://localhost:8081/auth/oauth/linkedin/callback",
	"rede.oauth.frontend-redirect-url=http://localhost:5173/auth/callback"})
@AutoConfigureMockMvc(print = org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
class OAuthSessaoModeracaoTests {
	@Autowired MockMvc mvc;
	@Autowired ObjectMapper json;
	@Autowired SanctionRepository sancoes;
	@Autowired AuthSessionRepository sessoes;
	@Autowired org.springframework.transaction.support.TransactionTemplate transacao;
	@MockitoBean(name = "googleTokenValidator") TokenValidator provedor;
	@MockitoBean(name = "linkedInTokenValidator") TokenValidator linkedin;
	@Autowired com.socialcoisa.rede.linkedin.application.LinkedInIntegrationService integracao;

	@Test
	void linkedinPreservaDesafioLocalQuandoProvedorDevolveTokenDeIntegracao() throws Exception {
		var titular = new TestAuthHelper(mvc, json).authenticate("oauth705_linkedin");
		com.socialcoisa.rede.shared.web.TestDatabase.enableTwoFactor(titular.userId(), Instant.now());
		sancoes.save(UserSanction.suspended("sanc_oauth705_linkedin", titular.userId(), "admin:705", "Conduta repetida.", Instant.now()));
		transacao.executeWithoutResult(ignorado -> sessoes.revokeAllByUser(titular.userId(), Instant.now()));
		when(linkedin.validate(eq(OAuthProvider.LINKEDIN), anyString(), anyString(), anyString()))
			.thenReturn(Optional.of(new TokenValidationResult("sub-linkedin705", "oauth705_linkedin@test.com",
				"Pessoa sintetica", true, "integracao-sintetica-nao-funcional705", Instant.now().plusSeconds(3600))));
		var inicio = mvc.perform(get("/auth/oauth/linkedin/start")).andExpect(status().isOk()).andReturn();
		String estado = json.readTree(inicio.getResponse().getContentAsString()).path("state").asText();
		var resposta = mvc.perform(get("/auth/oauth/linkedin/callback").param("code", "codigo-sintetico705").param("state", estado))
			.andExpect(status().isFound()).andReturn();
		assertThat(resposta.getResponse().getHeader("Location"))
			.isEqualTo("http://localhost:5173/auth/callback?status=two_factor_required");
		assertThat(resposta.getResponse().getCookie("enois_session").getValue()).isEmpty();
		assertThat(integracao.status(titular.userId()).connected()).isTrue();
		mvc.perform(get("/auth/2fa/challenge").cookie(resposta.getResponse().getCookie("enois_2fa_challenge")))
			.andExpect(status().isOk()).andExpect(jsonPath("$.method").value("TOTP"));
	}

	@Test
	void provedorNaoContornaSegundoFatorLocalAntesDeRevelarSuspensao() throws Exception {
		var titular = new TestAuthHelper(mvc, json).authenticate("oauth705_doisfatores");
		var setup = mvc.perform(post("/auth/2fa/totp/setup").header("X-Session-Token", titular.token())
			.contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"password\":\"Test1234!\"}"))
			.andExpect(status().isOk()).andReturn();
		String segredo = json.readTree(setup.getResponse().getContentAsString()).path("secret").asText();
		String codigo = com.socialcoisa.rede.shared.security.Totp.codeAt(segredo, Instant.now().getEpochSecond() / 30);
		var ativado = mvc.perform(post("/auth/2fa/totp/enable").header("X-Session-Token", titular.token())
			.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
			.content(json.writeValueAsString(java.util.Map.of("password", "Test1234!", "code", codigo))))
			.andExpect(status().isOk()).andReturn();
		String recuperacao = json.readTree(ativado.getResponse().getContentAsString()).path("backupCodes").get(0).asText();
		sancoes.save(UserSanction.suspended("sanc_oauth705_2fa", titular.userId(), "admin:decisao705", "Conduta repetida.", Instant.now()));
		transacao.executeWithoutResult(ignorado -> sessoes.revokeAllByUser(titular.userId(), Instant.now()));
		validar("oauth705_doisfatores", "sub-oauth705-2fa");
		var senha = mvc.perform(post("/auth/login").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
			.content("{\"email\":\"oauth705_doisfatores@test.com\",\"password\":\"Test1234!\"}"))
			.andExpect(status().isAccepted()).andExpect(jsonPath("$.twoFactorRequired").value(true))
			.andExpect(jsonPath("$.accessScope").doesNotExist()).andReturn();
		assertThat(senha.getResponse().getCookies()).isEmpty();
		String desafioSenha = json.readTree(senha.getResponse().getContentAsString()).path("challengeId").asText();
		mvc.perform(get("/me/sanctions").header("X-Session-Token", desafioSenha)).andExpect(status().isUnauthorized());
		String segundaRecuperacao = json.readTree(ativado.getResponse().getContentAsString()).path("backupCodes").get(1).asText();
		mvc.perform(post("/auth/2fa/verify").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
			.content(json.writeValueAsString(java.util.Map.of("challengeId", desafioSenha, "code", segundaRecuperacao))))
			.andExpect(status().isOk()).andExpect(jsonPath("$.accessScope").value("SANCTIONS_ONLY"));
		var parcial = callback();
		assertThat(parcial.getResponse().getHeader("Location"))
			.isEqualTo("http://localhost:5173/auth/callback?status=two_factor_required");
		assertThat(parcial.getResponse().getCookie("enois_session").getValue()).isEmpty();
		var cookie = parcial.getResponse().getCookie("enois_2fa_challenge");
		assertThat(cookie.isHttpOnly()).isTrue();
		var desafio = mvc.perform(get("/auth/2fa/challenge").cookie(cookie)).andExpect(status().isOk())
			.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.method").value("TOTP")).andExpect(jsonPath("$.userId").doesNotExist()).andReturn();
		String id = json.readTree(desafio.getResponse().getContentAsString()).path("challengeId").asText();
		mvc.perform(get("/me/sanctions").cookie(cookie)).andExpect(status().isUnauthorized());
		mvc.perform(post("/auth/2fa/verify").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
			.content(json.writeValueAsString(java.util.Map.of("challengeId", id, "code", "invalido"))))
			.andExpect(status().isBadRequest());
		var confirmado = mvc.perform(post("/auth/2fa/verify").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
			.content(json.writeValueAsString(java.util.Map.of("challengeId", id, "code", recuperacao))))
			.andExpect(status().isOk()).andExpect(jsonPath("$.accessScope").value("SANCTIONS_ONLY")).andReturn();
		mvc.perform(get("/me/sanctions").header("X-Session-Token", TestAuthHelper.sessionToken(confirmado)))
			.andExpect(status().isOk());
	}

	@Test
	void provedorValidadoNaoRecuperaAcessoNormalDeContaSuspensa() throws Exception {
		var titular = new TestAuthHelper(mvc, json).authenticate("oauth705_titular");
		sancoes.save(UserSanction.suspended("sanc_oauth705", titular.userId(), "admin:decisao705", "Conduta repetida.", Instant.now()));
		transacao.executeWithoutResult(ignorado -> sessoes.revokeAllByUser(titular.userId(), Instant.now()));
		validar("oauth705_titular", "sub-oauth705");
		var resposta = callback();
		assertThat(resposta.getResponse().getHeader("Location"))
			.isEqualTo("http://localhost:5173/auth/callback?status=ok&accessScope=SANCTIONS_ONLY");
		String token = TestAuthHelper.sessionToken(resposta);
		assertThat(sessoes.findActiveUserId(token, Instant.now())).isEmpty();
		mvc.perform(get("/me/sanctions").header("X-Session-Token", token)).andExpect(status().isOk());
		mvc.perform(get("/auth/me").header("X-Session-Token", token)).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ACCOUNT_SUSPENDED"));
		sancoes.save(sancoes.findById("sanc_oauth705").orElseThrow().escalateToBan());
		var banido = callback();
		assertThat(banido.getResponse().getHeader("Location")).contains("error=ACCOUNT_BANNED");
		assertThat(banido.getResponse().getCookies()).isEmpty();
	}

	private void validar(String nome, String sub) {
		when(provedor.validate(eq(OAuthProvider.GOOGLE), anyString(), anyString(), anyString()))
			.thenReturn(Optional.of(new TokenValidationResult(sub, nome + "@test.com", "Pessoa sintetica", true)));
	}

	private MvcResult callback() throws Exception {
		var inicio = mvc.perform(get("/auth/oauth/google/start")).andExpect(status().isOk()).andReturn();
		String estado = json.readTree(inicio.getResponse().getContentAsString()).path("state").asText();
		return mvc.perform(get("/auth/oauth/google/callback").param("code", "codigo-sintetico705").param("state", estado))
			.andExpect(status().isFound()).andReturn();
	}
}
