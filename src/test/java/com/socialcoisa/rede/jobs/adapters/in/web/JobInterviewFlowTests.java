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

import java.time.Duration;
import java.time.Instant;
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

import com.socialcoisa.rede.calendar.application.port.out.CalendarEntryRepository;
import com.socialcoisa.rede.calendar.domain.CalendarEntry;
import com.socialcoisa.rede.calendar.domain.CalendarEntrySource;
import com.socialcoisa.rede.calendar.domain.CalendarKind;
import com.socialcoisa.rede.calendar.domain.CalendarScope;
import com.socialcoisa.rede.identity.application.EmailService;
import com.socialcoisa.rede.profile.application.ReputationService;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * Entrevista marcada pelo empregador (fase 5): a candidatura ganha um compromisso na agenda dos
 * DOIS lados, reagendar move as duas entradas em vez de duplicar, cancelar publica o cancelamento,
 * e quem não é dono da vaga não marca nada.
 */
@SpringBootTest(properties = "spring.task.scheduling.enabled=false")
@AutoConfigureMockMvc
class JobInterviewFlowTests {

	private static final String ZONE = "America/Sao_Paulo";
	private static final Instant STARTS = Instant.parse("2027-03-10T17:00:00Z");
	private static final Instant ENDS = STARTS.plus(Duration.ofHours(1));

	@Autowired MockMvc mockMvc;
	@Autowired ObjectMapper objectMapper;
	@Autowired CalendarEntryRepository entries;

	@MockitoBean ReputationService reputationService;
	@MockitoBean EmailService emailService;

