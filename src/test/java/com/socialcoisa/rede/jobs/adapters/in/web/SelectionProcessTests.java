package com.socialcoisa.rede.jobs.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
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

import com.socialcoisa.rede.profile.application.ReputationService;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * Pipeline de recrutamento: fluxo de etapas por vaga, movimento das candidaturas com
 * histórico e notas internas que o candidato nunca vê.
 *
 * <p>A autorização é o ponto sensível: só quem publicou a vaga comanda o processo, e a
 * candidatura precisa ser DESTA vaga — um id de outra vaga na URL não pode funcionar.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SelectionProcessTests {

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
	void ownerConfiguresTheFlowReordersItAndCandidatesStartAtTheEntryStage() throws Exception {
		var employer = auth.authenticate("proc_owner");
		var candidate = auth.authenticate("proc_candidate");
		auth.activateProfessional(candidate);
		String jobId = createJob(employer);
		String applicationId = apply(jobId, candidate);

		// Fluxo próprio da vaga, na ordem em que foi criado.
		String triagem = addStage(employer, jobId, "Triagem");
		String tecnica = addStage(employer, jobId, "Entrevista técnica");
		String proposta = addStage(employer, jobId, "Proposta");

		mockMvc.perform(get("/jobs/" + jobId + "/process/stages").header("X-Session-Token", employer.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(3))
			.andExpect(jsonPath("$[0].name").value("Triagem"))
			.andExpect(jsonPath("$[0].position").value(0))
			.andExpect(jsonPath("$[2].name").value("Proposta"));

		// Nome repetido na mesma vaga: 409, não uma segunda "Triagem" no board.
		mockMvc.perform(post("/jobs/" + jobId + "/process/stages").header("X-Session-Token", employer.token())
				.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"triagem\"}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("STAGE_NAME_TAKEN"));

		// Reordenar: proposta vai para depois da triagem.
		mockMvc.perform(post("/jobs/" + jobId + "/process/stages/order")
				.header("X-Session-Token", employer.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("stageIds", List.of(triagem, proposta, tecnica)))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[1].name").value("Proposta"))
			.andExpect(jsonPath("$[1].position").value(1))
			.andExpect(jsonPath("$[2].name").value("Entrevista técnica"));

		// Ordem incompleta (faltando etapa) é erro do cliente, não bagunça silenciosa.
		mockMvc.perform(post("/jobs/" + jobId + "/process/stages/order")
				.header("X-Session-Token", employer.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("stageIds", List.of(triagem, proposta)))))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.code").value("REORDER_INCOMPLETE"));

		// Candidatura sem movimento aparece na entrada do fluxo, não sumida.
		mockMvc.perform(get("/jobs/" + jobId + "/process").header("X-Session-Token", employer.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.columns[0].stageId").value(triagem))
			.andExpect(jsonPath("$.columns[0].cards[0].applicationId").value(applicationId))
			.andExpect(jsonPath("$.summary.total").value(1))
			.andExpect(jsonPath("$.summary.inProcess").value(1))
			.andExpect(jsonPath("$.summary.rejected").value(0));
	}

	@Test
	void movingAheadAndBackIsRecordedInOrderAndTheBoardFollows() throws Exception {
		var employer = auth.authenticate("proc_move_owner");
		var candidate = auth.authenticate("proc_move_candidate");
		auth.activateProfessional(candidate);
		String jobId = createJob(employer);
		String applicationId = apply(jobId, candidate);
		String triagem = addStage(employer, jobId, "Triagem");
		String tecnica = addStage(employer, jobId, "Técnica");

		// A candidatura sem movimento já está na entrada do fluxo: o primeiro movimento válido é
		// para a etapa seguinte, e tentar "mover" para a entrada é 409.
		moveExpecting(employer, jobId, applicationId, triagem, "ADVANCE", null, 409, "ALREADY_IN_STAGE");
		move(employer, jobId, applicationId, tecnica, "ADVANCE", null);
		// Voltar para a etapa anterior com motivo registrado.
		move(employer, jobId, applicationId, triagem, "RETURN", "Precisa de outra amostra de código.");

		// O board mostra a candidatura onde ela está AGORA — no último movimento.
		mockMvc.perform(get("/jobs/" + jobId + "/process").header("X-Session-Token", employer.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.columns[0].stageId").value(triagem))
			.andExpect(jsonPath("$.columns[0].cards[0].applicationId").value(applicationId))
			.andExpect(jsonPath("$.columns[1].cards.length()").value(0))
			.andExpect(jsonPath("$.columns[0].cards[0].lastMovementAt").exists());

		// Histórico completo, em ordem, com responsável e motivo.
		mockMvc.perform(get("/jobs/" + jobId + "/process/applications/" + applicationId + "/movements")
				.header("X-Session-Token", employer.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[0].fromStageId").value(triagem))
			.andExpect(jsonPath("$[0].toStageId").value(tecnica))
			.andExpect(jsonPath("$[0].kind").value("ADVANCE"))
			.andExpect(jsonPath("$[1].kind").value("RETURN"))
			.andExpect(jsonPath("$[1].fromStageId").value(tecnica))
			.andExpect(jsonPath("$[1].toStageId").value(triagem))
			.andExpect(jsonPath("$[1].note").value("Precisa de outra amostra de código."));

		// Mover para a etapa em que já está: 409, não um movimento vazio no histórico.
		moveExpecting(employer, jobId, applicationId, triagem, "ADVANCE", null, 409, "ALREADY_IN_STAGE");

		// Etapa de outra vaga não é alcançável por esta URL.
		var other = auth.authenticate("proc_move_other");
		String otherJob = createJob(other);
		String foreignStage = addStage(other, otherJob, "Etapa alheia");
		moveExpecting(employer, jobId, applicationId, foreignStage, "ADVANCE", null, 404, "STAGE_NOT_FOUND");
	}

	@Test
	void rejectingRequiresAReasonAndEndsThePipelineWithTheCandidateSeeingIt() throws Exception {
		var employer = auth.authenticate("proc_reject_owner");
		var candidate = auth.authenticate("proc_reject_candidate");
		auth.activateProfessional(candidate);
		String jobId = createJob(employer);
		String applicationId = apply(jobId, candidate);
		addStage(employer, jobId, "Triagem");

		// Reprovar sem motivo é o que deixa o candidato sem saber o que aconteceu: 400.
		moveExpecting(employer, jobId, applicationId, null, "REJECT", null, 400, "FEEDBACK_REQUIRED");
		moveExpecting(employer, jobId, applicationId, null, "REJECT", "   ", 400, "FEEDBACK_REQUIRED");

		move(employer, jobId, applicationId, null, "REJECT", "Buscamos alguém com mais experiência em liderança.");

		// A reprovação vale nas duas visões: pipeline e estado clássico da candidatura.
		mockMvc.perform(get("/jobs/" + jobId + "/process").header("X-Session-Token", employer.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.summary.rejected").value(1))
			.andExpect(jsonPath("$.summary.inProcess").value(0))
			.andExpect(jsonPath("$.rejected[0].applicationId").value(applicationId));

		mockMvc.perform(get("/jobs/" + jobId + "/applications").header("X-Session-Token", employer.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].status").value("NOT_SELECTED"));

		// Reprovada não se move de novo: o processo dela terminou.
		moveExpecting(employer, jobId, applicationId, null, "ADVANCE", null, 409, "APPLICATION_REJECTED");

		// O candidato vê o próprio estado e o retorno — nunca a nota interna.
		mockMvc.perform(get("/jobs/applications/mine").header("X-Session-Token", candidate.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.id == '" + applicationId + "')].status").value("NOT_SELECTED"))
			.andExpect(jsonPath("$[?(@.id == '" + applicationId + "')].feedback")
				.value("Buscamos alguém com mais experiência em liderança."));
	}

	@Test
	void internalNotesStayWithTheOwnerAndNeverReachTheCandidate() throws Exception {
		var employer = auth.authenticate("proc_note_owner");
		var candidate = auth.authenticate("proc_note_candidate");
		auth.activateProfessional(candidate);
		var outsider = auth.authenticate("proc_note_outsider");
		String jobId = createJob(employer);
		String applicationId = apply(jobId, candidate);

		mockMvc.perform(post("/jobs/" + jobId + "/process/applications/" + applicationId + "/notes")
				.header("X-Session-Token", employer.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"body\":\"Bom portfólio, mas precisa confirmar disponibilidade para viagens.\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.authorId").value(employer.userId()))
			.andExpect(jsonPath("$.body").value("Bom portfólio, mas precisa confirmar disponibilidade para viagens."));

		// Nota vazia é ruído, não registro.
		mockMvc.perform(post("/jobs/" + jobId + "/process/applications/" + applicationId + "/notes")
				.header("X-Session-Token", employer.token())
				.contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"  \"}"))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.code").value("NOTE_BODY_REQUIRED"));

		mockMvc.perform(get("/jobs/" + jobId + "/process/applications/" + applicationId + "/notes")
				.header("X-Session-Token", employer.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].body").value("Bom portfólio, mas precisa confirmar disponibilidade para viagens."));

		// Terceiro: 403. O próprio candidato: 403 — nota interna é da equipe.
		mockMvc.perform(get("/jobs/" + jobId + "/process/applications/" + applicationId + "/notes")
				.header("X-Session-Token", outsider.token()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NOT_OWNER"));
		mockMvc.perform(get("/jobs/" + jobId + "/process/applications/" + applicationId + "/notes")
				.header("X-Session-Token", candidate.token()))
			.andExpect(status().isForbidden());

		// E o texto da nota não aparece em nenhuma resposta que o candidato acessa.
		MvcResult mine = mockMvc.perform(get("/jobs/applications/mine").header("X-Session-Token", candidate.token()))
			.andExpect(status().isOk())
			.andReturn();
		assertThat(mine.getResponse().getContentAsString()).doesNotContain("precisa confirmar disponibilidade");
	}

	@Test
	void onlyTheJobOwnerRunsTheProcessAndAnApplicationFromAnotherJobIsNotReachable() throws Exception {
		var employer = auth.authenticate("proc_auth_owner");
		var other = auth.authenticate("proc_auth_other");
		var candidate = auth.authenticate("proc_auth_candidate");
		auth.activateProfessional(candidate);
		String jobId = createJob(employer);
		String applicationId = apply(jobId, candidate);
		String otherJob = createJob(other);

		// Sem sessão: 401 em toda a superfície.
		mockMvc.perform(get("/jobs/" + jobId + "/process")).andExpect(status().isUnauthorized());
		mockMvc.perform(post("/jobs/" + jobId + "/process/stages")
				.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Triagem\"}"))
			.andExpect(status().isUnauthorized());

		// Outra pessoa: 403, mesmo com id de vaga válido.
		mockMvc.perform(get("/jobs/" + jobId + "/process/stages").header("X-Session-Token", other.token()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NOT_OWNER"));
		mockMvc.perform(post("/jobs/" + jobId + "/process/stages").header("X-Session-Token", other.token())
				.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Triagem\"}"))
			.andExpect(status().isForbidden());
		mockMvc.perform(get("/jobs/" + jobId + "/process").header("X-Session-Token", candidate.token()))
			.andExpect(status().isForbidden());

		// Candidatura de OUTRA vaga com a URL desta: 404, não vazamento entre processos.
		String stage = addStage(employer, jobId, "Triagem");
		mockMvc.perform(post("/jobs/" + otherJob + "/process/applications/" + applicationId + "/move")
				.header("X-Session-Token", other.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("stageId", stage, "kind", "ADVANCE"))))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("APPLICATION_NOT_FOUND"));

		// Vaga inexistente: 404.
		mockMvc.perform(get("/jobs/job_nao_existe/process").header("X-Session-Token", employer.token()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"));
	}

	@Test
	void stageWithSomeoneInItCannotBeDeletedButEmptyOnesReorderCleanly() throws Exception {
		var employer = auth.authenticate("proc_del_owner");
		var candidate = auth.authenticate("proc_del_candidate");
		auth.activateProfessional(candidate);
		String jobId = createJob(employer);
		String applicationId = apply(jobId, candidate);
		String triagem = addStage(employer, jobId, "Triagem");
		String tecnica = addStage(employer, jobId, "Técnica");
		String oferta = addStage(employer, jobId, "Oferta");

		move(employer, jobId, applicationId, tecnica, "ADVANCE", null);

		// Etapa ocupada: 409. Apagar deixaria o card apontando para o nada.
		mockMvc.perform(delete("/jobs/" + jobId + "/process/stages/" + tecnica)
				.header("X-Session-Token", employer.token()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("STAGE_IN_USE"));

		// Etapa vazia sai e as posições restantes ficam contíguas.
		mockMvc.perform(delete("/jobs/" + jobId + "/process/stages/" + oferta)
				.header("X-Session-Token", employer.token()))
			.andExpect(status().isOk());
		mockMvc.perform(get("/jobs/" + jobId + "/process/stages").header("X-Session-Token", employer.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[0].id").value(triagem))
			.andExpect(jsonPath("$[0].position").value(0))
			.andExpect(jsonPath("$[1].id").value(tecnica))
			.andExpect(jsonPath("$[1].position").value(1));

		// Renomear não perde o histórico: a candidatura continua na mesma etapa pelo id.
		mockMvc.perform(patch("/jobs/" + jobId + "/process/stages/" + tecnica)
				.header("X-Session-Token", employer.token())
				.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Entrevista técnica\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(tecnica))
			.andExpect(jsonPath("$.name").value("Entrevista técnica"));

		mockMvc.perform(get("/jobs/" + jobId + "/process").header("X-Session-Token", employer.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.columns[1].stageId").value(tecnica))
			.andExpect(jsonPath("$.columns[1].cards[0].applicationId").value(applicationId));
	}

	@Test
	void unknownMovementKindIsAClientErrorNotASilentAdvance() throws Exception {
		var employer = auth.authenticate("proc_kind_owner");
		var candidate = auth.authenticate("proc_kind_candidate");
		auth.activateProfessional(candidate);
		String jobId = createJob(employer);
		String applicationId = apply(jobId, candidate);
		String stage = addStage(employer, jobId, "Triagem");
		moveExpecting(employer, jobId, applicationId, stage, "PULAR", null, 400, "INVALID_MOVEMENT_KIND");
	}

	@Test
	void recruitmentHubCountsTheFunnelOfEachOwnVacancyOnly() throws Exception {
		var recruiter = auth.authenticate("hub_recruiter");
		var otherRecruiter = auth.authenticate("hub_other");
		var first = auth.authenticate("hub_cand_1");
		var second = auth.authenticate("hub_cand_2");
		var third = auth.authenticate("hub_cand_3");
		auth.activateProfessional(first);
		auth.activateProfessional(second);
		auth.activateProfessional(third);

		String jobId = createJob(recruiter);
		addStage(recruiter, jobId, "Triagem");
		String tecnica = addStage(recruiter, jobId, "Técnica");

		String a = apply(jobId, first);
		apply(jobId, second);
		String c = apply(jobId, third);

		// Uma candidatura ainda não foi mexida (aguardando triagem), uma avançou, uma foi reprovada.
		move(recruiter, jobId, a, tecnica, "ADVANCE", null);
		move(recruiter, jobId, c, null, "REJECT", "Perfil fora do que a vaga pede.");

		// Vaga de outra pessoa não entra na minha central.
		String otherJob = createJob(otherRecruiter);

		mockMvc.perform(get("/jobs/recruitment").header("X-Session-Token", recruiter.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.jobs.length()").value(1))
			.andExpect(jsonPath("$.jobs[0].jobId").value(jobId))
			.andExpect(jsonPath("$.jobs[0].total").value(3))
			.andExpect(jsonPath("$.jobs[0].awaitingTriage").value(1))
			.andExpect(jsonPath("$.jobs[0].inProcess").value(1))
			.andExpect(jsonPath("$.jobs[0].rejected").value(1))
			.andExpect(jsonPath("$.jobs[0].stages").value(2))
			.andExpect(jsonPath("$.totals.jobs").value(1))
			.andExpect(jsonPath("$.totals.openJobs").value(1))
			.andExpect(jsonPath("$.totals.candidates").value(3))
			.andExpect(jsonPath("$.totals.awaitingTriage").value(1));

		// A central da outra pessoa mostra só a vaga dela, zerada.
		mockMvc.perform(get("/jobs/recruitment").header("X-Session-Token", otherRecruiter.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.jobs.length()").value(1))
			.andExpect(jsonPath("$.jobs[0].jobId").value(otherJob))
			.andExpect(jsonPath("$.jobs[0].total").value(0));

		// Sem sessão: 401.
		mockMvc.perform(get("/jobs/recruitment")).andExpect(status().isUnauthorized());
	}

	// ---------- Apoio ----------

	private String createJob(TestAuthHelper.AuthSession session) throws Exception {
		MvcResult result = mockMvc.perform(post("/jobs")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "proc-job-" + session.userId())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"title":"Pessoa Desenvolvedora","company":"Acme","location":"Remoto","type":"CLT",
						 "level":"PLENO","currency":"BRL","salaryMin":"1500","salaryPeriod":"MONTH",
						 "description":"Vaga de teste do processo seletivo.","skills":["Java"],
						 "communityAgreement":true,"applicationFields":[]}"""))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private String apply(String jobId, TestAuthHelper.AuthSession candidate) throws Exception {
		MvcResult result = mockMvc.perform(post("/jobs/" + jobId + "/applications")
				.header("X-Session-Token", candidate.token())
				.header("Idempotency-Key", "proc-apply-" + jobId)
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
		moveExpecting(session, jobId, applicationId, stageId, kind, note, 200, null);
	}

	private void moveExpecting(TestAuthHelper.AuthSession session, String jobId, String applicationId,
			String stageId, String kind, String note, int expectedStatus, String expectedCode) throws Exception {
		Map<String, Object> body = new java.util.HashMap<>();
		body.put("kind", kind);
		if (stageId != null) {
			body.put("stageId", stageId);
		}
		if (note != null) {
			body.put("note", note);
		}
		var request = post("/jobs/" + jobId + "/process/applications/" + applicationId + "/move")
			.header("X-Session-Token", session.token())
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(body));
		var result = mockMvc.perform(request).andExpect(status().is(expectedStatus));
		if (expectedCode != null) {
			result.andExpect(jsonPath("$.code").value(expectedCode));
		}
	}

}
