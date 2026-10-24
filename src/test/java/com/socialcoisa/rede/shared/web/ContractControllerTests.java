package com.socialcoisa.rede.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
	// Mesma configuracao de qa/prd: doc da API desligado (o gateway expoe o backend sem auth).
	"springdoc.api-docs.enabled=false",
	"springdoc.swagger-ui.enabled=false",
})
@AutoConfigureMockMvc
class ContractControllerTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	private String userToken;

	@Test
	void doesNotExposeApiDocumentation() throws Exception {
		// O doc da API nao pode ficar publico: o gateway expoe o backend em /api/* sem autenticacao.
		mockMvc.perform(get("/openapi.json")).andExpect(status().isNotFound());
		mockMvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
	}

	@Test
	void doesNotExposeActuatorBeyondHealth() throws Exception {
		mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
		mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isNotFound());
		mockMvc.perform(get("/actuator/metrics")).andExpect(status().isNotFound());
	}

	@Test
	void signupRequiresTermsAcceptance() throws Exception {
		mockMvc.perform(post("/auth/signup")
				.header("Idempotency-Key", "terms-required")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Sem Termos\",\"role\":\"USER\",\"email\":\"sem-termos@example.com\","
					+ "\"password\":\"Test1234!\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("TERMS_NOT_ACCEPTED"));
	}

	@Test
	void supportRequestsRequireAuthAndValidateInput() throws Exception {
		var session = auth.authenticate("support-contract");

		mockMvc.perform(post("/support/requests")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"subject\":\"Ajuda\",\"message\":\"preciso de ajuda com minha conta\","
					+ "\"contactEmail\":\"eu@example.com\"}"))
			.andExpect(status().isUnauthorized());

		mockMvc.perform(post("/support/requests")
				.header("X-Session-Token", session.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"subject\":\"Ajuda\",\"message\":\"curto\",\"contactEmail\":\"eu@example.com\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_SUPPORT_REQUEST"));

		mockMvc.perform(post("/support/requests")
				.header("X-Session-Token", session.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"subject\":\"Ajuda com a conta\",\"message\":\"preciso de ajuda com minha conta\","
					+ "\"contactEmail\":\"eu@example.com\"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.id").exists())
			.andExpect(jsonPath("$.status").value("OPEN"));

		// A fila de suporte e restrita a administradores.
		mockMvc.perform(get("/admin/support").header("X-Session-Token", session.token()))
			.andExpect(status().isForbidden());
	}

	@Test
	void jobViewsExposeViewerStateAndValidateInput() throws Exception {
		var session = auth.authenticate("jobs-contract");

		mockMvc.perform(get("/jobs"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items").isArray());

		mockMvc.perform(get("/jobs").header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items").isArray());

		mockMvc.perform(get("/jobs/mine").header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items").isArray());

		mockMvc.perform(get("/jobs/applications").header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items").isArray());

		// Sem titulo: valida antes do gate de reputacao, entao responde 400 (e nao 403/500).
		mockMvc.perform(post("/jobs")
				.header("X-Session-Token", session.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"company\":\"Empresa\",\"type\":\"FULL_TIME\",\"level\":\"MID\"}"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void returnsStableEmptyPagesForContractLists() throws Exception {
		mockMvc.perform(get("/communities"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items").isArray());
	}

	@Test
	void addsRequestIdToResponses() throws Exception {
		MvcResult result = mockMvc.perform(get("/health")
				.header("X-Request-Id", "request-contract-1"))
			.andExpect(status().isOk())
			.andReturn();

		assertThat(result.getResponse().getHeader("X-Request-Id")).isEqualTo("request-contract-1");
	}

	@Test
	void replaysIdempotentContractWrites() throws Exception {
		var session = auth.authenticate("user_contract");

		MvcResult first = mockMvc.perform(post("/jobs/{jobId}/saves", "job_contract")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "contract-job-save-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("SAVED"))
			.andReturn();

		MvcResult replay = mockMvc.perform(post("/jobs/{jobId}/saves", "job_contract")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "contract-job-save-1"))
			.andExpect(status().isOk())
			.andReturn();

		assertThat(replay.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
	}
}
