package com.socialcoisa.rede.calendar.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.calendar.application.port.out.CalendarEntryRepository;
import com.socialcoisa.rede.calendar.application.port.out.CalendarReminderRepository;
import com.socialcoisa.rede.calendar.domain.CalendarEntry;
import com.socialcoisa.rede.calendar.domain.CalendarParticipantState;
import com.socialcoisa.rede.calendar.domain.CalendarReminder;
import com.socialcoisa.rede.calendar.application.port.out.CalendarParticipantRepository;
import com.socialcoisa.rede.notifications.application.NotificationService;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Lembretes de compromisso (decisão D4).
 *
 * <p>Ao contrário do envio, a <b>criação</b> é dirigida pela tela: a pessoa escolhe quando quer ser
 * avisada, e o lembrete nasce no mesmo instante em que o compromisso entra na agenda dela. Ao
 * contrário do lembrete, o <b>envio</b> é do worker: ele só olha o relógio e chama por aqui.
 *
 * <p>Quem é avisado: o organizador e quem aceitou o convite. Quem foi convidado e não respondeu
 * ainda não está na agenda — avisar seria lembrar de algo que a pessoa não disse que vai. Quem
 * recusou, idem. A regra é aplicada aqui, não na consulta, porque um convite aceito depois do
 * lembrete criado precisa passar a valer.
 */
@Service
public class CalendarReminderService {

	private static final Logger LOG = LoggerFactory.getLogger(CalendarReminderService.class);

	/** Quantos vencidos o worker envia por ciclo: um lote pequeno evita prender o laço num pico. */
	static final int BATCH = 20;

	/**
	 * Autor das notificações automáticas. `notifications.actor_id` é `not null`, e um lembrete não
	 * tem pessoa por trás: usar o id do compromisso como ator misturaria entidade com pessoa.
	 */
	static final String SYSTEM_ACTOR = "system";

	private final CalendarReminderRepository reminders;
	private final CalendarEntryRepository entries;
	private final CalendarParticipantRepository participants;
	private final NotificationService notifications;
	private final Clock clock;

	CalendarReminderService(CalendarReminderRepository reminders, CalendarEntryRepository entries,
			CalendarParticipantRepository participants, NotificationService notifications, Clock clock) {
		this.reminders = reminders;
		this.entries = entries;
		this.participants = participants;
		this.notifications = notifications;
		this.clock = clock;
	}

	/**
	 * Põe (ou troca) o lembrete de quem está lendo. Criar de novo com a mesma antecedência é o que
	 * um duplo clique faz: devolve a linha que existe em vez de violar a chave única.
	 */
	@Transactional
	public CalendarReminder setForReader(String userId, String entryId, int minutesBefore) {
		CalendarEntry entry = entries.findById(entryId)
			.orElseThrow(() -> new ApiException(404, "CALENDAR_ENTRY_NOT_FOUND",
				"Compromisso não encontrado."));
		boolean involved = entry.ownerId().equals(userId) || participates(userId, entry);
		if (!involved) {
			// Mesma resposta de inexistente: não confirma a existência da agenda de outra pessoa (D10).
			throw new ApiException(404, "CALENDAR_ENTRY_NOT_FOUND",
				"Compromisso não encontrado.");
		}
		if (!entry.active()) {
			throw new ApiException(409, "CALENDAR_ENTRY_CANCELLED",
				"Compromisso cancelado não aceita lembrete.");
		}
		Instant now = clock.instant();
		Optional<CalendarReminder> existing = reminders.findByEntryAndUserAndMinutes(entryId, userId, minutesBefore);
		if (existing.isPresent()) {
			return existing.get();
		}
		CalendarReminder reminder = CalendarReminder.create("rem_" + UUID.randomUUID().toString().replace("-", ""),
			entryId, userId, minutesBefore, entry.startsAt(), now);
		reminders.save(reminder);
		return reminder;
	}

	/**
	 * Tira o lembrete de quem está lendo para uma antecedência. Devolve se havia algo: tirar duas
	 * vezes não é erro (o botão pode repetir depois de um timeout).
	 */
	@Transactional
	public boolean clearForReader(String userId, String entryId, int minutesBefore) {
		return reminders.findByEntryAndUserAndMinutes(entryId, userId, minutesBefore)
			.map(reminder -> {
				clear(reminder, clock.instant());
				return true;
			})
			.orElse(false);
	}

