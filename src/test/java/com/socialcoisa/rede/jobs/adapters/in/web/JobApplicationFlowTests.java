package com.socialcoisa.rede.jobs.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
 * Candidatura de ponta a ponta: formulario montado por quem publica (campos obrigatorios e opcionais),
 * validacao, retrato do candidato, entrega por e-mail e DM, e a lista que o empregador ve.
 */
@SpringBootTest
@AutoConfigureMockMvc
class JobApplicationFlowTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	/** A porta de reputacao sustenta o gate de publicacao; aqui basta um valor alto e estavel. */
	@MockitoBean
	private ReputationService reputationService;

	/** Espiao do e-mail: a candidatura precisa chegar ao empregador por aqui tambem. */
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
	void employerDefinesFieldsAndReceivesTheApplicationWithTheCandidateProfile() throws Exception {
		var employer = auth.authenticate("cand_employer");
		var candidate = auth.authenticate("cand_candidate");
		String proUsername = auth.activateProfessional(candidate);
		// Duas experiencias: a primeira comeca 6 anos atras (o "tempo de xp" do retrato vem daqui).
		addExperience(candidate, "Desenvolvedora Back-end", java.time.LocalDate.now().minusYears(6));
		addExperience(candidate, "Desenvolvedora Junior", java.time.LocalDate.now().minusYears(2));

		String jobId = createJob(employer, """
				[{"label":"Anos de experiência com Java","type":"NUMBER","required":true},
				 {"label":"Link do portfólio","type":"LINK","required":false},
				 {"label":"Por que essa vaga?","type":"TEXTAREA","required":false}]""");

		// O candidato ve o formulario e quem publicou (credibilidade de quem esta do outro lado).
		mockMvc.perform(get("/jobs/" + jobId).header("X-Session-Token", candidate.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.applicationFields.length()").value(3))
			.andExpect(jsonPath("$.applicationFields[0].label").value("Anos de experiência com Java"))
			.andExpect(jsonPath("$.applicationFields[0].required").value(true))
			.andExpect(jsonPath("$.author.username").value("cand-employer"))
			.andExpect(jsonPath("$.author.identityVerified").value(true));

		// Campo obrigatorio sem resposta barra a candidatura, dizendo qual falta.
		mockMvc.perform(post("/jobs/" + jobId + "/applications")
				.header("X-Session-Token", candidate.token())
				.header("Idempotency-Key", "cand-missing")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("message", "Tenho interesse", "answers", List.of()))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("MISSING_REQUIRED_ANSWERS"))
			.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Anos de experiência")));

		// Valor que nao combina com o tipo do campo tambem barra.
		mockMvc.perform(post("/jobs/" + jobId + "/applications")
				.header("X-Session-Token", candidate.token())
				.header("Idempotency-Key", "cand-invalid")
				.contentType(MediaType.APPLICATION_JSON)
				.content(answersBody(jobId, candidate, List.of("varios anos"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_ANSWER"));

		// Candidatura valida.
		MvcResult applied = mockMvc.perform(post("/jobs/" + jobId + "/applications")
				.header("X-Session-Token", candidate.token())
				.header("Idempotency-Key", "cand-ok")
				.contentType(MediaType.APPLICATION_JSON)
				.content(answersBody(jobId, candidate, java.util.Arrays.asList("6", null, "Porque quero crescer"))))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("APPLIED"))
			.andExpect(jsonPath("$.answersCount").value(2))
			.andReturn();
		assertThat(applied.getResponse().getContentAsString()).contains("\"id\"");

		// O empregador recebe por e-mail, com o perfil e as respostas.
		assertThat(sentSubjects).hasSize(1);
		assertThat(sentSubjects.get(0)).contains("Nova candidatura");
		// Sempre pela identidade profissional: o nome e o @ profissionais, nunca o @ social.
		assertThat(sentBodies.get(0)).contains("Perfil Profissional (@" + proUsername + ")")
			.contains("/profissional/" + proUsername).doesNotContain("cand-candidate")
			.contains("Anos de experiência com Java")
			.contains("Por que essa vaga?").contains("Reputação: 300").contains("conta verificada")
			.contains("Experiência: 6 anos (2 experiências)");

		// ... e na mensagem direta, criada na hora entre os dois.
		MvcResult conversations = mockMvc.perform(get("/conversations").header("X-Session-Token", employer.token()))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode items = objectMapper.readTree(conversations.getResponse().getContentAsString()).get("items");
		assertThat(items.size()).isEqualTo(1);
		String conversationId = items.get(0).get("id").asText();

		MvcResult messages = mockMvc.perform(get("/conversations/" + conversationId + "/messages")
				.header("X-Session-Token", employer.token()))
			.andExpect(status().isOk())
			.andReturn();
		assertThat(messages.getResponse().getContentAsString())
			.contains("Candidatura para")
			.contains("@" + proUsername)
			.contains("Experiência: 6 anos (2 experiências)")
			.contains("Anos de experiência com Java: 6")
			.contains("Por que essa vaga?: Porque quero crescer");

		// O empregador ve a lista de candidaturas com o retrato do candidato.
		mockMvc.perform(get("/jobs/" + jobId + "/applications").header("X-Session-Token", employer.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].candidate.username").value(proUsername))
			.andExpect(jsonPath("$[0].candidate.reputation").value(300))
			.andExpect(jsonPath("$[0].candidate.experienceYears").value(6))
			.andExpect(jsonPath("$[0].candidate.experiencesCount").value(2))
			.andExpect(jsonPath("$[0].message").value("Quero muito essa vaga"))
			.andExpect(jsonPath("$[0].answers.length()").value(2));

		// Candidato nao ve a lista de quem se candidatou (nem o total).
		mockMvc.perform(get("/jobs/" + jobId + "/applications").header("X-Session-Token", candidate.token()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NOT_OWNER"));

		// Candidatar de novo e bloqueado com motivo claro (antes a tela so dizia "erro inesperado").
		mockMvc.perform(post("/jobs/" + jobId + "/applications")
				.header("X-Session-Token", candidate.token())
				.header("Idempotency-Key", "cand-again")
				.contentType(MediaType.APPLICATION_JSON)
				.content(answersBody(jobId, candidate, java.util.Arrays.asList("6", null, "Porque quero crescer"))))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("ALREADY_APPLIED"));
	}

	@Test
	void ownerCannotApplyToOwnJobAndOptionalFieldsMayBeLeftEmpty() throws Exception {
		var employer = auth.authenticate("cand_owner");
		var candidate = auth.authenticate("cand_simple");
		auth.activateProfessional(candidate);

		String jobId = createJob(employer, """
				[{"label":"Anos de experiência","type":"NUMBER","required":false}]""");

		mockMvc.perform(post("/jobs/" + jobId + "/applications")
				.header("X-Session-Token", employer.token())
				.header("Idempotency-Key", "cand-owner")
				.contentType(MediaType.APPLICATION_JSON)
				.content(answersBody(jobId, employer, List.of("10"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("CANNOT_APPLY_TO_OWN_JOB"));

		// Opcional em branco nao atrapalha.
		mockMvc.perform(post("/jobs/" + jobId + "/applications")
				.header("X-Session-Token", candidate.token())
				.header("Idempotency-Key", "cand-optional")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("answers", List.of()))))
			.andExpect(status().isCreated());
	}

	/**
	 * Issue 321: a DM ao empregador corre na transacao da candidatura. Se ela for recusada (aqui, o
	 * portao de idade de quem nao declarou a data de nascimento), a candidatura nao pode cair junto
	 * com 500 — ela fica registrada e o empregador ainda recebe o e-mail.
	 */
	@Test
	void refusedDirectMessageDoesNotBreakTheApplication() throws Exception {
		var employer = auth.authenticate("cand_dm_employer");
		var candidate = auth.authenticate("cand_dm_candidate");
		auth.activateProfessional(candidate);
		String jobId = createJob(employer, "[]");
		com.socialcoisa.rede.shared.web.TestDatabase.clearBirthDate(candidate.userId());

		mockMvc.perform(post("/jobs/" + jobId + "/applications")
				.header("X-Session-Token", candidate.token())
				.header("Idempotency-Key", "cand-dm-apply")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"message\":\"Quero muito essa vaga\"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("APPLIED"));

		mockMvc.perform(get("/jobs/" + jobId + "/applications").header("X-Session-Token", employer.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1));
		assertThat(sentSubjects).contains("Nova candidatura: Pessoa Desenvolvedora");
	}

	@Test
	void formRejectsDuplicateLabelsAndAbsurdFieldCounts() throws Exception {
		var employer = auth.authenticate("cand_limits");

		mockMvc.perform(post("/jobs")
				.header("X-Session-Token", employer.token())
				.header("Idempotency-Key", "cand-dup")
				.contentType(MediaType.APPLICATION_JSON)
				.content(jobBody("""
					[{"label":"Experiência","type":"TEXT","required":true},
					 {"label":"experiência","type":"TEXT","required":false}]""")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("DUPLICATE_FIELD_LABEL"));

		mockMvc.perform(post("/jobs")
				.header("X-Session-Token", employer.token())
				.header("Idempotency-Key", "cand-type")
				.contentType(MediaType.APPLICATION_JSON)
				.content(jobBody("""
					[{"label":"Currículo","type":"PDF","required":true}]""")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_FIELD_TYPE"));
	}

	/** Registra uma experiencia no perfil do candidato (o retrato usa a mais antiga para os anos). */
	private void addExperience(TestAuthHelper.AuthSession session, String title, java.time.LocalDate startDate)
			throws Exception {
		mockMvc.perform(post("/me/experiences")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "cand-exp-" + title)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"title", title, "company", "Empresa", "location", "Remoto", "description", "x",
					"startDate", startDate.toString()))))
			.andExpect(status().isCreated());
	}

	private String createJob(TestAuthHelper.AuthSession session, String fieldsJson) throws Exception {
		MvcResult result = mockMvc.perform(post("/jobs")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "cand-job-" + session.userId())
				.contentType(MediaType.APPLICATION_JSON)
				.content(jobBody(fieldsJson)))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private String jobBody(String fieldsJson) throws Exception {
		// A vaga nova exige descrição e valor (item 5 do backlog).
		return """
				{"title":"Pessoa Desenvolvedora","company":"Acme","location":"Remoto","type":"CLT","level":"PLENO",
				 "currency":"BRL","salaryMin":"1500","salaryPeriod":"MONTH","description":"Vaga de teste com valor.",
				 "skills":["Java"],"communityAgreement":true,"applicationFields":%s}"""
			.formatted(fieldsJson);
	}

	/** Ids reais dos campos da vaga: a resposta aponta para eles, como no app. */
	private List<String> fieldIds(String jobId, TestAuthHelper.AuthSession session) throws Exception {
		MvcResult result = mockMvc.perform(get("/jobs/" + jobId).header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andReturn();
		List<String> ids = new ArrayList<>();
		objectMapper.readTree(result.getResponse().getContentAsString()).get("applicationFields")
			.forEach(field -> ids.add(field.get("id").asText()));
		return ids;
	}

	/** Corpo da candidatura com a mensagem e as respostas na ordem dos campos da vaga. */
	private String answersBody(String jobId, TestAuthHelper.AuthSession session, List<String> values)
			throws Exception {
		List<String> ids = fieldIds(jobId, session);
		List<Map<String, String>> answers = new ArrayList<>();
		for (int index = 0; index < values.size() && index < ids.size(); index++) {
			if (values.get(index) != null) {
				answers.add(Map.of("fieldId", ids.get(index), "value", values.get(index)));
			}
		}
		return objectMapper.writeValueAsString(Map.of("message", "Quero muito essa vaga", "answers", answers));
	}

	@Test
	void closingStopsNewApplicationsAndPreservesTheOnesAlreadyReceived() throws Exception {
		var employer = auth.authenticate("close_employer");
		var candidate = auth.authenticate("close_candidate");
		auth.activateProfessional(candidate);
		var outsider = auth.authenticate("close_outsider");

		String jobId = createJob(employer, "[]");

		// Candidatura recebida ANTES do encerramento: precisa sobreviver a ele.
		mockMvc.perform(post("/jobs/" + jobId + "/applications")
				.header("X-Session-Token", candidate.token())
				.header("Idempotency-Key", "close-apply-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("message", "Tenho interesse", "answers", List.of()))))
			.andExpect(status().isCreated());

		// Terceiro nao encerra vaga alheia.
		mockMvc.perform(post("/jobs/" + jobId + "/close").header("X-Session-Token", outsider.token()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NOT_OWNER"));

		// Sem sessao tambem nao.
		mockMvc.perform(post("/jobs/" + jobId + "/close"))
			.andExpect(status().isUnauthorized());

		// Vaga recem-publicada nasce aberta.
		mockMvc.perform(get("/jobs/" + jobId).header("X-Session-Token", employer.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("OPEN"));

		// O dono encerra, e o instante fica registrado.
		mockMvc.perform(post("/jobs/" + jobId + "/close").header("X-Session-Token", employer.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CLOSED"))
			.andExpect(jsonPath("$.closedAt").isNotEmpty());

		// Encerrada continua legivel por qualquer um (a consulta e publica).
		mockMvc.perform(get("/jobs/" + jobId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CLOSED"));

		// Candidatura nova em vaga encerrada e recusada...
		mockMvc.perform(post("/jobs/" + jobId + "/applications")
				.header("X-Session-Token", outsider.token())
				.header("Idempotency-Key", "close-apply-2")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("message", "Tarde demais", "answers", List.of()))))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("JOB_CLOSED"));

		// ... e o encerrado vence o "ja se candidatou": quem ja tinha aplicado tambem recebe o
		// motivo certo, senao a pessoa tentaria de novo achando que o problema era duplicidade.
		mockMvc.perform(post("/jobs/" + jobId + "/applications")
				.header("X-Session-Token", candidate.token())
				.header("Idempotency-Key", "close-apply-3")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("message", "De novo", "answers", List.of()))))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("JOB_CLOSED"));

		// Encerrar de novo e erro de estado, nao sucesso silencioso.
		mockMvc.perform(post("/jobs/" + jobId + "/close").header("X-Session-Token", employer.token()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("JOB_ALREADY_CLOSED"));

		// A candidatura anterior segue com o empregador — encerrar nao apaga nada.
		mockMvc.perform(get("/jobs/" + jobId + "/applications").header("X-Session-Token", employer.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1));
	}

	@Test
	void employerTriagesTheApplicationAndTheCandidateIsTold() throws Exception {
		var employer = auth.authenticate("tri_employer");
		var candidate = auth.authenticate("tri_candidate");
		auth.activateProfessional(candidate);
		var outsider = auth.authenticate("tri_outsider");

		String jobId = createJob(employer, "[]");
		MvcResult applied = mockMvc.perform(post("/jobs/" + jobId + "/applications")
				.header("X-Session-Token", candidate.token())
				.header("Idempotency-Key", "tri-apply")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("message", "Tenho interesse", "answers", List.of()))))
			.andExpect(status().isCreated())
			// O estado vem do dominio, nao mais de um literal no controller.
			.andExpect(jsonPath("$.status").value("APPLIED"))
			.andReturn();
		String applicationId = objectMapper.readTree(applied.getResponse().getContentAsString()).get("id").asText();

		String url = "/jobs/" + jobId + "/applications/" + applicationId;

		// Sem sessao: 401. Terceiro e o proprio candidato: 403 (so o dono da vaga triagem).
		mockMvc.perform(patch(url).contentType(MediaType.APPLICATION_JSON).content(statusBody("IN_REVIEW")))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(patch(url).header("X-Session-Token", outsider.token())
				.contentType(MediaType.APPLICATION_JSON).content(statusBody("IN_REVIEW")))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NOT_OWNER"));
		mockMvc.perform(patch(url).header("X-Session-Token", candidate.token())
				.contentType(MediaType.APPLICATION_JSON).content(statusBody("ADVANCED")))
			.andExpect(status().isForbidden());

		// Estado que nao existe e erro do cliente (422), nao erro interno.
		mockMvc.perform(patch(url).header("X-Session-Token", employer.token())
				.contentType(MediaType.APPLICATION_JSON).content(statusBody("QUEM_SABE")))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.code").value("INVALID_STATUS"));

		// Pular etapa: 409 e nada muda.
		mockMvc.perform(patch(url).header("X-Session-Token", employer.token())
				.contentType(MediaType.APPLICATION_JSON).content(statusBody("ADVANCED")))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));

		// Caminho valido.
		mockMvc.perform(patch(url).header("X-Session-Token", employer.token())
				.contentType(MediaType.APPLICATION_JSON).content(statusBody("IN_REVIEW")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("IN_REVIEW"));

		// O candidato tambem recebe na caixa de notificacoes, apontando para a vaga.
		mockMvc.perform(get("/notifications").header("X-Session-Token", candidate.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.kind == 'JOB_APPLICATION_STATUS')].href")
				.value(org.hamcrest.Matchers.contains("/vagas/" + jobId)))
			.andExpect(jsonPath("$.items[?(@.kind == 'JOB_APPLICATION_STATUS')].body")
				.value(org.hamcrest.Matchers.contains(org.hamcrest.Matchers.containsString("em análise"))));

		// Persistiu de verdade: a lista do empregador mostra o novo estado.
		mockMvc.perform(get("/jobs/" + jobId + "/applications").header("X-Session-Token", employer.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].status").value("IN_REVIEW"));

		// Repetir o mesmo estado: 409 (senao cada clique viraria mensagem nova).
		mockMvc.perform(patch(url).header("X-Session-Token", employer.token())
				.contentType(MediaType.APPLICATION_JSON).content(statusBody("IN_REVIEW")))
			.andExpect(status().isConflict());

		// Candidatura de outra vaga / inexistente: 404.
		mockMvc.perform(patch("/jobs/" + jobId + "/applications/999999").header("X-Session-Token", employer.token())
				.contentType(MediaType.APPLICATION_JSON).content(statusBody("IN_REVIEW")))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("APPLICATION_NOT_FOUND"));

		// Dois e-mails: o do empregador (candidatura nova) e o do CANDIDATO (mudanca de estado).
		assertThat(sentTo).hasSize(2);
		assertThat(sentTo.get(0)).contains("tri_employer");
		assertThat(sentTo.get(1)).contains("tri_candidate");
		assertThat(sentSubjects.get(1)).contains("análise");
	}

	@Test
	void rejectingRequiresAReasonAndTheCandidateReadsIt() throws Exception {
		var employer = auth.authenticate("job_feedback_owner");
		var candidate = auth.authenticate("job_feedback_candidate");
		auth.activateProfessional(candidate);
		String jobId = createJob(employer, "[]");
		String applicationId = apply(candidate, jobId);

		String url = "/jobs/" + jobId + "/applications/" + applicationId;

		// Reprovar sem dizer por que: recusado pelo dominio (nao pelo banco).
		mockMvc.perform(patch(url).header("X-Session-Token", employer.token())
				.contentType(MediaType.APPLICATION_JSON).content(statusBody("NOT_SELECTED")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("FEEDBACK_REQUIRED"));

		// Com o motivo: passa, e o retorno volta na resposta.
		String reason = "Seguimos com quem ja tinha trabalhado com pagamentos.";
		mockMvc.perform(patch(url).header("X-Session-Token", employer.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("status", "NOT_SELECTED", "feedback", reason))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("NOT_SELECTED"))
			.andExpect(jsonPath("$.feedback").value(reason))
			.andExpect(jsonPath("$.reviewedAt").isNotEmpty());

		// O candidato le o retorno na propria lista (antes so existia no aviso).
		mockMvc.perform(get("/jobs/applications/mine").header("X-Session-Token", candidate.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].jobId").value(jobId))
			.andExpect(jsonPath("$[0].status").value("NOT_SELECTED"))
			.andExpect(jsonPath("$[0].feedback").value(reason));
	}

	private String apply(TestAuthHelper.AuthSession candidate, String jobId) throws Exception {
		MvcResult applied = mockMvc.perform(post("/jobs/" + jobId + "/applications")
				.header("X-Session-Token", candidate.token())
				.header("Idempotency-Key", "feedback-apply-" + jobId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("message", "Tenho interesse", "answers", List.of()))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(applied.getResponse().getContentAsString()).get("id").asText();
	}

	private String statusBody(String value) throws Exception {
		return objectMapper.writeValueAsString(Map.of("status", value));
	}
}
