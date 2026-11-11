package com.socialcoisa.rede.jobs.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Entrevista marcada pelo empregador para uma candidatura.
 *
 * <p>É o fato que gera o compromisso na agenda dos dois lados: o candidato e quem publicou a vaga
 * precisam ver a mesma conversa no mesmo horário. A entrada da agenda é gravada pelo módulo de
 * vagas pela porta de origem do calendário, com o id desta entrevista — então reagendar aqui move
 * as duas entradas em vez de criar outras.
 *
 * <p>O fuso é o de quem marcou. O instante responde "quando", e o fuso preserva "às 14h" quando a
 * regra de horário do lugar mudar.
 */
public class JobInterview {

	/** Teto de duração: entrevista de mais de um dia útil é erro de digitação, não conversa. */
	public static final Duration MAX_DURATION = Duration.ofHours(8);
	public static final int MAX_NOTES = 1000;

	private final String id;
	private final String applicationId;
	private final String jobId;
	private final String candidateId;
	private final String employerId;
	private final Instant createdAt;
	private Instant startsAt;
	private Instant endsAt;
	private ZoneId zone;
	private String notes;
	private JobInterviewStatus status;
	private Instant updatedAt;
	private Instant cancelledAt;

	private JobInterview(String id, String applicationId, String jobId, String candidateId, String employerId,
			Instant startsAt, Instant endsAt, ZoneId zone, String notes, JobInterviewStatus status, Instant createdAt,
			Instant updatedAt, Instant cancelledAt) {
		this.id = Objects.requireNonNull(id, "id");
		this.applicationId = requireText(applicationId, "candidatura");
		this.jobId = requireText(jobId, "vaga");
		this.candidateId = requireText(candidateId, "candidato");
		this.employerId = requireText(employerId, "empregador");
		this.createdAt = Objects.requireNonNull(createdAt, "criação");
		this.updatedAt = Objects.requireNonNull(updatedAt, "atualização");
		this.status = Objects.requireNonNull(status, "situação");
		this.cancelledAt = cancelledAt;
		this.zone = Objects.requireNonNull(zone, "fuso");
		this.notes = checkNotes(notes);
		applyInterval(startsAt, endsAt);
	}

	/**
	 * Marca a entrevista. Recusa horário no passado: marcar para ontem é erro de digitação, e a
	 * agenda mostraria um compromisso que ninguém pode cumprir.
	 */
	public static JobInterview schedule(String id, String applicationId, String jobId, String candidateId,
			String employerId, Instant startsAt, Instant endsAt, ZoneId zone, String notes, Instant now) {
		Instant start = Objects.requireNonNull(startsAt, "início");
		if (!start.isAfter(now)) {
			throw new ApiException(422, "INTERVIEW_IN_PAST", "A entrevista precisa ser marcada para o futuro.");
		}
		return new JobInterview(id, applicationId, jobId, candidateId, employerId, startsAt, endsAt, zone, notes,
			JobInterviewStatus.SCHEDULED, now, now, null);
	}

	public static JobInterview restore(String id, String applicationId, String jobId, String candidateId,
			String employerId, Instant startsAt, Instant endsAt, ZoneId zone, String notes,
			JobInterviewStatus status, Instant createdAt, Instant updatedAt, Instant cancelledAt) {
		return new JobInterview(id, applicationId, jobId, candidateId, employerId, startsAt, endsAt, zone, notes,
			status, createdAt, updatedAt, cancelledAt);
	}

	/**
	 * Reagenda. Mantém a identidade e a origem, então a entrada da agenda é movida, não duplicada.
	 * Reagendar para o mesmo horário não mexe em `updatedAt`.
	 */
	public void reschedule(Instant newStartsAt, Instant newEndsAt, ZoneId newZone, String newNotes, Instant now) {
		requireScheduled("reagendar");
		Instant start = Objects.requireNonNull(newStartsAt, "início");
		if (!start.isAfter(now)) {
			throw new ApiException(422, "INTERVIEW_IN_PAST", "A entrevista precisa ser marcada para o futuro.");
		}
		checkInterval(start, newEndsAt);
		ZoneId newZoneId = Objects.requireNonNull(newZone, "fuso");
		String checkedNotes = checkNotes(newNotes);
		if (start.equals(startsAt) && newEndsAt.equals(endsAt) && newZoneId.equals(zone)
				&& Objects.equals(checkedNotes, notes)) {
			return;
		}
		startsAt = start;
		endsAt = newEndsAt;
		zone = newZoneId;
		notes = checkedNotes;
		updatedAt = Objects.requireNonNull(now, "atualização");
	}

	public void cancel(Instant now) {
		requireScheduled("cancelar");
		Instant at = Objects.requireNonNull(now, "cancelamento");
		status = JobInterviewStatus.CANCELLED;
		cancelledAt = at;
		updatedAt = at;
	}

	public boolean scheduled() {
		return status == JobInterviewStatus.SCHEDULED;
	}

	private void applyInterval(Instant start, Instant end) {
		checkInterval(start, end);
		startsAt = start;
		endsAt = end;
	}

	private static void checkInterval(Instant start, Instant end) {
		Instant begin = Objects.requireNonNull(start, "início");
		Instant finish = Objects.requireNonNull(end, "fim");
		if (!finish.isAfter(begin)) {
			throw new ApiException(422, "INVALID_INTERVIEW_WINDOW", "O fim da entrevista tem de ser depois do início.");
		}
		if (Duration.between(begin, finish).compareTo(MAX_DURATION) > 0) {
			throw new ApiException(422, "INVALID_INTERVIEW_WINDOW",
				"A entrevista não pode durar mais de " + MAX_DURATION.toHours() + " horas.");
		}
	}

	private static String checkNotes(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String clean = value.trim();
		if (clean.length() > MAX_NOTES) {
			throw new ApiException(422, "INTERVIEW_NOTES_TOO_LONG",
				"As observações da entrevista devem ter no máximo " + MAX_NOTES + " caracteres.");
		}
		return clean;
	}

	private void requireScheduled(String action) {
		if (status == JobInterviewStatus.CANCELLED) {
			throw new ApiException(409, "INTERVIEW_CANCELLED", "Não dá para " + action + " uma entrevista cancelada.");
		}
	}

	private static String requireText(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(field + " é obrigatório");
		}
		return value;
	}

	public String id() { return id; }

	public String applicationId() { return applicationId; }

	public String jobId() { return jobId; }

	public String candidateId() { return candidateId; }

	public String employerId() { return employerId; }

	public Instant startsAt() { return startsAt; }

	public Instant endsAt() { return endsAt; }

	public ZoneId zone() { return zone; }

	public String notes() { return notes; }

	public JobInterviewStatus status() { return status; }

	public Instant createdAt() { return createdAt; }

	public Instant updatedAt() { return updatedAt; }

	public Instant cancelledAt() { return cancelledAt; }
}
