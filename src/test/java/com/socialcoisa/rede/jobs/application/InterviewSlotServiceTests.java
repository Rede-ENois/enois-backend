package com.socialcoisa.rede.jobs.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.socialcoisa.rede.calendar.application.CalendarAvailabilityService;
import com.socialcoisa.rede.calendar.application.CalendarConflictService;
import com.socialcoisa.rede.calendar.domain.CalendarAvailabilityWindow;

@ExtendWith(MockitoExtension.class)
class InterviewSlotServiceTests {

	private static final Instant AGORA = Instant.parse("2024-03-04T10:00:00Z");
	private static final Instant FIM = Instant.parse("2024-03-04T22:00:00Z");

	@Mock private CalendarAvailabilityService disponibilidade;
	@Mock private CalendarConflictService conflitos;
	@Mock private JobCommandAuthorization comando;

	private InterviewSlotService servico;

	@BeforeEach
	void preparar() {
		servico = new InterviewSlotService(disponibilidade, conflitos, comando,
			Clock.fixed(AGORA, ZoneOffset.UTC));
		when(disponibilidade.weeklyOf("autor_b2")).thenReturn(List.of(
			CalendarAvailabilityWindow.create("ava_b2", "autor_b2", 1,
				LocalTime.of(6, 0), LocalTime.of(18, 0), ZoneId.of("America/Sao_Paulo"), AGORA)));
	}

	@ParameterizedTest
	@CsvSource({
		"2024-03-04T09:00:00Z, 2024-03-04T10:00:00Z",
		"2024-03-04T10:00:00Z, 2024-03-04T10:00:00Z",
		"2024-03-04T14:00:00Z, 2024-03-04T14:00:00Z",
		"2024-03-04T14:10:00Z, 2024-03-04T14:30:00Z"
	})
	void respeitaFromEAgoraSemSairDaGrade(String from, String esperado) {
		var resultado = servico.freeSlots("job_b2", "autor_b2", Instant.parse(from), FIM,
			Duration.ofHours(1));
		List<Instant> inicios = resultado.slots().stream()
			.map(horario -> Instant.parse(horario.startsAt()))
			.toList();
		Instant primeiro = Instant.parse(esperado);

		assertThat(inicios).isNotEmpty().allMatch(inicio -> !inicio.isBefore(primeiro));
		assertThat(inicios.getFirst()).isEqualTo(primeiro);
	}

	@Test
	void consultaAposAJanelaNaoSugereHorariosAnteriores() {
		var resultado = servico.freeSlots("job_b2", "autor_b2", Instant.parse("2024-03-04T21:00:00Z"),
			FIM, Duration.ofHours(1));

		assertThat(resultado.slots()).isEmpty();
		assertThat(resultado.reasonCode()).isEqualTo("NO_FREE_SLOT");
	}
}
