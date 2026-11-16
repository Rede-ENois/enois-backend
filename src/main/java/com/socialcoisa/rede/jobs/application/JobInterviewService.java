package com.socialcoisa.rede.jobs.application;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.calendar.application.CalendarSourceService;
import com.socialcoisa.rede.calendar.domain.CalendarKind;
import com.socialcoisa.rede.calendar.domain.CalendarScope;
import com.socialcoisa.rede.companies.domain.RecruitmentCapability;
import com.socialcoisa.rede.jobs.application.port.out.JobInterviewNotifier;
import com.socialcoisa.rede.jobs.application.port.out.JobInterviewRepository;
import com.socialcoisa.rede.jobs.application.port.out.JobRepository;
import com.socialcoisa.rede.jobs.domain.Job;
import com.socialcoisa.rede.jobs.domain.JobApplication;
import com.socialcoisa.rede.jobs.domain.JobInterview;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * A entrevista que o empregador marca para uma candidatura, e o compromisso que ela vira na agenda
 * dos DOIS lados (candidato e quem publicou), pela porta de origem do calendário (D12).
 *
 * <p>A origem é <code>JOB_INTERVIEW</code> + id da entrevista. Como a chave da porta é
 * (dono, origem), as duas entradas têm origens iguais e donos diferentes; reagendar ou cancelar
 * aqui move ou cancela as duas em vez de criar outras. É a mesma transação da entrevista: entrevista
 * marcada que não chega à agenda é pior do que a pessoa tentar de novo.
 */
@Service
public class JobInterviewService {

	static final String CALENDAR_SOURCE = "JOB_INTERVIEW";

	private final JobRepository jobs;
	private final JobInterviewRepository interviews;
	private final CalendarSourceService calendar;
	private final JobInterviewNotifier notifier;
	private final JobCommandAuthorization command;
	private final java.time.Clock clock;

	JobInterviewService(JobRepository jobs, JobInterviewRepository interviews, CalendarSourceService calendar,
			JobInterviewNotifier notifier, JobCommandAuthorization command, java.time.Clock clock) {
		this.jobs = jobs;
		this.interviews = interviews;
		this.calendar = calendar;
		this.notifier = notifier;
		this.command = command;
		this.clock = clock;
	}

	/**
	 * Marca (ou reagenda, quando já existe uma valendo) a entrevista da candidatura.
	 *
	 * <p>O autor da vaga marca — e, quando a vaga nasceu numa página, também quem na equipe tem
	 * MANAGE_INTERVIEWS (recrutador, entrevistador). A candidatura tem de ser DESTA vaga: sem
	 * isso, um id de outra candidatura seria alcançável pelo caminho da URL.
	 *
	 * <p>Quem marca fica como {@code employerId} da entrevista: só essa pessoa mexe nela depois —
	 * reagendar e cancelar não se alargam para o resto da equipe.
	 */
	@Transactional
	public JobInterview schedule(String jobId, String applicationId, String employerId, Instant startsAt,
			Instant endsAt, String timeZoneId, String notes) {
		Job job = command.requireCommander(jobId, employerId, RecruitmentCapability.MANAGE_INTERVIEWS,
			"So quem publicou a vaga pode marcar a entrevista.");
		JobApplication application = applicationOfJob(jobId, applicationId);
		Instant now = clock.instant();

		JobInterview interview = interviews.findScheduledByApplication(applicationId)
			.map(existing -> {
				existing.reschedule(startsAt, endsAt, zoneOf(timeZoneId), notes, now);
				return existing;
			})
			.orElseGet(() -> JobInterview.schedule(nextId(), applicationId, jobId, application.userId(), employerId,
				startsAt, endsAt, zoneOf(timeZoneId), notes, now));
		interviews.save(interview);
		syncCalendar(interview, job, application.userId());
		notifier.notifyChange(job, interview, application.userId());
		return interview;
	}

	/** Reagenda uma entrevista existente. Quem marca é quem desmarca: só o empregador. */
	@Transactional
	public JobInterview reschedule(String jobId, String applicationId, String interviewId, String employerId,
			Instant startsAt, Instant endsAt, String timeZoneId, String notes) {
		JobInterview interview = ownedInterview(jobId, applicationId, interviewId, employerId);
		Job job = jobs.findById(interview.jobId())
			.orElseThrow(() -> new ApiException(404, "JOB_NOT_FOUND", "Vaga nao encontrada."));
		interview.reschedule(startsAt, endsAt, zoneOf(timeZoneId), notes, clock.instant());
		interviews.save(interview);
		syncCalendar(interview, job, interview.candidateId());
		notifier.notifyChange(job, interview, interview.candidateId());
		return interview;
	}

