package com.socialcoisa.rede.calendar.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.calendar.application.port.out.CalendarEntryRepository;
import com.socialcoisa.rede.calendar.application.port.out.CalendarParticipantRepository;
import com.socialcoisa.rede.calendar.application.port.out.CalendarReminderRepository;
import com.socialcoisa.rede.calendar.domain.CalendarEntry;
import com.socialcoisa.rede.calendar.domain.CalendarEntrySource;
import com.socialcoisa.rede.calendar.domain.CalendarKind;
import com.socialcoisa.rede.calendar.domain.CalendarParticipant;
import com.socialcoisa.rede.calendar.domain.CalendarReminder;
import com.socialcoisa.rede.calendar.domain.CalendarScope;
import com.socialcoisa.rede.notifications.application.NotificationService;
import com.socialcoisa.rede.shared.web.ApiException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;

/**
 * O worker de lembretes vence em lote: a decisão sobre cada pessoa não pode custar a lista de
 * participantes do compromisso. Este teste é a tranca — a leitura é da linha da pessoa, não da
 * coleção.
 */
class CalendarReminderQueryTests {

	private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");

	@Test
	void theWorkerReadsTheGuestRowInsteadOfTheWholeGuestList() {
		CalendarEntryRepository entries = mock(CalendarEntryRepository.class);
		CalendarParticipantRepository participants = mock(CalendarParticipantRepository.class);
		CalendarReminderRepository reminders = mock(CalendarReminderRepository.class);
		Instant startsAt = Instant.now().plus(Duration.ofMinutes(5));
		CalendarEntry entry = CalendarEntry.create("cap_entry", "host", CalendarScope.SOCIAL,
			CalendarKind.PERSONAL, new CalendarEntrySource(null, null), "Chamada", null, startsAt,
			startsAt.plus(Duration.ofMinutes(30)), SAO_PAULO, false, Instant.now());
		CalendarReminder due = CalendarReminder.create("cap_rem", "cap_entry", "guest", 5, startsAt,
			Instant.now());
		when(entries.findById("cap_entry")).thenReturn(Optional.of(entry));
		when(reminders.findDue(any(), anyInt(), anyInt())).thenReturn(List.of(due));
		when(participants.findByEntryAndUser("cap_entry", "guest")).thenReturn(Optional
			.of(CalendarParticipant.invite("cap_p", "cap_entry", "host", "guest", Instant.now())));

		new CalendarReminderService(reminders, entries, participants, mock(NotificationService.class),
			Clock.systemUTC(), noTransaction()).deliverDue();

		verify(participants, never()).findByEntry(anyString());
		verify(participants).findByEntryAndUser("cap_entry", "guest");
	}

	@Test
	void anInvitedGuestStillNeedsAnAcceptedInviteToBeNotified() {
		CalendarEntryRepository entries = mock(CalendarEntryRepository.class);
		CalendarParticipantRepository participants = mock(CalendarParticipantRepository.class);
		CalendarReminderRepository reminders = mock(CalendarReminderRepository.class);
		Instant startsAt = Instant.now().plus(Duration.ofMinutes(5));
		CalendarEntry entry = CalendarEntry.create("cap_entry2", "host", CalendarScope.SOCIAL,
			CalendarKind.PERSONAL, new CalendarEntrySource(null, null), "Chamada", null, startsAt,
			startsAt.plus(Duration.ofMinutes(30)), SAO_PAULO, false, Instant.now());
		CalendarReminder due = CalendarReminder.create("cap_rem2", "cap_entry2", "guest", 5, startsAt,
			Instant.now());
		when(entries.findById("cap_entry2")).thenReturn(Optional.of(entry));
		when(reminders.findDue(any(), anyInt(), anyInt())).thenReturn(List.of(due));
		// Convite ainda em INVITED: o lembrete é encerrado sem avisar, como manda o contrato.
		when(participants.findByEntryAndUser("cap_entry2", "guest")).thenReturn(Optional
			.of(CalendarParticipant.invite("cap_p2", "cap_entry2", "host", "guest", Instant.now())));

		new CalendarReminderService(reminders, entries, participants, mock(NotificationService.class),
			Clock.systemUTC(), noTransaction()).deliverDue();

		assertThat(due.sentAt()).isNotNull();
	}

	@Test
	void oneFailingReminderDoesNotHoldTheRestOfTheBatchAndItsMarkSurvives() {
		CalendarEntryRepository entries = mock(CalendarEntryRepository.class);
		CalendarParticipantRepository participants = mock(CalendarParticipantRepository.class);
		CalendarReminderRepository reminders = mock(CalendarReminderRepository.class);
		NotificationService notifications = mock(NotificationService.class);
		Instant startsAt = Instant.now().plus(Duration.ofMinutes(5));
		CalendarEntry okEntry = CalendarEntry.create("c3_entry_ok", "host", CalendarScope.SOCIAL,
			CalendarKind.PERSONAL, new CalendarEntrySource(null, null), "Ok", null, startsAt,
			startsAt.plus(Duration.ofMinutes(30)), SAO_PAULO, false, Instant.now());
		CalendarEntry badEntry = CalendarEntry.create("c3_entry_bad", "host", CalendarScope.SOCIAL,
			CalendarKind.PERSONAL, new CalendarEntrySource(null, null), "Quebrado", null, startsAt,
			startsAt.plus(Duration.ofMinutes(30)), SAO_PAULO, false, Instant.now());
		CalendarReminder bad = CalendarReminder.create("c3_rem_bad", "c3_entry_bad", "host", 5, startsAt,
			Instant.now());
		CalendarReminder ok = CalendarReminder.create("c3_rem_ok", "c3_entry_ok", "host", 5, startsAt,
			Instant.now());
		when(entries.findById("c3_entry_bad")).thenReturn(Optional.of(badEntry));
		when(entries.findById("c3_entry_ok")).thenReturn(Optional.of(okEntry));
		when(reminders.findDue(any(), anyInt(), anyInt())).thenReturn(List.of(bad, ok));
		// Só o compromisso quebrado falha ao notificar; o outro canal está de pé.
		doThrow(new ApiException(503, "NOTIFY_DOWN", "canal fora do ar"))
			.when(notifications).create(anyString(), anyString(), anyString(), anyString(), contains("Quebrado"),
				anyString(), anyString());

		int sent = new CalendarReminderService(reminders, entries, participants, notifications,
			Clock.systemUTC(), noTransaction()).deliverDue();

		// O lembrete quebrado não segura o lote: o seguinte sai.
		assertThat(sent).isEqualTo(1);
		// E a marca de falha ficou gravada na linha do quebrado, fora da transação que voltou.
		verify(reminders).save(bad);
		assertThat(bad.attempts()).isEqualTo(1);
		assertThat(bad.lastError()).isNotBlank();
		assertThat(ok.sentAt()).isNotNull();
	}

	/** As consultas do worker não precisam de banco de verdade aqui: transação que não faz nada. */
	private static PlatformTransactionManager noTransaction() {
		return new PlatformTransactionManager() {
			@Override
			public TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException {
				return new org.springframework.transaction.support.SimpleTransactionStatus();
			}

			@Override
			public void commit(TransactionStatus status) throws TransactionException {
			}

			@Override
			public void rollback(TransactionStatus status) throws TransactionException {
			}
		};
	}
}
