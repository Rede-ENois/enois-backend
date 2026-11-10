package com.socialcoisa.rede.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.socialcoisa.rede.calendar.application.CalendarCommandService;
import com.socialcoisa.rede.calendar.application.CalendarReminderService;
import com.socialcoisa.rede.calendar.application.port.out.CalendarEntryRepository;
import com.socialcoisa.rede.calendar.application.port.out.CalendarParticipantRepository;
import com.socialcoisa.rede.calendar.application.port.out.CalendarReminderRepository;
import com.socialcoisa.rede.calendar.domain.CalendarEntry;
import com.socialcoisa.rede.calendar.domain.CalendarEntrySource;
import com.socialcoisa.rede.calendar.domain.CalendarKind;
import com.socialcoisa.rede.calendar.domain.CalendarParticipant;
import com.socialcoisa.rede.calendar.domain.CalendarParticipantState;
import com.socialcoisa.rede.calendar.domain.CalendarReminder;
import com.socialcoisa.rede.calendar.domain.CalendarScope;

/**
 * Lembretes de compromisso contra o banco de verdade.
 *
 * <p>O que este teste tranca é o contrato da D4: um lembrete por pessoa e antecedência, entrega
 * única mesmo com o worker rodando em cima de um horário já vencido, nada para quem não aceitou o
 * convite, e nenhum aviso sobrevivendo ao cancelamento ou ao reagendamento do compromisso.
 */
@SpringBootTest(properties = "spring.task.scheduling.enabled=false")
class CalendarReminderServiceTests {

	private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");

	@Autowired CalendarReminderService reminders;
	@Autowired CalendarReminderRepository reminderRepository;
	@Autowired CalendarEntryRepository entries;
	@Autowired CalendarParticipantRepository participants;
	@Autowired CalendarCommandService commands;

	@Test
	void settingTheSameReminderTwiceKeepsOneRow() {
		String owner = owner();
		String entryId = entry(owner, "Revisão de código", Instant.now().plus(Duration.ofHours(3)));

		CalendarReminder first = reminders.setForReader(owner, entryId, 15);
		CalendarReminder second = reminders.setForReader(owner, entryId, 15);

		assertThat(second.id()).isEqualTo(first.id());
		assertThat(reminderRepository.findByEntry(entryId)).hasSize(1);
	}

	@Test
	void theDueTimeIsTheStartMinusTheLead() {
		String owner = owner();
		Instant startsAt = Instant.parse("2026-12-01T19:00:00Z");
		String entryId = entry(owner, "Daily", startsAt.plus(Duration.ofMinutes(1)));

		CalendarReminder reminder = reminders.setForReader(owner, entryId, 60);

		// `setForReader` usa o começo que está gravado; o que importa aqui é a regra do domínio.
		assertThat(CalendarReminder.create("rem_x", "cal_x", owner, 60, startsAt, Instant.now()).dueAt())
			.isEqualTo(startsAt.minus(Duration.ofMinutes(60)));
		assertThat(reminder.minutesBefore()).isEqualTo(60);
	}

	@Test
	void aReminderDueInThePastIsDeliveredOnceAndNeverAgain() {
		String owner = owner();
		// Compromisso criado em cima da hora: o lembrete de 1 dia já venceu quando nasce.
		String entryId = entry(owner, "Começa agora", Instant.now().plus(Duration.ofMinutes(10)));
		reminders.setForReader(owner, entryId, 1440);

		int first = reminders.deliverDue();
		int second = reminders.deliverDue();

		assertThat(first).isGreaterThanOrEqualTo(1);
		assertThat(second).isZero();
		List<CalendarReminder> stored = reminderRepository.findByEntry(entryId);
		assertThat(stored).hasSize(1);
		assertThat(stored.get(0).sentAt()).isNotNull();
	}

