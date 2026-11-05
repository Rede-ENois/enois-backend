package com.socialcoisa.rede.shared.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.ObjectMapper;

public class TestAuthHelper {

	/** Origem do proprio app: os testes que ligam o gate global (rede.origins.enforce) a usam. */
	static final String APP_ORIGIN = "https://enois.ia.br";

	public record AuthSession(String token, String refreshToken, String userId) {}

	private final MockMvc mockMvc;
	private final ObjectMapper objectMapper;

	public TestAuthHelper(MockMvc mockMvc, ObjectMapper objectMapper) {
		this.mockMvc = mockMvc;
		this.objectMapper = objectMapper;
	}

	/**
	 * Cria (se preciso) e ativa a identidade profissional da conta pelos MESMOS endpoints do app:
	 * candidatura e proposta exigem perfil profissional ativo. O @ profissional deriva do id da
	 * conta, entao chamar de novo para a mesma conta nao falha.
	 */
	public String activateProfessional(AuthSession session) throws Exception {
		String username = "pro" + Integer.toUnsignedString(session.userId().hashCode(), 36);
		mockMvc.perform(post("/me/business-profile")
				.header("X-Session-Token", session.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("username", username, "name", "Perfil Profissional"))));
		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/me/business-profile")
				.header("X-Session-Token", session.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("headline", "Pessoa desenvolvedora"))))
			.andExpect(status().isOk());
		mockMvc.perform(post("/me/business-profile/activate").header("X-Session-Token", session.token()))
			.andExpect(status().isOk());
		return username;
	}

	/**
	 * Cria a conta e devolve uma sessao valida.
	 *
	 * O cadastro e em duas etapas desde 2026-10-21: o POST /auth/signup so guarda o pendente e a
	 * conta nasce na confirmacao do e-mail. Como o token cru so existe no e-mail, aqui trocamos o
	 * hash do pendente por um valor conhecido (TestDatabase) e chamamos o MESMO endpoint de
	 * verificacao que o link usaria — a conta nasce verificada e a resposta ja traz a sessao.
	 * Se a conta JA existe (o cadastro e um replay da idempotencia quando o mesmo teste pede a
	 * mesma conta duas vezes), nao ha pendente para confirmar: entra-se pelo login normal.
	 */
	public AuthSession authenticate(String userId) throws Exception {
		String email = userId + "@test.com";

		// Idempotente: a mesma conta pode ser pedida por varios testes da mesma classe. O
		// cadastro e um replay da idempotencia (nao nasce outro pendente), entao a conta que
		// ja existe e usada como esta.
		if (TestDatabase.userIdByEmail(email).isEmpty()) {
			String payload = objectMapper.writeValueAsString(Map.of(
				"name", userId,
				"role", "USER",
				"email", email,
				"password", "Test1234!",
				"acceptedTerms", true,
				"birthDate", "1990-05-10"
			));

				mockMvc.perform(post("/auth/signup")
						.header("X-Client-Origin", APP_ORIGIN)
						.header("Idempotency-Key", "signup-" + userId)
					.contentType(MediaType.APPLICATION_JSON)
					.content(payload))
				.andExpect(status().isCreated());

			// Token conhecido no cadastro pendente: o e-mail nao sai no teste, mas o hash do
			// token fica no banco — e e por ele que o endpoint de confirmacao casa o link.
			String confirmToken = "token-teste-" + userId;
			TestDatabase.setPendingSignupToken(email, confirmToken);

				MvcResult verified = mockMvc.perform(post("/auth/email/verify")
						.header("X-Client-Origin", APP_ORIGIN)
						.header("Idempotency-Key", "verify-" + userId)
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(Map.of("token", confirmToken))))
				.andExpect(status().isOk())
				.andReturn();

			String createdUserId = TestDatabase.userIdByEmail(email)
				.orElseThrow(() -> new IllegalStateException("usuario nao criado: " + email));
			markTestAdmin(email, createdUserId);
			return new AuthSession(sessionToken(verified), refreshToken(verified), createdUserId);
		}

		// A conta ja existia (replay do cadastro): entra pelo login normal.
			MvcResult login = mockMvc.perform(post("/auth/login")
					.header("X-Client-Origin", APP_ORIGIN)
					.header("Idempotency-Key", "login-" + userId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("email", email, "password", "Test1234!"))))
			.andExpect(status().isOk())
			.andReturn();

		String createdUserId = TestDatabase.userIdByEmail(email)
			.orElseThrow(() -> new IllegalStateException("usuario nao criado: " + email));
		markTestAdmin(email, createdUserId);
		return new AuthSession(sessionToken(login), refreshToken(login), createdUserId);
	}

	/** Access token issued by an auth endpoint: it travels only in the HttpOnly cookie. */
	public static String sessionToken(MvcResult result) {
		return cookie(result, SessionCookies.SESSION);
	}

	public static String refreshToken(MvcResult result) {
		return cookie(result, SessionCookies.REFRESH);
	}

	private static String cookie(MvcResult result, String name) {
		jakarta.servlet.http.Cookie cookie = result.getResponse().getCookie(name);
		if (cookie == null || cookie.getValue().isEmpty()) {
			throw new IllegalStateException("cookie de sessao ausente: " + name);
		}
		return cookie.getValue();
	}

	/** O papel admin é uma flag no banco; a conta de teste da equipe é promovida aqui. */
	private static void markTestAdmin(String email, String userId) {
		if ("group-admin@test.com".equalsIgnoreCase(email)) {
			TestDatabase.setAdmin(userId);
		}
	}
}
