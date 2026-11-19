package com.socialcoisa.rede.calendar.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;

import com.socialcoisa.rede.calendar.application.port.out.CalendarEntryRepository;
import com.socialcoisa.rede.calendar.domain.CalendarEntry;
import com.socialcoisa.rede.calendar.domain.CalendarEntrySource;
import com.socialcoisa.rede.calendar.domain.CalendarKind;
import com.socialcoisa.rede.calendar.domain.CalendarRecurrence;
import com.socialcoisa.rede.calendar.domain.CalendarScope;

@SpringBootTest(properties = "spring.task.scheduling.enabled=false")
class CalendarExtensionTransactionTests {

	private static final Instant AGORA = Instant.parse("2024-01-02T00:00:00Z");

	@Autowired private PlatformTransactionManager transacoes;
	@Autowired private JdbcTemplate jdbc;
	@MockitoSpyBean private CalendarEntryRepository entries;

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void falhaDepoisDeFecharAPontaPreservaASerieParaRetentativa(boolean peloWorker) {
		String sufixo = UUID.randomUUID().toString().replace("-", "");
		String serieId = "cal_ext_" + sufixo;
		String entradaId = "cal_ext_ponta_" + sufixo;
		Instant inicio = Instant.parse("2024-01-01T10:00:00Z");
		CalendarEntry ponta = CalendarEntry.create(entradaId, "cal_ext_dono_" + sufixo,
			CalendarScope.SOCIAL, CalendarKind.MEETING, new CalendarEntrySource(null, null),
			"Reunião", null, inicio, inicio.plus(Duration.ofHours(1)),
			ZoneId.of("America/Sao_Paulo"), false, inicio);
		ponta.joinSeriesQuietly(serieId, CalendarRecurrence.WEEKLY);
		ponta.limitSeries(inicio.plus(Duration.ofDays(7)));
		ponta.openTail();

		AtomicBoolean falhar = new AtomicBoolean(true);
		RuntimeException falha = new RuntimeException("Falha ao criar ocorrência.");

		try (var contexto = new AnnotationConfigApplicationContext()) {
			contexto.registerBean(CalendarEntryRepository.class, () -> entries);
			contexto.registerBean(Clock.class, () -> Clock.fixed(AGORA, ZoneOffset.UTC));
			contexto.registerBean(PlatformTransactionManager.class, () -> transacoes);
			contexto.registerBean(CalendarRecurrenceService.class);
			contexto.refresh();
			CalendarRecurrenceService recurrences = contexto.getBean(CalendarRecurrenceService.class);

			entries.save(ponta);

			doAnswer(chamada -> {
				CalendarEntry entrada = chamada.getArgument(0);
				if (serieId.equals(entrada.seriesId()) && !entradaId.equals(entrada.id())
						&& falhar.getAndSet(false)) {
					throw falha;
				}
				return chamada.callRealMethod();
			}).when(entries).save(any(CalendarEntry.class));

			doAnswer(chamada -> entries.findBySeries(serieId).stream()
				.filter(CalendarEntry::tail).toList())
				.when(entries).findSeriesTails();

			if (peloWorker) {
				recurrences.extendDue();
			}
			else {
				assertThatThrownBy(() -> recurrences.extend(ponta, AGORA)).isSameAs(falha);
			}

			assertThat(falhar.get()).isFalse();
			CalendarEntry preservada = entries.findById(entradaId).orElseThrow();
			assertThat(preservada.tail()).isTrue();
			assertThat(entries.findBySeries(serieId)).hasSize(1);

			if (peloWorker) {
				recurrences.extendDue();
			}
			else {
				assertThat(recurrences.extend(preservada, AGORA)).isEqualTo(1);
			}

			var serie = entries.findBySeries(serieId);
			assertThat(serie).hasSize(2);
			assertThat(serie).extracting(CalendarEntry::startsAt)
				.containsExactly(inicio, inicio.plus(Duration.ofDays(7)));
			assertThat(serie).filteredOn(CalendarEntry::tail)
				.singleElement().satisfies(entrada -> {
					assertThat(entrada.id()).isNotEqualTo(entradaId);
					assertThat(entrada.startsAt()).isEqualTo(inicio.plus(Duration.ofDays(7)));
				});
		}
		finally {
			jdbc.update("delete from calendar_entries where series_id = ?", serieId);
		}
	}
}
