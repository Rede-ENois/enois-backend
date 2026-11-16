package com.socialcoisa.rede.jobs.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
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

import com.socialcoisa.rede.profile.application.ReputationService;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * Experiência do candidato: ele vê o NOME da etapa em que está e o próprio histórico, não só o
 * enum de quatro valores. O limite é o mesmo das notas internas — o que a equipe escreve para si
 * não vira texto do candidato.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CandidateJourneyTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@MockitoBean
	private ReputationService reputationService;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		when(reputationService.reputationOf(anyString())).thenReturn(300);
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	@Test
	void candidateSeesTheNamedStageAndTheHistoryOfTheirOwnApplication() throws Exception {
		var employer = auth.authenticate("journey_owner");
		var candidate = auth.authenticate("journey_candidate");
		auth.activateProfessional(candidate);
		String jobId = createJob(employer);
		String applicationId = apply(jobId, candidate);
		String triagem = addStage(employer, jobId, "Triagem");
		String tecnica = addStage(employer, jobId, "Entrevista técnica");

		// Sem movimento, a candidatura está na entrada do fluxo — e é isso que o candidato vê.
		mockMvc.perform(get("/jobs/applications/mine").header("X-Session-Token", candidate.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.id == '" + applicationId + "')].stageName").value("Triagem"));

		move(employer, jobId, applicationId, tecnica, "ADVANCE", null);

		// O nome da etapa na lista do candidato é o mesmo do board, não o enum.
		mockMvc.perform(get("/jobs/applications/mine").header("X-Session-Token", candidate.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.id == '" + applicationId + "')].stageName")
				.value("Entrevista técnica"));

		// A jornada: fluxo da vaga e histórico em ordem, com a etapa atual apontada.
		mockMvc.perform(get("/jobs/" + jobId + "/applications/" + applicationId + "/journey")
				.header("X-Session-Token", candidate.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.applicationId").value(applicationId))
			.andExpect(jsonPath("$.currentStageId").value(tecnica))
			.andExpect(jsonPath("$.stages.length()").value(2))
			.andExpect(jsonPath("$.stages[0].name").value("Triagem"))
			.andExpect(jsonPath("$.stages[1].name").value("Entrevista técnica"))
			.andExpect(jsonPath("$.steps.length()").value(1))
			.andExpect(jsonPath("$.steps[0].kind").value("ADVANCE"))
			.andExpect(jsonPath("$.steps[0].fromStageId").value(triagem))
			.andExpect(jsonPath("$.steps[0].toStageId").value(tecnica))
			.andExpect(jsonPath("$.steps[0].movedAt").isNotEmpty());

		// Quem comanda a vaga vê a mesma jornada — não é um segundo caminho para a equipe.
		mockMvc.perform(get("/jobs/" + jobId + "/applications/" + applicationId + "/journey")
				.header("X-Session-Token", employer.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.currentStageId").value(tecnica));
	}

	@Test
	void theJourneyShowsTheRejectionReasonButNotTheTeamsWorkNote() throws Exception {
		var employer = auth.authenticate("journey_note_owner");
		var candidate = auth.authenticate("journey_note_candidate");
		auth.activateProfessional(candidate);
		String jobId = createJob(employer);
		String applicationId = apply(jobId, candidate);
		String triagem = addStage(employer, jobId, "Triagem");
		String tecnica = addStage(employer, jobId, "Entrevista técnica");

		// Contexto de trabalho escrito numa passagem de etapa: é da equipe, não do candidato.
		move(employer, jobId, applicationId, tecnica, "ADVANCE",
			"pedir referral ao contato interno antes da próxima conversa");
		// Reprovação: o motivo é o retorno que o candidato tem direito a reler.
		move(employer, jobId, applicationId, null, "REJECT", "Seguimos com quem já tinha liderado time.");

		MvcResult mine = mockMvc.perform(get("/jobs/" + jobId + "/applications/" + applicationId + "/journey")
				.header("X-Session-Token", candidate.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.steps.length()").value(2))
			.andExpect(jsonPath("$.steps[0].kind").value("ADVANCE"))
			.andExpect(jsonPath("$.steps[0].note").value(org.hamcrest.Matchers.nullValue()))
			.andExpect(jsonPath("$.steps[1].kind").value("REJECT"))
			.andExpect(jsonPath("$.steps[1].note").value("Seguimos com quem já tinha liderado time."))
			.andExpect(jsonPath("$.feedback").value("Seguimos com quem já tinha liderado time."))
			.andReturn();
		assertThat(mine.getResponse().getContentAsString()).doesNotContain("pedir referral");

		// Para a equipe o contexto de trabalho continua legível — a redação é para o candidato.
		mockMvc.perform(get("/jobs/" + jobId + "/applications/" + applicationId + "/journey")
				.header("X-Session-Token", employer.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.steps[0].note")
				.value("pedir referral ao contato interno antes da próxima conversa"));
	}

	@Test
	void anOutsiderCannotReadTheJourneyAndAnApplicationFromAnotherJobIsNotReachable() throws Exception {
		var employer = auth.authenticate("journey_auth_owner");
		var candidate = auth.authenticate("journey_auth_candidate");
		auth.activateProfessional(candidate);
		var outsider = auth.authenticate("journey_auth_outsider");
		String jobId = createJob(employer);
		String applicationId = apply(jobId, candidate);
		addStage(employer, jobId, "Triagem");

		String url = "/jobs/" + jobId + "/applications/" + applicationId + "/journey";

		// Sem sessão: 401. Terceiro: 403, e a recusa não revela o conteúdo.
		mockMvc.perform(get(url)).andExpect(status().isUnauthorized());
		mockMvc.perform(get(url).header("X-Session-Token", outsider.token()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NOT_PARTICIPANT"));

		// Candidatura de OUTRA vaga na URL: 404, sem vazar entre processos.
		var otherEmployer = auth.authenticate("journey_auth_other_owner");
		String otherJob = createJob(otherEmployer);
		var otherCandidate = auth.authenticate("journey_auth_other_candidate");
		auth.activateProfessional(otherCandidate);
		String foreignApplication = apply(otherJob, otherCandidate);
		mockMvc.perform(get("/jobs/" + jobId + "/applications/" + foreignApplication + "/journey")
				.header("X-Session-Token", employer.token()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("APPLICATION_NOT_FOUND"));
	}

	private String createJob(TestAuthHelper.AuthSession session) throws Exception {
		MvcResult result = mockMvc.perform(post("/jobs")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "journey-job-" + session.userId())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"title":"Pessoa Desenvolvedora","company":"Acme","location":"Remoto","type":"CLT",
						 "level":"PLENO","currency":"BRL","salaryMin":"1500","salaryPeriod":"MONTH",
						 "description":"Vaga de teste da jornada do candidato.","skills":["Java"],
						 "communityAgreement":true,"applicationFields":[]}"""))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private String apply(String jobId, TestAuthHelper.AuthSession candidate) throws Exception {
		MvcResult result = mockMvc.perform(post("/jobs/" + jobId + "/applications")
				.header("X-Session-Token", candidate.token())
				.header("Idempotency-Key", "journey-apply-" + jobId + "-" + candidate.userId())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"message\":\"Tenho interesse\",\"answers\":[]}"))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private String addStage(TestAuthHelper.AuthSession session, String jobId, String name) throws Exception {
		MvcResult result = mockMvc.perform(post("/jobs/" + jobId + "/process/stages")
				.header("X-Session-Token", session.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", name))))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private void move(TestAuthHelper.AuthSession session, String jobId, String applicationId, String stageId,
			String kind, String note) throws Exception {
		Map<String, Object> body = new HashMap<>();
		body.put("kind", kind);
		if (stageId != null) {
			body.put("stageId", stageId);
		}
		if (note != null) {
			body.put("note", note);
		}
		mockMvc.perform(post("/jobs/" + jobId + "/process/applications/" + applicationId + "/move")
				.header("X-Session-Token", session.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(body)))
			.andExpect(status().isOk());
	}
}