	private final List<String> sentTo = new ArrayList<>();
	private final List<String> sentSubjects = new ArrayList<>();

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		sentTo.clear();
		sentSubjects.clear();
		when(reputationService.reputationOf(anyString())).thenReturn(300);
		when(emailService.sendHtml(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
			sentTo.add(invocation.getArgument(0, String.class));
			sentSubjects.add(invocation.getArgument(1, String.class));
			return true;
		});
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	@Test
	void employerSchedulesTheInterviewAndBothSidesGetTheCalendarEntry() throws Exception {
		var employer = auth.authenticate("int_employer");
		var candidate = auth.authenticate("int_candidate");
		auth.activateProfessional(candidate);
		var outsider = auth.authenticate("int_outsider");

		String jobId = createJob(employer);
		String applicationId = apply(jobId, candidate);

		// Terceiro não marca entrevista em vaga alheia.
		mockMvc.perform(post(interviewPath(jobId, applicationId))
				.header("X-Session-Token", outsider.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(interviewBody(STARTS, ENDS)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NOT_OWNER"));

		// Sem sessão também não.
		mockMvc.perform(post(interviewPath(jobId, applicationId))
				.contentType(MediaType.APPLICATION_JSON)
				.content(interviewBody(STARTS, ENDS)))
			.andExpect(status().isUnauthorized());

		// O dono marca; a resposta traz o estado e o fuso de quem marcou.
		MvcResult scheduled = mockMvc.perform(post(interviewPath(jobId, applicationId))
				.header("X-Session-Token", employer.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(interviewBody(STARTS, ENDS)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("SCHEDULED"))
			.andExpect(jsonPath("$.startsAt").value(STARTS.toString()))
			.andExpect(jsonPath("$.timeZoneId").value(ZONE))
			.andExpect(jsonPath("$.candidateId").value(candidate.userId()))
			.andReturn();
		String interviewId = objectMapper.readTree(scheduled.getResponse().getContentAsString()).get("id").asText();

		// A agenda dos DOIS lados tem o compromisso, com a mesma origem e o tipo INTERVIEW.
		CalendarEntry candidateEntry = entryOf(candidate.userId(), interviewId);
		CalendarEntry employerEntry = entryOf(employer.userId(), interviewId);
		assertThat(candidateEntry.kind()).isEqualTo(CalendarKind.INTERVIEW);
		assertThat(candidateEntry.scope()).isEqualTo(CalendarScope.PROFESSIONAL);
		assertThat(candidateEntry.startsAt()).isEqualTo(STARTS);
		assertThat(employerEntry.startsAt()).isEqualTo(STARTS);
		assertThat(employerEntry.title()).contains("Entrevista");

		// O candidato foi avisado por e-mail.
		assertThat(sentTo).contains("int_candidate@test.com");
		assertThat(sentSubjects).anySatisfy(subject -> assertThat(subject).containsIgnoringCase("entrevista"));

		// O candidato vê a entrevista; um terceiro não.
		mockMvc.perform(get(interviewPath(jobId, applicationId)).header("X-Session-Token", candidate.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].id").value(interviewId));
		mockMvc.perform(get(interviewPath(jobId, applicationId)).header("X-Session-Token", outsider.token()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NOT_PARTICIPANT"));
	}

	@Test
	void reschedulingMovesBothEntriesInsteadOfDuplicatingThem() throws Exception {
		var employer = auth.authenticate("int_res_employer");
		var candidate = auth.authenticate("int_res_candidate");
		auth.activateProfessional(candidate);

		String jobId = createJob(employer);
		String applicationId = apply(jobId, candidate);
		String interviewId = schedule(jobId, applicationId, employer, STARTS, ENDS);

		Instant moved = STARTS.plus(Duration.ofDays(1));
		mockMvc.perform(patch(interviewPath(jobId, applicationId) + "/" + interviewId)
				.header("X-Session-Token", employer.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(interviewBody(moved, moved.plus(Duration.ofHours(1)))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.startsAt").value(moved.toString()));

		// Mesma origem, mesmo id de entrada: moveu, não duplicou.
		CalendarEntry candidateEntry = entryOf(candidate.userId(), interviewId);
		CalendarEntry employerEntry = entryOf(employer.userId(), interviewId);
		assertThat(candidateEntry.startsAt()).isEqualTo(moved);
		assertThat(employerEntry.startsAt()).isEqualTo(moved);
		assertThat(entries.findBySource(candidate.userId(), new CalendarEntrySource("JOB_INTERVIEW", interviewId)))
			.isPresent();
	}

	@Test
	void cancellingPublishesTheCancellationOnBothSides() throws Exception {
		var employer = auth.authenticate("int_cancel_employer");
		var candidate = auth.authenticate("int_cancel_candidate");
		auth.activateProfessional(candidate);

		String jobId = createJob(employer);
		String applicationId = apply(jobId, candidate);
		String interviewId = schedule(jobId, applicationId, employer, STARTS, ENDS);

		mockMvc.perform(delete(interviewPath(jobId, applicationId) + "/" + interviewId)
				.header("X-Session-Token", employer.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CANCELLED"))
			.andExpect(jsonPath("$.cancelledAt").isNotEmpty());

		// A entrada continua existindo, cancelada: é o que publica o cancelamento no .ics.
		assertThat(entryOf(candidate.userId(), interviewId).active()).isFalse();
		assertThat(entryOf(employer.userId(), interviewId).active()).isFalse();

		// Cancelar de novo é erro de estado, não sucesso silencioso.
		mockMvc.perform(delete(interviewPath(jobId, applicationId) + "/" + interviewId)
				.header("X-Session-Token", employer.token()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("INTERVIEW_CANCELLED"));
	}

	@Test
	void schedulingInThePastIsRejected() throws Exception {
		var employer = auth.authenticate("int_past_employer");
		var candidate = auth.authenticate("int_past_candidate");
		auth.activateProfessional(candidate);

		String jobId = createJob(employer);
		String applicationId = apply(jobId, candidate);

		Instant past = Instant.now().minus(Duration.ofDays(1));
		mockMvc.perform(post(interviewPath(jobId, applicationId))
				.header("X-Session-Token", employer.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(interviewBody(past, past.plus(Duration.ofHours(1)))))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.code").value("INTERVIEW_IN_PAST"));
	}

	@Test
	void schedulingTwiceReusesTheSameInterview() throws Exception {
		var employer = auth.authenticate("int_twice_employer");
		var candidate = auth.authenticate("int_twice_candidate");
		auth.activateProfessional(candidate);

		String jobId = createJob(employer);
		String applicationId = apply(jobId, candidate);
		String first = schedule(jobId, applicationId, employer, STARTS, ENDS);

		Instant moved = STARTS.plus(Duration.ofHours(2));
		MvcResult second = mockMvc.perform(post(interviewPath(jobId, applicationId))
				.header("X-Session-Token", employer.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(interviewBody(moved, moved.plus(Duration.ofHours(1)))))
			.andExpect(status().isCreated())
			.andReturn();
		String secondId = objectMapper.readTree(second.getResponse().getContentAsString()).get("id").asText();

		// A mesma entrevista foi reagendada: não nasceu uma segunda.
		assertThat(secondId).isEqualTo(first);
		assertThat(entryOf(candidate.userId(), first).startsAt()).isEqualTo(moved);
	}

	private String interviewPath(String jobId, String applicationId) {
		return "/jobs/" + jobId + "/applications/" + applicationId + "/interview";
	}

	private String interviewBody(Instant startsAt, Instant endsAt) throws Exception {
		return objectMapper.writeValueAsString(Map.of("startsAt", startsAt.toString(), "endsAt", endsAt.toString(),
			"timeZoneId", ZONE, "notes", "Leve seu portfólio."));
	}

	private String schedule(String jobId, String applicationId, TestAuthHelper.AuthSession employer, Instant startsAt,
			Instant endsAt) throws Exception {
		MvcResult result = mockMvc.perform(post(interviewPath(jobId, applicationId))
				.header("X-Session-Token", employer.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(interviewBody(startsAt, endsAt)))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private CalendarEntry entryOf(String ownerId, String interviewId) {
		return entries.findBySource(ownerId, new CalendarEntrySource("JOB_INTERVIEW", interviewId))
			.orElseThrow(() -> new AssertionError("entrada da agenda ausente para " + ownerId));
	}

	private String createJob(TestAuthHelper.AuthSession session) throws Exception {
		MvcResult result = mockMvc.perform(post("/jobs")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "int-job-" + session.userId())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"title":"Pessoa Desenvolvedora","company":"Acme","location":"Remoto","type":"CLT","level":"PLENO",
					 "currency":"BRL","salaryMin":"1500","salaryPeriod":"MONTH","description":"Vaga de teste com valor.",
					 "skills":["Java"],"communityAgreement":true,"applicationFields":[]}"""))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private String apply(String jobId, TestAuthHelper.AuthSession candidate) throws Exception {
		MvcResult result = mockMvc.perform(post("/jobs/" + jobId + "/applications")
				.header("X-Session-Token", candidate.token())
				.header("Idempotency-Key", "int-apply-" + candidate.userId())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("message", "Tenho interesse", "answers", List.of()))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}
}