	/** Cancela a entrevista e os dois compromissos da agenda (estado publicado, não remoção). */
	@Transactional
	public JobInterview cancel(String jobId, String applicationId, String interviewId, String employerId) {
		JobInterview interview = ownedInterview(jobId, applicationId, interviewId, employerId);
		Job job = jobs.findById(interview.jobId())
			.orElseThrow(() -> new ApiException(404, "JOB_NOT_FOUND", "Vaga nao encontrada."));
		interview.cancel(clock.instant());
		interviews.save(interview);
		calendar.cancel(interview.candidateId(), CALENDAR_SOURCE, interview.id());
		calendar.cancel(employerId, CALENDAR_SOURCE, interview.id());
		notifier.notifyCancelled(job, interview, interview.candidateId());
		return interview;
	}

	/**
	 * As entrevistas valendo nas candidaturas de uma vaga, por id de candidatura. É o que a lista do
	 * empregador usa para mostrar o horário já marcado sem uma consulta por candidatura. O autor da
	 * vaga e a equipe de recrutamento dela (VIEW_CANDIDATES) veem; a ninguém mais.
	 */
	public java.util.Map<String, JobInterview> scheduledByApplication(String jobId, String employerId) {
		command.requireCommander(jobId, employerId, RecruitmentCapability.VIEW_CANDIDATES,
			"So quem publicou a vaga pode marcar a entrevista.");
		return interviews.findScheduledByJob(jobId).stream()
			.collect(java.util.stream.Collectors.toMap(JobInterview::applicationId, interview -> interview));
	}

	/**
	 * As entrevistas valendo nas candidaturas DO USUÁRIO, por id de candidatura, numa consulta só —
	 * é o que "minhas candidaturas" usa para o candidato ver o horário marcado na própria lista.
	 */
	public java.util.Map<String, JobInterview> scheduledForCandidate(java.util.List<JobApplication> applications) {
		return interviews.findScheduledByApplications(applications.stream().map(JobApplication::id).toList())
			.stream()
			.collect(java.util.stream.Collectors.toMap(JobInterview::applicationId, interview -> interview));
	}

	/**
	 * As entrevistas da candidatura, visíveis a quem participa — o autor da vaga ou a equipe de
	 * recrutamento dela (VIEW_CANDIDATES), ou o candidato. A ninguém mais.
	 */
	public List<JobInterview> ofApplication(String jobId, String applicationId, String requesterId) {
		Job job = jobs.findById(jobId)
			.orElseThrow(() -> new ApiException(404, "JOB_NOT_FOUND", "Vaga nao encontrada."));
		JobApplication application = applicationOfJob(jobId, applicationId);
		boolean employer = command.canCommand(job, requesterId, RecruitmentCapability.VIEW_CANDIDATES);
		boolean candidate = application.userId().equals(requesterId);
		if (!employer && !candidate) {
			throw new ApiException(403, "NOT_PARTICIPANT", "So os envolvidos no processo veem as entrevistas.");
		}
		return interviews.findByApplication(applicationId);
	}

	private JobApplication applicationOfJob(String jobId, String applicationId) {
		return jobs.findApplicationById(applicationId)
			.filter(found -> found.jobId().equals(jobId))
			.orElseThrow(() -> new ApiException(404, "APPLICATION_NOT_FOUND", "Candidatura nao encontrada."));
	}

	private JobInterview ownedInterview(String jobId, String applicationId, String interviewId, String employerId) {
		JobInterview interview = interviews.findById(interviewId)
			.filter(found -> found.jobId().equals(jobId) && found.applicationId().equals(applicationId))
			.orElseThrow(() -> new ApiException(404, "INTERVIEW_NOT_FOUND", "Entrevista nao encontrada."));
		if (!interview.employerId().equals(employerId)) {
			throw new ApiException(403, "NOT_OWNER", "So quem marcou a entrevista pode muda-la.");
		}
		return interview;
	}

	/** Grava (ou move) o compromisso nas duas agendas, com a origem da entrevista. */
	private void syncCalendar(JobInterview interview, Job job, String candidateId) {
		if (!interview.scheduled()) {
			return;
		}
		String title = "Entrevista: " + job.title();
		upsert(candidateId, interview, title, job);
		upsert(interview.employerId(), interview, title, job);
	}

	private void upsert(String ownerId, JobInterview interview, String title, Job job) {
		calendar.upsert(new CalendarSourceService.UpsertInput(ownerId, CALENDAR_SOURCE, interview.id(),
			CalendarScope.PROFESSIONAL, CalendarKind.INTERVIEW, title, interview.notes(), interview.startsAt(),
			interview.endsAt(), interview.zone().getId()));
	}

	private static ZoneId zoneOf(String timeZoneId) {
		if (timeZoneId == null || timeZoneId.isBlank()) {
			return ZoneId.of("America/Sao_Paulo");
		}
		try {
			return com.socialcoisa.rede.calendar.domain.CalendarEntry.zoneOf(timeZoneId);
		}
		catch (IllegalArgumentException exception) {
			throw new ApiException(422, "INVALID_TIMEZONE", "Fuso desconhecido: " + timeZoneId);
		}
	}

	private static String nextId() {
		return "jint_" + UUID.randomUUID().toString().replace("-", "");
	}
}
