package com.socialcoisa.rede.jobs.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Regras do domínio da entrevista: horário no passado é recusado, janela inválida é recusada,
 * cancelada não reagenda, e reconfirmar o mesmo horário não é mudança.
 */
class JobInterviewTests {

	private static final Instant NOW = Instant.parse("2027-03-01T12:00:00Z");
	private static final Instant STARTS = Instant.parse("2027-03-10T17:00:00Z");
	private static final Instant ENDS = STARTS.plus(Duration.ofHours(1));

	private JobInterview interview() {
		return JobInterview.schedule("jint_1", "10", "job_1", "cand", "emp", STARTS, ENDS,
			ZoneId.of("America/Sao_Paulo"), "Leve seu portfólio.", NOW);
	}

	@Test
	void refusesThePast() {
		Instant past = NOW.minus(Duration.ofHours(1));
		assertThatThrownBy(() -> JobInterview.schedule("jint_2", "10", "job_1", "cand", "emp", past,
			past.plus(Duration.ofHours(1)), ZoneId.of("America/Sao_Paulo"), null, NOW))
			.isInstanceOfSatisfying(ApiException.class,
				exception -> assertThat(exception.code()).isEqualTo("INTERVIEW_IN_PAST"));
	}

	@Test
	void refusesEndBeforeStartAndTooLongWindows() {
		assertThatThrownBy(() -> JobInterview.schedule("jint_3", "10", "job_1", "cand", "emp", STARTS, STARTS,
			ZoneId.of("America/Sao_Paulo"), null, NOW))
			.isInstanceOfSatisfying(ApiException.class,
				exception -> assertThat(exception.code()).isEqualTo("INVALID_INTERVIEW_WINDOW"));
		assertThatThrownBy(() -> JobInterview.schedule("jint_4", "10", "job_1", "cand", "emp", STARTS,
			STARTS.plus(Duration.ofHours(9)), ZoneId.of("America/Sao_Paulo"), null, NOW))
			.isInstanceOfSatisfying(ApiException.class,
				exception -> assertThat(exception.code()).isEqualTo("INVALID_INTERVIEW_WINDOW"));
	}

	@Test
	void cancelledInterviewCannotBeRescheduledOrCancelledAgain() {
		JobInterview cancelled = interview();
		cancelled.cancel(NOW.plus(Duration.ofDays(1)));
		assertThat(cancelled.scheduled()).isFalse();
		assertThatThrownBy(() -> cancelled.reschedule(STARTS.plus(Duration.ofDays(2)),
			STARTS.plus(Duration.ofDays(2)).plus(Duration.ofHours(1)), ZoneId.of("America/Sao_Paulo"), null,
			NOW.plus(Duration.ofDays(2))))
			.isInstanceOfSatisfying(ApiException.class,
				exception -> assertThat(exception.code()).isEqualTo("INTERVIEW_CANCELLED"));
		assertThatThrownBy(() -> cancelled.cancel(NOW.plus(Duration.ofDays(2))))
			.isInstanceOfSatisfying(ApiException.class,
				exception -> assertThat(exception.code()).isEqualTo("INTERVIEW_CANCELLED"));
	}

	@Test
	void reschedulingToTheSameWindowKeepsUpdatedAt() {
		JobInterview interview = interview();
		Instant updatedAt = interview.updatedAt();
		interview.reschedule(STARTS, ENDS, ZoneId.of("America/Sao_Paulo"), "Leve seu portfólio.",
			NOW.plus(Duration.ofHours(1)));
		assertThat(interview.updatedAt()).isEqualTo(updatedAt);
	}

	@Test
	void reschedulingMovesTheWindowAndKeepsIdentity() {
		JobInterview interview = interview();
		Instant moved = STARTS.plus(Duration.ofDays(1));
		interview.reschedule(moved, moved.plus(Duration.ofHours(2)), ZoneId.of("Europe/Lisbon"), null,
			NOW.plus(Duration.ofHours(1)));
		assertThat(interview.startsAt()).isEqualTo(moved);
		assertThat(interview.zone()).isEqualTo(ZoneId.of("Europe/Lisbon"));
		assertThat(interview.notes()).isNull();
		assertThat(interview.id()).isEqualTo("jint_1");
	}

	@Test
	void notesAreTrimmedAndCapped() {
		assertThat(interview().notes()).isEqualTo("Leve seu portfólio.");
		assertThatThrownBy(() -> JobInterview.schedule("jint_5", "10", "job_1", "cand", "emp", STARTS, ENDS,
			ZoneId.of("America/Sao_Paulo"), "x".repeat(1001), NOW))
			.isInstanceOfSatisfying(ApiException.class,
				exception -> assertThat(exception.code()).isEqualTo("INTERVIEW_NOTES_TOO_LONG"));
	}
}
