package com.socialcoisa.rede.profile.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.JsonNode;
import com.socialcoisa.rede.shared.web.TestDatabase;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class ProfileControllerTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private String sessionToken;
	private String userId;

	@BeforeEach
	void setUp() throws Exception {
		String unique = "prof_" + System.nanoTime();
		String email = unique + "@example.com";
		// O cadastro exige a data de nascimento (ECA) e é em duas etapas: a conta só nasce
		// quando o e-mail é confirmado.
		String signupPayload = objectMapper.writeValueAsString(Map.of(
			"name", "Profile Test User",
			"role", "Engineer",
			"email", email,
			"password", "senha-segura",
			"acceptedTerms", true,
			"birthDate", "1990-05-10"));

		mockMvc.perform(post("/auth/signup")
				.header("Idempotency-Key", "prof-setup-" + unique)
				.contentType(MediaType.APPLICATION_JSON)
				.content(signupPayload))
			.andExpect(status().isCreated());

		// Token conhecido no cadastro pendente: o e-mail nao sai no teste.
		String confirmToken = "token-prof-" + unique;
		TestDatabase.setPendingSignupToken(email, confirmToken);
		mockMvc.perform(post("/auth/email/verify")
				.header("Idempotency-Key", "prof-verify-" + unique)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("token", confirmToken))))
			.andExpect(status().isOk());

		// Conta nascida e verificada: entramos pelo login normal.
		userId = TestDatabase.userIdByEmail(email).orElseThrow();
		TestDatabase.markEmailVerified(userId, java.time.Instant.now());

		MvcResult login = mockMvc.perform(post("/auth/login")
				.header("Idempotency-Key", "prof-login-" + unique)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("email", email, "password", "senha-segura"))))
			.andExpect(status().isOk())
			.andReturn();

		sessionToken = com.socialcoisa.rede.shared.web.TestAuthHelper.sessionToken(login);
	}

	@Test
	void rejectsFieldOverTheLimitWithAClearMessage() throws Exception {
		// Bio acima do limite: precisa dizer QUAL campo passou, nao virar um 409 generico (a tela
		// antiga ainda rotulava isso como "nome de usuario ja esta em uso").
		String payload = objectMapper.writeValueAsString(Map.of("bio", "a".repeat(2000)));

		mockMvc.perform(patch("/me/profile")
				.header("X-Session-Token", sessionToken)
				.header("Idempotency-Key", "profile-bio-too-long")
				.contentType(MediaType.APPLICATION_JSON)
				.content(payload))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("FIELD_TOO_LONG"))
			.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("bio")))
			.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("1024")));
	}

	@Test
	void updatesProfile() throws Exception {
		String profilePayload = objectMapper.writeValueAsString(Map.of(
			"headline", "Senior Engineer",
			"company", "Connect Grow",
			"location", "Remoto",
			"bio", "Fullstack developer",
			"skills", List.of("Java", "Spring"),
			"openToWork", true));

		mockMvc.perform(patch("/me/profile")
				.header("X-User-Id", userId)
				.header("X-Session-Token", sessionToken)
				.header("Idempotency-Key", "prof-update-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(profilePayload))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.headline").value("Senior Engineer"))
			.andExpect(jsonPath("$.company").value("Connect Grow"))
			.andExpect(jsonPath("$.skills[0]").value("Java"));

		mockMvc.perform(get("/me")
				.header("X-User-Id", userId)
				.header("X-Session-Token", sessionToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.headline").value("Senior Engineer"));
	}

	@Test
	void managesExperiences() throws Exception {
		String expPayload = objectMapper.writeValueAsString(Map.of(
			"title", "Software Engineer",
			"company", "Tech Co",
			"startDate", "2020-01-01"));

		MvcResult expResult = mockMvc.perform(post("/me/experiences")
				.header("X-User-Id", userId)
				.header("X-Session-Token", sessionToken)
				.header("Idempotency-Key", "prof-exp-create-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(expPayload))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.title").value("Software Engineer"))
			.andReturn();

		String expId = objectMapper.readTree(expResult.getResponse().getContentAsString()).get("id").asText();

		String updateExpPayload = objectMapper.writeValueAsString(Map.of(
			"title", "Senior Software Engineer",
			"company", "Tech Co",
			"startDate", "2020-01-01"));

		mockMvc.perform(put("/me/experiences/{id}", expId)
				.header("X-User-Id", userId)
				.header("X-Session-Token", sessionToken)
				.header("Idempotency-Key", "prof-exp-update-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(updateExpPayload))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.title").value("Senior Software Engineer"));

		// A edição precisa estar no BANCO, não só na resposta: um GET novo tem que trazer o cargo novo.
		mockMvc.perform(get("/me/experiences")
				.header("X-User-Id", userId)
				.header("X-Session-Token", sessionToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].title").value("Senior Software Engineer"));

		mockMvc.perform(delete("/me/experiences/{id}", expId)
				.header("X-User-Id", userId)
				.header("X-Session-Token", sessionToken)
				.header("Idempotency-Key", "prof-exp-delete-1"))
			.andExpect(status().isOk());

		mockMvc.perform(get("/me/experiences")
				.header("X-User-Id", userId)
				.header("X-Session-Token", sessionToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$").isEmpty());
	}

	@Test
	void publicExperiencesAreOrderedMostRecentFirst() throws Exception {
		mockMvc.perform(post("/me/experiences")
				.header("X-User-Id", userId)
				.header("X-Session-Token", sessionToken)
				.header("Idempotency-Key", "prof-exp-public-old")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"title", "Software Engineer",
					"company", "Tech Co",
					"location", "Remoto",
					"description", "APIs em Java e Spring.",
					"startDate", "2018-01-01",
					"endDate", "2020-12-31"))))
			.andExpect(status().isCreated());

		mockMvc.perform(post("/me/experiences")
				.header("X-User-Id", userId)
				.header("X-Session-Token", sessionToken)
				.header("Idempotency-Key", "prof-exp-public-current")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"title", "Tech Lead",
					"company", "É Nóis",
					"startDate", "2024-03-01"))))
			.andExpect(status().isCreated());

		String username = objectMapper.readTree(mockMvc.perform(get("/me")
				.header("X-User-Id", userId)
				.header("X-Session-Token", sessionToken))
			.andReturn().getResponse().getContentAsString()).get("username").asText();

		// Leitura pública (sem sessão), do mais recente para o mais antigo.
		mockMvc.perform(get("/users/" + username + "/experiences"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[0].title").value("Tech Lead"))
			.andExpect(jsonPath("$[0].currentlyHere").value(true))
			.andExpect(jsonPath("$[1].title").value("Software Engineer"))
			.andExpect(jsonPath("$[1].location").value("Remoto"))
			.andExpect(jsonPath("$[1].description").value("APIs em Java e Spring."))
			.andExpect(jsonPath("$[1].endDate").value("2020-12-31"));

		mockMvc.perform(get("/users/perfil-que-nao-existe-" + System.nanoTime() + "/experiences"))
			.andExpect(status().isNotFound());
	}

	@Test
	void managesEducation() throws Exception {
		String eduPayload = objectMapper.writeValueAsString(Map.of(
			"school", "USP",
			"degree", "BS",
			"field", "Computer Science",
			"startDate", "2015-01-01",
			"endDate", "2019-12-31"));

		MvcResult eduResult = mockMvc.perform(post("/me/education")
				.header("X-User-Id", userId)
				.header("X-Session-Token", sessionToken)
				.header("Idempotency-Key", "prof-edu-create-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(eduPayload))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.school").value("USP"))
			.andReturn();

		String eduId = objectMapper.readTree(eduResult.getResponse().getContentAsString()).get("id").asText();

		mockMvc.perform(put("/me/education/{id}", eduId)
				.header("X-User-Id", userId)
				.header("X-Session-Token", sessionToken)
				.header("Idempotency-Key", "prof-edu-update-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"school", "USP",
					"degree", "MS",
					"field", "Computer Science",
					"startDate", "2015-01-01",
					"endDate", "2019-12-31"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.degree").value("MS"));

		mockMvc.perform(delete("/me/education/{id}", eduId)
				.header("X-User-Id", userId)
				.header("X-Session-Token", sessionToken)
				.header("Idempotency-Key", "prof-edu-delete-1"))
			.andExpect(status().isOk());
	}

	@Test
	void managesCertifications() throws Exception {
		String certPayload = objectMapper.writeValueAsString(Map.of(
			"name", "AWS Certified",
			"issuer", "Amazon",
			"issueDate", "2023-06-01"));

		MvcResult certResult = mockMvc.perform(post("/me/certifications")
				.header("X-User-Id", userId)
				.header("X-Session-Token", sessionToken)
				.header("Idempotency-Key", "prof-cert-create-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(certPayload))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.name").value("AWS Certified"))
			.andReturn();

		String certId = objectMapper.readTree(certResult.getResponse().getContentAsString()).get("id").asText();

		mockMvc.perform(put("/me/certifications/{id}", certId)
				.header("X-User-Id", userId)
				.header("X-Session-Token", sessionToken)
				.header("Idempotency-Key", "prof-cert-update-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"name", "AWS Certified Solutions Architect",
					"issuer", "Amazon",
					"issueDate", "2023-06-01"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("AWS Certified Solutions Architect"));

		mockMvc.perform(delete("/me/certifications/{id}", certId)
				.header("X-User-Id", userId)
				.header("X-Session-Token", sessionToken)
				.header("Idempotency-Key", "prof-cert-delete-1"))
			.andExpect(status().isOk());
	}

	@Test
	void viewsPublicProfile() throws Exception {
		mockMvc.perform(get("/me")
				.header("X-User-Id", userId)
				.header("X-Session-Token", sessionToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.username").isNotEmpty());
	}

	/** O id interno nao e identificador publico: sem sessao, a rota nao devolve perfil. */
	@Test
	void profileByIdRequiresSession() throws Exception {
		mockMvc.perform(get("/users/by-id/" + userId))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/users/by-id/" + userId)
				.header("X-Session-Token", sessionToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(userId));
	}

	/**
	 * O ranking público virou a vitrine de CHEGADA (2026-10-20): quem entrou por último aparece
	 * primeiro, e cada item diz desde quando está aqui (`joinedAt`) para a tela deixar isso claro.
	 */
	@Test
	void rankingListsTheNewestProfilesFirst() throws Exception {
		var auth = new com.socialcoisa.rede.shared.web.TestAuthHelper(mockMvc, objectMapper);
		String suffix = String.valueOf(System.nanoTime());
		var older = auth.authenticate("rank_older_" + suffix);
		// O created_at tem precisão de microssegundos, mas um respiro evita empate na ordenação.
		Thread.sleep(20);
		var newer = auth.authenticate("rank_newer_" + suffix);

		MvcResult result = mockMvc.perform(get("/profile/ranking").param("limit", "50"))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode items = objectMapper.readTree(result.getResponse().getContentAsString());

		int olderIndex = indexOfUser(items, older.userId());
		int newerIndex = indexOfUser(items, newer.userId());
		org.assertj.core.api.Assertions.assertThat(olderIndex).as("perfil antigo na lista")
			.isGreaterThanOrEqualTo(0);
		org.assertj.core.api.Assertions.assertThat(newerIndex).as("perfil novo na lista")
			.isGreaterThanOrEqualTo(0);
		org.assertj.core.api.Assertions.assertThat(newerIndex).as("quem chegou por último vem primeiro")
			.isLessThan(olderIndex);
		org.assertj.core.api.Assertions.assertThat(items.get(newerIndex).path("joinedAt").asString())
			.as("a tela mostra há quanto tempo a pessoa chegou").isNotBlank();
	}

	/**
	 * Conta banida sai da vitrine de chegada: depois da limpeza dos cadastros em série
	 * (2026-10-22) as contas punidas continuavam aparecendo como "chegando agora".
	 */
	@Test
	void bannedProfilesDisappearFromThePublicProfile() throws Exception {
		var auth = new com.socialcoisa.rede.shared.web.TestAuthHelper(mockMvc, objectMapper);
		String suffix = String.valueOf(System.nanoTime());
		auth.authenticate("gone_banned_" + suffix);
		var admin = auth.authenticate("group-admin");

		mockMvc.perform(post("/admin/users/gone-banned-" + suffix + "/sanction")
				.header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"type", "BAN", "reason", "Cadastro em série na triagem de contas."))))
			.andExpect(status().isOk());

		mockMvc.perform(get("/users/gone-banned-" + suffix))
			.andExpect(status().isNotFound());
	}

	@Test
	void bannedProfilesLeaveTheRanking() throws Exception {
		var auth = new com.socialcoisa.rede.shared.web.TestAuthHelper(mockMvc, objectMapper);
		String suffix = String.valueOf(System.nanoTime());
		var banned = auth.authenticate("rank_banned_" + suffix);
		var admin = auth.authenticate("group-admin");

		mockMvc.perform(post("/admin/users/rank-banned-" + suffix + "/sanction")
				.header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"type", "BAN", "reason", "Cadastro em série na triagem de contas."))))
			.andExpect(status().isOk());

		MvcResult result = mockMvc.perform(get("/profile/ranking").param("limit", "50"))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode items = objectMapper.readTree(result.getResponse().getContentAsString());
		org.assertj.core.api.Assertions.assertThat(indexOfUser(items, banned.userId()))
			.as("conta banida não aparece na vitrine").isEqualTo(-1);
	}

	private int indexOfUser(JsonNode items, String userId) {
		for (int i = 0; i < items.size(); i++) {
			if (userId.equals(items.get(i).path("id").asString())) {
				return i;
			}
		}
		return -1;
	}

	@Test
	void rankingEOpcionalPorPreferenciaDaConta() throws Exception {
		// Padrao: mostra (compatibilidade com quem ja usava).
		mockMvc.perform(get("/me").header("X-Session-Token", sessionToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.showRanking").value(true));

		mockMvc.perform(put("/me/ranking")
				.header("X-Session-Token", sessionToken)
				.header("Idempotency-Key", "ranking-off-" + userId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("show", false))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("RANKING_HIDDEN"));

		mockMvc.perform(get("/me").header("X-Session-Token", sessionToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.showRanking").value(false));

		mockMvc.perform(put("/me/ranking")
				.header("X-Session-Token", sessionToken)
				.header("Idempotency-Key", "ranking-on-" + userId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("show", true))))
			.andExpect(status().isOk());

		mockMvc.perform(get("/me").header("X-Session-Token", sessionToken))
			.andExpect(jsonPath("$.showRanking").value(true));
	}
}