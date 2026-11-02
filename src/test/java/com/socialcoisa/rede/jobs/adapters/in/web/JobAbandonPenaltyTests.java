package com.socialcoisa.rede.jobs.adapters.in.web;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.ObjectMapper;
import com.socialcoisa.rede.profile.application.ReputationService;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

/**
 * Item 7 do backlog: excluir vaga SEM dar retorno aos candidatos (ainda em APPLIED) registra
 * penalidade de credibilidade; quem respondeu todas antes de excluir não é penalizado. O aviso
 * volta NA RESPOSTA do delete — quem exclui precisa saber na hora.
 */
@SpringBootTest
@AutoConfigureMockMvc
class JobAbandonPenaltyTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@MockitoBean
	private ReputationService reputationService;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
		when(reputationService.reputationOf(anyString())).thenReturn(300);
	}

	private String createJob(TestAuthHelper.AuthSession owner) throws Exception {
		MvcResult result = mockMvc.perform(post("/jobs")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "penalty-job-" + System.nanoTime())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"title", "Vaga de penalidade", "company", "Acme", "location", "Remoto",
					"type", "CLT", "level", "PLENO", "skills", List.of(),
					"description", "Vaga de teste com valor.", "salaryMin", "1500",
					"communityAgreement", true))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private String apply(TestAuthHelper.AuthSession candidate, String jobId) throws Exception {
		MvcResult applied = mockMvc.perform(post("/jobs/" + jobId + "/applications")
				.header("X-Session-Token", candidate.token())
				.header("Idempotency-Key", "penalty-apply-" + System.nanoTime())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("message", "Tenho interesse."))))
			.andExpect(status().isCreated())
			.andReturn();
		// A resposta da candidatura já traz o id (o PATCH de triagem precisa dele).
		return objectMapper.readTree(applied.getResponse().getContentAsString()).get("id").asText();
	}

	@Test
	void deletingWithoutRespondingRecordsThePenaltyAndWarns() throws Exception {
		var owner = auth.authenticate("penalty_owner");
		var candidate = auth.authenticate("penalty_candidate");
		auth.activateProfessional(candidate);
		String jobId = createJob(owner);
		apply(candidate, jobId);

		// Sem resposta nenhuma: o aviso vem na resposta do delete.
		mockMvc.perform(delete("/jobs/" + jobId)
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "penalty-delete-no-reply"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.unanswered").value(1))
			.andExpect(jsonPath("$.penalized").value(true));
	}

	@Test
	void deletingAfterRespondingDoesNotPenalize() throws Exception {
		var owner = auth.authenticate("penalty_owner_ok");
		var candidate = auth.authenticate("penalty_candidate_ok");
		auth.activateProfessional(candidate);
		String jobId = createJob(owner);
		String applicationId = apply(candidate, jobId);

		// O empregador responde (recusa com motivo): a candidatura sai de APPLIED.
		mockMvc.perform(patch("/jobs/" + jobId + "/applications/" + applicationId)
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "penalty-reply-" + System.nanoTime())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"status", "NOT_SELECTED", "feedback", "Obrigado, seguimos com outros."))))
			.andExpect(status().isOk());

		mockMvc.perform(delete("/jobs/" + jobId)
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "penalty-delete-replied"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.unanswered").value(0))
			.andExpect(jsonPath("$.penalized").value(false));
	}

	@Test
	void deletingTwiceDoesNotDoubleCount() throws Exception {
		var owner = auth.authenticate("penalty_owner_dbl");
		var candidate = auth.authenticate("penalty_candidate_dbl");
		auth.activateProfessional(candidate);
		String jobId = createJob(owner);
		apply(candidate, jobId);

		mockMvc.perform(delete("/jobs/" + jobId)
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "penalty-delete-first"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.penalized").value(true));

		// A segunda exclusão da MESMA vaga é recusada (a vaga já não existe).
		mockMvc.perform(delete("/jobs/" + jobId)
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "penalty-delete-second"))
			.andExpect(status().isNotFound());
	}
}