	/**
	 * Os lembretes ativos de quem lê num compromisso: o que ainda vai tocar. Os resolvidos (enviado,
	 * encerrado pelo cancelamento) saem da lista — a tela escolhe o lembrete, não vê o histórico.
	 */
	public List<CalendarReminder> ofReader(String userId, String entryId) {
		return reminders.findByEntry(entryId).stream()
			.filter(r -> r.userId().equals(userId) && r.sentAt() == null)
			.toList();
	}

	/**
	 * Encerra os lembretes pendentes de um compromisso sem aviso: usado quando a entrada é cancelada
	 * ou reagendada, porque um aviso para o horário antigo é pior que aviso nenhum.
	 */
	@Transactional
	public void clearPendingForEntry(String entryId) {
		for (CalendarReminder reminder : reminders.findPendingByEntries(List.of(entryId))) {
			clear(reminder, clock.instant());
		}
	}

	/**
	 * O ciclo do worker. Cada lembrete é enviado e marcado sozinho: uma falha não impede o próximo
	 * de sair, e o motivo fica gravado na linha para quem for investigar.
	 */
	@Transactional
	public int deliverDue() {
		Instant now = clock.instant();
		List<CalendarReminder> due = reminders.findDue(now, CalendarReminder.MAX_ATTEMPTS, BATCH);
		int sent = 0;
		for (CalendarReminder reminder : due) {
			try {
				Optional<CalendarEntry> entry = entries.findById(reminder.entryId());
				if (entry.isEmpty() || !entry.get().active()) {
					// Compromisso sumiu ou foi cancelado: o lembrete não tem mais o que avisar.
					clear(reminder, now);
					continue;
				}
				if (!shouldNotify(reminder.userId(), entry.get())) {
					clear(reminder, now);
					continue;
				}
				notify(reminder, entry.get());
				reminder.sent(now);
				reminders.save(reminder);
				sent++;
			}
			catch (RuntimeException error) {
				reminder.failed(reasonOf(error), now);
				reminders.save(reminder);
				LOG.warn("Lembrete {} falhou (tentativa {}): {}", reminder.id(), reminder.attempts(),
					reminder.lastError());
			}
		}
		return sent;
	}

	/**
	 * O compromisso que chega: o organizador sempre; quem foi convidado, só depois de aceitar (ou
	 * talvez). `TENTATIVE` conta porque a pessoa disse que talvez vai, e o aviso é justamente para
	 * decidir.
	 */
	private boolean shouldNotify(String userId, CalendarEntry entry) {
		if (entry.ownerId().equals(userId)) {
			return true;
		}
		return participants.findByEntry(entry.id()).stream()
			.filter(p -> p.userId().equals(userId))
			.anyMatch(p -> p.state() == CalendarParticipantState.ACCEPTED
				|| p.state() == CalendarParticipantState.TENTATIVE);
	}

	private boolean participates(String userId, CalendarEntry entry) {
		return participants.findByEntry(entry.id()).stream().anyMatch(p -> p.userId().equals(userId));
	}

	private void notify(CalendarReminder reminder, CalendarEntry entry) {
		String when = textOfMinutes(reminder.minutesBefore());
		// O aviso abre o compromisso que o originou: a pessoa que tocou no lembrete quer ver quem
		// convidou, onde é e o que responder. Levar só à agenda deixava a tarefa pela metade.
		// O lembrete é do sistema: ninguém "fez" isso para a pessoa. `actor_id` é obrigatório na
		// tabela de notificações (a coluna não aceita nulo), então o autor é o próprio relógio.
		notifications.create(reminder.userId(), "CALENDAR_REMINDER", SYSTEM_ACTOR, "Compromisso chegando",
			"«" + entry.title() + "» começa " + when + ".", "/agenda/" + entry.id(), "Ver compromisso");
	}

	private static String textOfMinutes(int minutes) {
		if (minutes >= 1440) {
			int days = minutes / 1440;
			return days == 1 ? "em 1 dia" : "em " + days + " dias";
		}
		return "em " + minutes + " min";
	}

	/**
	 * Encerra a linha sem enviar. `sentAt` marca "resolvido", e o motivo diz por quê — apagar a
	 * linha esconderia a diferença entre "avisado", "compromisso cancelado" e "pessoa recusou".
	 */
	private void clear(CalendarReminder reminder, Instant now) {
		reminder.sent(now);
		reminders.save(reminder);
	}

	private static String reasonOf(RuntimeException error) {
		return error.getMessage() == null || error.getMessage().isBlank() ? error.getClass().getSimpleName()
			: error.getMessage();
	}

}