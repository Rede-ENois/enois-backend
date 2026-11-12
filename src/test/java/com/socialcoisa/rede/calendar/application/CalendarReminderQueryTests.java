package com.socialcoisa.rede.calendar.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
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
			Clock.systemUTC()).deliverDue();

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
			Clock.systemUTC()).deliverDue();

		assertThat(due.sentAt()).isNotNull();
	}

}
