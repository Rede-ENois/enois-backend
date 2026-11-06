package com.socialcoisa.rede.identity;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.ObjectMapper;

import com.socialcoisa.rede.network.application.port.out.ConnectionRepository;
import com.socialcoisa.rede.network.domain.ConnectionStatus;
import com.socialcoisa.rede.network.domain.UserConnection;
import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestDatabase;

/**
 * Política de idade (ECA Digital, Lei 15.211/2025): a data de nascimento é obrigatória no
 * cadastro, a base antiga regulariza pelo fluxo próprio, escritas ficam bloqueadas sem a data
 * e um menor de 18 não recebe mensagem direta de quem não é conexão aceita.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AgePolicyTests {

	private static final ZoneId BRAZIL = ZoneId.of("America/Sao_Paulo");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private ConnectionRepository connections;

	@Autowired
	private org.springframework.jdbc.core.JdbcTemplate jdbc;

	/** Data que faz a pessoa ter 15 anos hoje (menor de teste). */
	private static String minorBirthDate() {
		return LocalDate.now(BRAZIL).minusYears(15).toString();
	}

	private String signup(String username, String birthDate) throws Exception {
		Map<String, Object> payload = Map.of(
			"name", username,
			"role", "USER",
			"email", username + "@test.com",
			"password", "Test1234!",
			"acceptedTerms", true,
			"birthDate", birthDate);
		MvcResult result = mockMvc.perform(post("/auth/signup")
				.header("Idempotency-Key", "age-signup-" + username)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(payload)))
			.andReturn();
		return result.getResponse().getContentAsString();
	}

	@Test
	void signupWithoutBirthDateIsRejected() throws Exception {
		Map<String, Object> payload = Map.of(
			"name", "age_semdatanasc",
			"role", "USER",
			"email", "age_semdatanasc@test.com",
			"password", "Test1234!",
			"acceptedTerms", true);
		mockMvc.perform(post("/auth/signup")
				.header("Idempotency-Key", "age-signup-nodata")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(payload)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("BIRTH_DATE_REQUIRED"));
	}

	@Test
	void futureOrImplausibleBirthDateIsRejected() throws Exception {
		String future = signup("age_futuro", LocalDate.now(BRAZIL).plusYears(1).toString());
		org.assertj.core.api.Assertions.assertThat(future).contains("INVALID_BIRTH_DATE");

		String ancient = signup("age_antigo", "1800-01-01");
		org.assertj.core.api.Assertions.assertThat(ancient).contains("INVALID_BIRTH_DATE");
	}

	@Test
	void minorIsFlaggedByTheBackendAndAdultIsNot() throws Exception {
		String adultResponse = signup("age_adulto_ok", "1990-05-10");
		org.assertj.core.api.Assertions.assertThat(adultResponse).contains("verificationRequired");

		String minorResponse = signup("age_menor_ok", minorBirthDate());
		org.assertj.core.api.Assertions.assertThat(minorResponse).contains("verificationRequired");

		// Confirmar e-mail e entrar para consultar o estado
		String adultToken = login("age_adulto_ok");
		mockMvc.perform(get("/me/age-state").header("X-Session-Token", adultToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("ADULT_DECLARED"));

		String minorToken = login("age_menor_ok");
		mockMvc.perform(get("/me/age-state").header("X-Session-Token", minorToken))
			.andExpect(status().isOk())
			// O estado do menor e MINOR_CONSENT_PENDING: com a supervisao parental (V137) a conta
			// do menor espera o consentimento do responsavel (MINOR_SUPERVISED quando ja tem).
			.andExpect(jsonPath("$.state").value("MINOR_CONSENT_PENDING"))
			.andExpect(jsonPath("$.minor").value(true));
	}

	@Test
	void regularizationUnlocksWriting() throws Exception {
		// Fluxo da base antiga: usuário criado (com data, pelo helper) e depois "esvaziado"
		// para simular quem ainda não regularizou.
		var user = new TestAuthHelper(mockMvc, objectMapper).authenticate("age_reguser");
		jdbc.update("UPDATE users SET birth_date = NULL WHERE id = ?", user.userId());

		// Publicar sem data declarada: bloqueado com o código que abre a tela de regularização.
		mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", user.token())
				.header("Idempotency-Key", "age-reg-post-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA", "type", "INSIGHT", "content",
					"texto de teste da regularizacao"))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("BIRTH_DATE_REQUIRED"));

		// Regulariza e a escrita volta.
		mockMvc.perform(post("/me/birth-date")
				.header("X-Session-Token", user.token())
				.header("Idempotency-Key", "age-reg-declare")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("birthDate", "1995-03-15"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("ADULT_DECLARED"));

		mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", user.token())
				.header("Idempotency-Key", "age-reg-post-2")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA", "type", "INSIGHT", "content",
					"depois da regularizacao funciona"))))
			.andExpect(status().isCreated());
	}

	@Test
	void strangerCannotMessageMinorButConnectionCan() throws Exception {
		var stranger = new TestAuthHelper(mockMvc, objectMapper).authenticate("age_stranger");
		var minor = new TestAuthHelper(mockMvc, objectMapper).authenticate("age_minor_target");

		// Forçar o alvo a ser menor de 18 (15 anos) direto na base.
		jdbc.update("UPDATE users SET birth_date = ? WHERE id = ?", minorBirthDate(), minor.userId());

		// Conversa direta de um desconhecido com o menor: nem abre (a conversa vazia já seria um
		// contato visível na caixa de entrada do menor).
		mockMvc.perform(post("/conversations/direct")
				.header("X-Session-Token", stranger.token())
				.header("Idempotency-Key", "age-dm-create")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("userId", minor.userId()))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("MINOR_DM_RESTRICTED"));

		// Com conexão ACEITA entre os dois, a mensagem passa (ex.: colega de turma da escola).
		connections.save(UserConnection.restore(null, minor.userId(), stranger.userId(),
			ConnectionStatus.ACCEPTED, java.time.Instant.now(), java.time.Instant.now(), null));
		MvcResult conversation = mockMvc.perform(post("/conversations/direct")
				.header("X-Session-Token", stranger.token())
				.header("Idempotency-Key", "age-dm-create-connected")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("userId", minor.userId()))))
			.andExpect(status().isOk())
			.andReturn();
		String conversationId = objectMapper.readTree(conversation.getResponse().getContentAsString())
			.get("id").asText();

		mockMvc.perform(post("/conversations/" + conversationId + "/messages")
				.header("X-Session-Token", stranger.token())
				.header("Idempotency-Key", "age-dm-msg-2")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("text", "oi, colega de turma!"))))
			// A criacao da mensagem responde 201 (Created) — o teste ainda esperava 200.
			.andExpect(status().isCreated());
	}

	/**
	 * Entra na conta: no cadastro de DUAS etapas a conta so nasce quando o e-mail e confirmado,
	 * entao o login direto da 401. Aqui o link e confirmado com um token conhecido (o mesmo
	 * truque do TestAuthHelper) e a sessao vem da resposta da confirmacao.
	 */
	private String login(String username) throws Exception {
		String email = username + "@test.com";
		String confirmToken = "token-teste-" + username;
		TestDatabase.setPendingSignupToken(email, confirmToken);
		MvcResult confirmed = mockMvc.perform(post("/auth/email/verify")
				.header("Idempotency-Key", "age-verify-" + username)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("token", confirmToken))))
			.andExpect(status().isOk())
			.andReturn();
		return com.socialcoisa.rede.shared.web.TestAuthHelper.sessionToken(confirmed);
	}

}
