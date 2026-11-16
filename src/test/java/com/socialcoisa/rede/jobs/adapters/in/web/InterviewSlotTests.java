package com.socialcoisa.rede.jobs.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
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
 * Horário de entrevista saído da agenda que já existe: a sugestão vem da disponibilidade semanal
 * declarada por quem marca (nunca da agenda alheia), o que já está ocupado não é sugerido, e marcar
 * em cima de um compromisso é permitido com aviso — quem decide é a pessoa, não um 409.
 */
@SpringBootTest(properties = "spring.task.scheduling.enabled=false")
@AutoConfigureMockMvc
class InterviewSlotTests {

	private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
	private static final String ZONE = "America/Sao_Paulo";

	/** Uma segunda-feira às 9h de São Paulo: o dia da semana usado nas janelas declaradas. */
	private static final LocalDate MONDAY = LocalDate.of(2027, 3, 1);

	@Autowired MockMvc mockMvc;
	@Autowired ObjectMapper objectMapper;
	@Autowired CalendarEntryRepository entries;

	@MockitoBean ReputationService reputationService;
	@MockitoBean EmailService emailService;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		when(reputationService.reputationOf(anyString())).thenReturn(300);
		when(emailService.sendHtml(anyString(), anyString(), anyString())).thenReturn(true);
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	@Test
	void semDisponibilidadeDeclaradaARespostaDizOMotivoEmVezDeInventarHorario() throws Exception {
		var employer = auth.authenticate("slot_no_avail");
		String jobId = createJob(employer);

		mockMvc.perform(get(slotsPath(jobId)).header("X-Session-Token", employer.token())
				.param("from", monday(9).toString())
				.param("to", monday(18).toString()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.slots.length()").value(0))
			.andExpect(jsonPath("$.reasonCode").value("NO_AVAILABILITY"));
	}

	@Test
	void osHorariosSaemDaDisponibilidadeDoProprioMarcadorEUmaOcupacaoSaiDaLista() throws Exception {
		var employer = auth.authenticate("slot_owner");
		String jobId = createJob(employer);

		// Segunda das 9h às 11h, declarada pelo próprio marcador.
		declareAvailability(employer, 1, "09:00", "11:00");

		MvcResult free = mockMvc.perform(get(slotsPath(jobId)).header("X-Session-Token", employer.token())
				.param("from", monday(8).toString())
				.param("to", monday(12).toString()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.reasonCode").doesNotExist())
			.andReturn();
		var slots = objectMapper.readTree(free.getResponse().getContentAsString()).get("slots");
		// Duas horas com passo de meia hora e duração de uma hora: 9h, 9h30, 10h.
		assertThat(slots.size()).isEqualTo(3);
		assertThat(slots.get(0).get("startsAt").asText()).isEqualTo(monday(9).toString());

		// Um compromisso do próprio marcador em cima das 9h30 tira aquele horário da lista.
		entries.save(CalendarEntry.create("cal_" + System.nanoTime(), employer.userId(), CalendarScope.PROFESSIONAL,
			CalendarKind.MEETING, new CalendarEntrySource(null, null), "Reunião", null,
			monday(9).plus(Duration.ofMinutes(30)), monday(10).plus(Duration.ofMinutes(30)), SAO_PAULO, false,
			Instant.now()));

		MvcResult after = mockMvc.perform(get(slotsPath(jobId)).header("X-Session-Token", employer.token())
				.param("from", monday(8).toString())
				.param("to", monday(12).toString()))
			.andExpect(status().isOk())
			.andReturn();
		var remaining = objectMapper.readTree(after.getResponse().getContentAsString()).get("slots");
		List<String> starts = remaining.valueStream().map(node -> node.get("startsAt").asText()).toList();
		assertThat(starts).doesNotContain(monday(9).plus(Duration.ofMinutes(30)).toString());
	}

	@Test
	void marcarEmCimaDeOutroCompromissoAvisaMasNaoBloqueia() throws Exception {
		var employer = auth.authenticate("slot_conflict_owner");
		var candidate = auth.authenticate("slot_conflict_candidate");
		auth.activateProfessional(candidate);
		String jobId = createJob(employer);
		String applicationId = apply(jobId, candidate);

		// Compromisso prévio do empregador exatamente no horário pretendido.
		Instant starts = monday(9);
		Instant ends = starts.plus(Duration.ofHours(1));
		entries.save(CalendarEntry.create("cal_" + System.nanoTime(), employer.userId(), CalendarScope.PROFESSIONAL,
			CalendarKind.MEETING, new CalendarEntrySource(null, null), "Daily", null, starts, ends, SAO_PAULO, false,
			Instant.now()));

		// Marcar funciona (sobrepor é decisão da pessoa) e a resposta traz o conflito.
		mockMvc.perform(post("/jobs/" + jobId + "/applications/" + applicationId + "/interview")
				.header("X-Session-Token", employer.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(interviewBody(starts, ends)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.conflicts.length()").value(1))
			.andExpect(jsonPath("$.conflicts[0].title").value("Daily"));
	}

	@Test
	void quemNaoConduzAsEntrevistasNaoVeOsHorarios() throws Exception {
		var employer = auth.authenticate("slot_outsider_owner");
		var outsider = auth.authenticate("slot_outsider");
		String jobId = createJob(employer);

		mockMvc.perform(get(slotsPath(jobId)).header("X-Session-Token", outsider.token())
				.param("from", monday(9).toString())
				.param("to", monday(18).toString()))
			.andExpect(status().isForbidden());

		mockMvc.perform(get(slotsPath(jobId)).param("from", monday(9).toString())
				.param("to", monday(18).toString()))
			.andExpect(status().isUnauthorized());
	}

	private Instant monday(int hour) {
		return MONDAY.atTime(LocalTime.of(hour, 0)).atZone(SAO_PAULO).toInstant();
	}

	private String slotsPath(String jobId) {
		return "/jobs/" + jobId + "/interviews/free-slots";
	}

	private String interviewBody(Instant startsAt, Instant endsAt) throws Exception {
		return objectMapper.writeValueAsString(Map.of("startsAt", startsAt.toString(), "endsAt", endsAt.toString(),
			"timeZoneId", ZONE));
	}

	private void declareAvailability(TestAuthHelper.AuthSession who, int dayOfWeek, String from, String to)
			throws Exception {
		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.put("/calendar/availability")
				.header("X-Session-Token", who.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("windows", List.of(Map.of("dayOfWeek", dayOfWeek,
					"startsAt", from, "endsAt", to, "timezone", ZONE))))))
			.andExpect(status().isOk());
	}

	private String createJob(TestAuthHelper.AuthSession session) throws Exception {
		MvcResult result = mockMvc.perform(post("/jobs")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "slot-job-" + session.userId())
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
				.header("Idempotency-Key", "slot-apply-" + candidate.userId())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("message", "Tenho interesse", "answers", List.of()))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}
}
