package com.socialcoisa.rede.jobs.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
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

import com.socialcoisa.rede.identity.application.EmailService;
import com.socialcoisa.rede.profile.application.ReputationService;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reprovar pelo board avisa o candidato pelos mesmos canais da triagem clássica.
 *
 * <p>O board e o estado clássico são a MESMA candidatura: reprovar por um caminho e não avisar
 * enquanto o outro avisa deixava a decisão muda justamente para quem espera resposta. Aqui a
 * prova é do fluxo inteiro — notificação na caixa, mensagem na conversa de candidatura e e-mail —
 * saindo da infraestrutura que o projeto já tinha, sem canal novo.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PipelineRejectionNotifiesCandidateTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@MockitoBean
	private ReputationService reputationService;

	@MockitoBean
	private EmailService emailService;

	private final List<String> sentTo = new ArrayList<>();
	private final List<String> sentSubjects = new ArrayList<>();
	private final List<String> sentBodies = new ArrayList<>();

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		sentTo.clear();
		sentSubjects.clear();
		sentBodies.clear();
		when(reputationService.reputationOf(anyString())).thenReturn(300);
		when(emailService.sendHtml(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
			sentTo.add(invocation.getArgument(0, String.class));
			sentSubjects.add(invocation.getArgument(1, String.class));
			sentBodies.add(invocation.getArgument(2, String.class));
			return true;
		});
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	@Test
	void rejectingOnTheBoardReachesTheCandidateInTheInboxTheConversationAndTheEmail() throws Exception {
		var employer = auth.authenticate("pipe_reject_owner");
		var candidate = auth.authenticate("pipe_reject_candidate");
		auth.activateProfessional(candidate);
		String jobId = createJob(employer);
		String applicationId = apply(jobId, candidate);
		addStage(employer, jobId, "Triagem");

		// Reprovar sem motivo continua sendo recusa: nada é gravado e nada é avisado.
		moveExpecting(employer, jobId, applicationId, null, "REJECT", "   ", 400, "FEEDBACK_REQUIRED");
		mockMvc.perform(get("/notifications").header("X-Session-Token", candidate.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.kind == 'JOB_APPLICATION_STATUS')]").isEmpty());

		String reason = "Seguimos com alguém que já liderou times maiores.";
		move(employer, jobId, applicationId, null, "REJECT", reason);

		// Caixa de notificações: o aviso aponta para a vaga e NÃO repete o retorno (o corpo
		// aparece em notificação de tela bloqueada); o retorno vai pelo canal privado.
		mockMvc.perform(get("/notifications").header("X-Session-Token", candidate.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.kind == 'JOB_APPLICATION_STATUS')].href")
				.value(org.hamcrest.Matchers.contains("/vagas/" + jobId)))
			.andExpect(jsonPath("$.items[?(@.kind == 'JOB_APPLICATION_STATUS')].body")
				.value(org.hamcrest.Matchers.contains(org.hamcrest.Matchers.containsString("respondeu"))))
			.andExpect(jsonPath("$.items[?(@.kind == 'JOB_APPLICATION_STATUS')].body")
				.value(org.hamcrest.Matchers.everyItem(
					org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("liderou times maiores")))));

		// A conversa de candidatura entre os dois existe e traz o retorno escrito.
		String conversationId = onlyConversationFor(candidate);
		mockMvc.perform(get("/conversations/" + conversationId + "/messages")
				.header("X-Session-Token", candidate.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[*].text")
				.value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString(reason))));

		// E-mail ao candidato, com o retorno no corpo.
		assertThat(sentTo).anyMatch(address -> address.startsWith("pipe_reject_candidate"));
		int email = indexOfRecipient("pipe_reject_candidate");
		assertThat(sentSubjects.get(email)).contains("Pessoa Desenvolvedora");
		assertThat(sentBodies.get(email)).contains(reason);
	}

	@Test
	void rejectingInBatchAlsoNotifiesEveryCandidate() throws Exception {
		var employer = auth.authenticate("pipe_batch_owner");
		var first = auth.authenticate("pipe_batch_first");
		var second = auth.authenticate("pipe_batch_second");
		auth.activateProfessional(first);
		auth.activateProfessional(second);
		String jobId = createJob(employer);
		String firstApplication = apply(jobId, first);
		String secondApplication = apply(jobId, second);
		addStage(employer, jobId, "Triagem");

		String reason = "A vaga pediu disponibilidade para viagens.";
		mockMvc.perform(post("/jobs/" + jobId + "/process/applications/batch-move")
				.header("X-Session-Token", employer.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"applicationIds", List.of(firstApplication, secondApplication),
					"kind", "REJECT",
					"note", reason))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[0].errorCode").doesNotExist())
			.andExpect(jsonPath("$[1].errorCode").doesNotExist());

		// Cada candidatura reprovada no lote gera o aviso da própria candidata — o lote não vira
		// um aviso só, nem um silêncio.
		for (var candidate : List.of(first, second)) {
			mockMvc.perform(get("/notifications").header("X-Session-Token", candidate.token()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[?(@.kind == 'JOB_APPLICATION_STATUS')].href")
					.value(org.hamcrest.Matchers.contains("/vagas/" + jobId)));
		}
		assertThat(sentTo).anyMatch(address -> address.startsWith("pipe_batch_first"));
		assertThat(sentTo).anyMatch(address -> address.startsWith("pipe_batch_second"));
	}

	/** Avançar de etapa não é decisão final: o candidato segue no processo e não recebe aviso. */
	@Test
	void advancingBetweenStagesStaysQuiet() throws Exception {
		var employer = auth.authenticate("pipe_advance_owner");
		var candidate = auth.authenticate("pipe_advance_candidate");
		auth.activateProfessional(candidate);
		String jobId = createJob(employer);
		String applicationId = apply(jobId, candidate);
		String triagem = addStage(employer, jobId, "Triagem");
		String tecnica = addStage(employer, jobId, "Técnica");

		move(employer, jobId, applicationId, tecnica, "ADVANCE", null);

		mockMvc.perform(get("/notifications").header("X-Session-Token", candidate.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.kind == 'JOB_APPLICATION_STATUS')]").isEmpty());
		assertThat(sentTo).noneMatch(address -> address.startsWith("pipe_advance_candidate"));
		// A etapa nova continua visível para a equipe: silêncio não é perda do movimento.
		mockMvc.perform(get("/jobs/" + jobId + "/process").header("X-Session-Token", employer.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.columns[1].stageId").value(tecnica))
			.andExpect(jsonPath("$.columns[1].cards[0].applicationId").value(applicationId));
	}

	// ---------- Apoio ----------

	private int indexOfRecipient(String prefix) {
		for (int index = 0; index < sentTo.size(); index++) {
			if (sentTo.get(index).startsWith(prefix)) {
				return index;
			}
		}
		throw new AssertionError("nenhum e-mail para " + prefix + " em " + sentTo);
	}

	private String onlyConversationFor(TestAuthHelper.AuthSession session) throws Exception {
		MvcResult result = mockMvc.perform(get("/conversations").header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode items = objectMapper.readTree(result.getResponse().getContentAsString()).get("items");
		assertThat(items.size()).isEqualTo(1);
		return items.get(0).get("id").asText();
	}

	private String createJob(TestAuthHelper.AuthSession session) throws Exception {
		MvcResult result = mockMvc.perform(post("/jobs")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "pipe-job-" + session.userId())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"title":"Pessoa Desenvolvedora","company":"Acme","location":"Remoto","type":"CLT",
						 "level":"PLENO","currency":"BRL","salaryMin":"1500","salaryPeriod":"MONTH",
						 "description":"Vaga de teste da comunicação do processo seletivo.","skills":["Java"],
						 "communityAgreement":true,"applicationFields":[]}"""))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private String apply(String jobId, TestAuthHelper.AuthSession candidate) throws Exception {
		MvcResult result = mockMvc.perform(post("/jobs/" + jobId + "/applications")
				.header("X-Session-Token", candidate.token())
				.header("Idempotency-Key", "pipe-apply-" + jobId + "-" + candidate.userId())
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
		var result = mockMvc.perform(post("/jobs/" + jobId + "/process/applications/" + applicationId + "/move")
				.header("X-Session-Token", session.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(body)))
			.andExpect(status().is(expectedStatus));
		if (expectedCode != null) {
			result.andExpect(jsonPath("$.code").value(expectedCode));
		}
	}
}