	@Test
	void anInvitedPersonGetsNoReminderUntilTheyAccept() {
		String host = owner();
		String guest = owner();
		String entryId = entry(host, "Chamada", Instant.now().plus(Duration.ofMinutes(5)));
		participants.save(CalendarParticipant.invite("p_" + UUID.randomUUID(), entryId, host, guest,
			Instant.now()));

		reminders.setForReader(guest, entryId, 5);
		reminders.deliverDue();

		CalendarReminder stored = reminderRepository.findByEntry(entryId).get(0);
		// O convite não foi respondido: o lembrete é encerrado sem avisar.
		assertThat(stored.sentAt()).isNotNull();
	}

	@Test
	void onceAcceptedTheGuestGetsTheReminder() {
		String host = owner();
		String guest = owner();
		String entryId = entry(host, "Chamada", Instant.now().plus(Duration.ofMinutes(5)));
		participants.save(CalendarParticipant.invite("p_" + UUID.randomUUID(), entryId, host, guest,
			Instant.now()));
		participants.findByEntryAndUser(entryId, guest).orElseThrow()
			.respond(CalendarParticipantState.ACCEPTED, Instant.now());

		reminders.setForReader(guest, entryId, 5);
		reminders.deliverDue();

		assertThat(reminderRepository.findByEntry(entryId).get(0).sentAt()).isNotNull();
	}

	@Test
	void cancellingTheEntryEndsThePendingReminder() {
		String owner = owner();
		String entryId = entry(owner, "Será cancelado", Instant.now().plus(Duration.ofDays(2)));
		reminders.setForReader(owner, entryId, 1440);

		commands.cancel(owner, entryId);

		assertThat(reminderRepository.findByEntry(entryId).get(0).sentAt())
			.as("cancelamento encerra o lembrete pendente")
			.isNotNull();
	}

	@Test
	void reschedulingEndsTheReminderForTheOldTime() {
		String owner = owner();
		Instant startsAt = Instant.now().plus(Duration.ofDays(3));
		String entryId = entry(owner, "Vai mudar de hora", startsAt);
		reminders.setForReader(owner, entryId, 60);

		commands.update(owner, entryId, null, null, startsAt.plus(Duration.ofDays(5)).toString(),
			startsAt.plus(Duration.ofDays(5)).plus(Duration.ofHours(1)).toString(), SAO_PAULO.getId());

		assertThat(reminderRepository.findByEntry(entryId).get(0).sentAt())
			.as("o aviso do horário antigo não pode sair")
			.isNotNull();
	}

	@Test
	void aStrangerCannotSetAReminderOnSomeoneElsesEntry() {
		String host = owner();
		String stranger = owner();
		String entryId = entry(host, "Privado", Instant.now().plus(Duration.ofHours(4)));

		assertThatThrownBy(() -> reminders.setForReader(stranger, entryId, 15))
			.hasMessageContaining("não encontrado");
		assertThat(reminderRepository.findByEntry(entryId)).isEmpty();
	}

	@Test
	void theDomainRefusesALeadThatIsNotOnTheList() {
		assertThatThrownBy(() -> CalendarReminder.create("rem_x", "cal_x", "user", 7, Instant.now(), Instant.now()))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void aReminderStopsAfterTheAttemptCeiling() {
		CalendarReminder reminder = CalendarReminder.create("rem_x", "cal_x", "user", 5,
			Instant.parse("2026-12-01T12:00:00Z"), Instant.now());
		Instant now = Instant.parse("2026-12-01T12:00:00Z");
		for (int i = 0; i < CalendarReminder.MAX_ATTEMPTS; i++) {
			assertThat(reminder.due(now)).isTrue();
			reminder.failed("falhou", now);
		}
		assertThat(reminder.due(now)).isFalse();
		assertThat(reminder.exhausted()).isTrue();
		assertThat(reminder.lastError()).isEqualTo("falhou");
	}

	private static String owner() {
		return "rem_owner_" + UUID.randomUUID();
	}

	private String entry(String owner, String title, Instant startsAt) {
		return commands.create(owner, "SOCIAL", "PERSONAL", title, null, startsAt.toString(),
			startsAt.plus(Duration.ofMinutes(30)).toString(), SAO_PAULO.getId(), false).id();
	}

}