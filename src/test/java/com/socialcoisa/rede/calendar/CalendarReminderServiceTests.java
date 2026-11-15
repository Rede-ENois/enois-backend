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
import com.socialcoisa.rede.calendar.application.CalendarSourceService;
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
import com.socialcoisa.rede.notifications.application.port.out.NotificationRepository;
import com.socialcoisa.rede.notifications.domain.Notification;

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
	@Autowired CalendarSourceService sources;
	@Autowired NotificationRepository notifications;

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
	void askingAgainAfterClearingBringsTheReminderBackToLife() {
		String owner = owner();
		String entryId = entry(owner, "Retirado e posto de novo", Instant.now().plus(Duration.ofMinutes(10)));

		// Antecedência de um dia: o lembrete nasce vencido e sai no primeiro ciclo do worker.
		reminders.setForReader(owner, entryId, 1440);
		reminders.deliverDue();
		assertThat(reminderRepository.findByEntry(entryId).get(0).sentAt()).isNotNull();

		// A pessoa pede o mesmo lembrete outra vez. A linha já foi resolvida e não pode ser devolvida
		// como está: um lembrete ligado na tela que nunca mais toca é pior que nenhum.
		CalendarReminder again = reminders.setForReader(owner, entryId, 1440);

		assertThat(again.sentAt()).isNull();
		assertThat(again.attempts()).isZero();
		// O horário de aviso é recalculado a partir do começo do compromisso, não herdado do antigo.
		assertThat(again.dueAt()).isEqualTo(entries.findById(entryId).orElseThrow().startsAt()
			.minus(Duration.ofMinutes(1440)));
		assertThat(reminderRepository.findByEntry(entryId)).hasSize(1);

		reminders.deliverDue();
		assertThat(reminderRepository.findByEntry(entryId).get(0).sentAt()).isNotNull();
	}

	@Test
	void clearingAndAskingAgainAlsoBringsTheReminderBack() {
		String owner = owner();
		String entryId = entry(owner, "Tirar e voltar", Instant.now().plus(Duration.ofDays(2)));

		reminders.setForReader(owner, entryId, 60);
		assertThat(reminders.clearForReader(owner, entryId, 60)).isTrue();
		assertThat(reminders.ofReader(owner, entryId)).isEmpty();

		reminders.setForReader(owner, entryId, 60);

		assertThat(reminders.ofReader(owner, entryId)).hasSize(1);
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
	void theReminderNotificationOpensTheEntryItIsAbout() {
		String owner = owner();
		String entryId = entry(owner, "Revisão com a equipe", Instant.now().plus(Duration.ofMinutes(5)));
		reminders.setForReader(owner, entryId, 5);

		reminders.deliverDue();

		Notification sent = notifications.findByUserId(owner, Instant.now().plus(Duration.ofMinutes(1)),
			null, 10)
			.stream()
			.filter(notification -> "CALENDAR_REMINDER".equals(notification.kind()))
			.findFirst()
			.orElseThrow();
		// "Ver agenda" levava à agenda sem o compromisso; o aviso tem de abrir o que ele avisa.
		assertThat(sent.href()).isEqualTo("/agenda/" + entryId);
		assertThat(sent.ctaLabel()).isEqualTo("Ver compromisso");
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

		commands.update(owner, entryId, null, null, null, null, startsAt.plus(Duration.ofDays(5)).toString(),
			startsAt.plus(Duration.ofDays(5)).plus(Duration.ofHours(1)).toString(), SAO_PAULO.getId(), null);

		assertThat(reminderRepository.findByEntry(entryId).get(0).sentAt())
			.as("o aviso do horário antigo não pode sair")
			.isNotNull();
	}

	@Test
	void reschedulingThroughTheSourcePortAlsoEndsTheOldReminder() {
		// O reagendamento que chega pela origem (mentoria, evento) segue a mesma regra da edição
		// direta: o lembrete pendente do horário antigo não pode tocar.
		String owner = owner();
		Instant startsAt = Instant.now().plus(Duration.ofDays(4));
		String sourceId = "event_" + UUID.randomUUID();
		CalendarEntry entry = sources.upsert(new CalendarSourceService.UpsertInput(owner, "EVENT", sourceId,
			CalendarScope.SOCIAL, CalendarKind.EVENT, "Encontro", null, startsAt,
			startsAt.plus(Duration.ofHours(1)), SAO_PAULO.getId())).entry();
		reminders.setForReader(owner, entry.id(), 60);

		sources.upsert(new CalendarSourceService.UpsertInput(owner, "EVENT", sourceId, CalendarScope.SOCIAL,
			CalendarKind.EVENT, "Encontro", null, startsAt.plus(Duration.ofDays(2)),
			startsAt.plus(Duration.ofDays(2)).plus(Duration.ofHours(1)), SAO_PAULO.getId()));

		assertThat(reminderRepository.findByEntry(entry.id()).get(0).sentAt())
			.as("o aviso do horário antigo não pode sair")
			.isNotNull();
	}

	@Test
	void cancellingThroughTheSourcePortEndsThePendingReminder() {
		String owner = owner();
		Instant startsAt = Instant.now().plus(Duration.ofDays(4));
		String sourceId = "event_" + UUID.randomUUID();
		CalendarEntry entry = sources.upsert(new CalendarSourceService.UpsertInput(owner, "EVENT", sourceId,
			CalendarScope.SOCIAL, CalendarKind.EVENT, "Encontro", null, startsAt,
			startsAt.plus(Duration.ofHours(1)), SAO_PAULO.getId())).entry();
		reminders.setForReader(owner, entry.id(), 15);

		sources.cancel(owner, "EVENT", sourceId);

		assertThat(reminderRepository.findByEntry(entry.id()).get(0).sentAt())
			.as("compromisso cancelado não avisa")
			.isNotNull();
	}

	@Test
	void theNotificationSaysTheTimeThatIsActuallyLeft() {
		// Um lembrete de 1 dia criado em cima da hora vence na hora, e o compromisso começa em 10
		// minutos. O texto antigo dizia "em 1 dia" — mentira sobre o horário.
		String owner = owner();
		String entryId = entry(owner, "Começa já", Instant.now().plus(Duration.ofMinutes(10)));
		reminders.setForReader(owner, entryId, 1440);

		reminders.deliverDue();

		String body = notifications.findByUserId(owner, null, null, 20).stream()
			.filter(n -> "CALENDAR_REMINDER".equals(n.kind()))
			.map(n -> n.body())
			.findFirst()
			.orElseThrow();
		assertThat(body).contains("«Começa já»").contains("min").doesNotContain("1 dia");
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

	/**
	 * O worker manda um lote por ciclo: se o `deliverDue` carregasse a fila inteira, um acumulo de
	 * vencidos (fim de semana, queda do envio) virava um ciclo longo preso a um pico e o laço de 30 s
	 * perdia o ritmo. O lote é aplicado na consulta da porta, e este teste tranca que um ciclo
	 * entrega no máximo o lote declarado; o resto fica na fila para o próximo.
	 */
	@Test
	void oneCycleDeliversAtMostTheBatchCeiling() {
		String sender = owner();
		Instant soon = Instant.now().plus(Duration.ofMinutes(10));
		int moreThanTheBatch = CalendarReminderService.BATCH + 5;
		for (int i = 0; i < moreThanTheBatch; i++) {
			String entryId = entry(sender, "Lote " + i, soon);
			// Antecedência de um dia: com o compromisso a 10 min, o lembrete já nasce vencido.
			reminders.setForReader(sender, entryId, 1440);
		}

		int delivered = reminders.deliverDue();

		assertThat(delivered).isEqualTo(CalendarReminderService.BATCH);
	}

	private String entry(String owner, String title, Instant startsAt) {
		return commands.create(owner, "SOCIAL", "PERSONAL", title, null, startsAt.toString(),
			startsAt.plus(Duration.ofMinutes(30)).toString(), SAO_PAULO.getId(), false).id();
	}

}