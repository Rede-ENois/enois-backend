package com.socialcoisa.rede.calendar.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.socialcoisa.rede.calendar.application.port.out.CalendarEntryRepository;
import com.socialcoisa.rede.calendar.domain.CalendarEntry;
import com.socialcoisa.rede.calendar.domain.CalendarEntrySource;
import com.socialcoisa.rede.calendar.domain.CalendarKind;
import com.socialcoisa.rede.calendar.domain.CalendarScope;

/**
 * Entrada gravada por outro módulo (mentoria, evento, entrevista), com a origem preenchida.
 *
 * <p>É o caminho da D12: a rota HTTP de escrita nunca aceita `sourceType`/`sourceId` do cliente, e
 * quem sabe a origem é o módulo dono, que já validou a regra própria antes de chamar aqui.
 *
 * <p>A origem é a chave de idempotência (D3). Gravar de novo a mesma origem reagenda a entrada que
 * já existe em vez de criar outra: é o que faz um reagendamento de mentoria mover o compromisso em
 * vez de duplicá-lo na agenda, e é o que segura o duplo clique. O banco também garante isso com o
 * índice único por dono e origem.
 */
@Service
public class CalendarSourceService {

	private static final String DEFAULT_ZONE = "America/Sao_Paulo";

	private final CalendarEntryRepository entries;
	private final CalendarReminderService reminders;
	private final Clock clock;

	public CalendarSourceService(CalendarEntryRepository entries, CalendarReminderService reminders, Clock clock) {
		this.entries = entries;
		this.reminders = reminders;
		this.clock = clock;
	}

	/**
	 * Cria a entrada da origem, ou reagenda a que já existe, e devolve qual das duas coisas fez.
	 *
	 * <p>Devolver o efeito em vez de só a entrada é o que permite ao módulo dono saber se precisa
	 * avisar alguém ("seu compromisso mudou de horário") sem consultar de novo.
	 */
	public UpsertResult upsert(UpsertInput input) {
		CalendarEntrySource source = new CalendarEntrySource(input.sourceType(), input.sourceId());
		ZoneId zone = input.timeZoneId() == null || input.timeZoneId().isBlank()
			? ZoneId.of(DEFAULT_ZONE)
			: CalendarEntry.zoneOf(input.timeZoneId());
		Instant now = clock.instant();

		Optional<CalendarEntry> existing = entries.findBySource(input.ownerId(), source);
		if (existing.isPresent()) {
			CalendarEntry entry = existing.get();
			boolean moved = false;
			// Entrada cancelada não volta: ela existe para publicar o cancelamento com o mesmo UID.
			// Reabrir aqui apagaria o cancelamento que a pessoa já recebeu.
			if (entry.active()) {
				Instant before = entry.startsAt();
				Instant beforeEnd = entry.endsAt();
				entry.reschedule(input.startsAt(), input.endsAt(), zone, now);
				entry.rename(input.title(), now);
				entry.describe(input.description(), now);
				moved = !before.equals(entry.startsAt()) || !beforeEnd.equals(entry.endsAt());
				entries.save(entry);
				if (moved) {
					// Mesma regra da edição direta: aviso do horário antigo é pior que aviso nenhum.
					reminders.clearPendingForEntry(entry.id());
				}
			}
			return new UpsertResult(entry, false, moved);
		}

		CalendarEntry created = CalendarEntry.create(nextId(), input.ownerId(), input.scope(), input.kind(),
			source, input.title(), input.description(), input.startsAt(), input.endsAt(), zone, input.allDay(),
			now);
		entries.save(created);
		return new UpsertResult(created, true, false);
	}

	/**
	 * Remove a entrada da origem. Sem entrada para a origem não é erro, e repetir também não.
	 *
	 * <p>É para quando o fato que gerou o compromisso deixou de valer — a pessoa saiu do evento —,
	 * não para cancelar: cancelar é estado, e a entrada cancelada continua publicando o cancelamento
	 * (D10/D26). Aqui o compromisso não existe mais, então não há o que publicar.
	 */
	public void delete(String ownerId, String sourceType, String sourceId) {
		entries.deleteBySource(ownerId, new CalendarEntrySource(sourceType, sourceId));
	}

	/**
	 * Cancela a entrada da origem. Sem entrada para a origem não é erro: o módulo dono pode estar
	 * cancelando uma sessão que nunca chegou a ser gravada na agenda.
	 */
	public Optional<CalendarEntry> cancel(String ownerId, String sourceType, String sourceId) {
		CalendarEntrySource source = new CalendarEntrySource(sourceType, sourceId);
		return entries.findBySource(ownerId, source).map(entry -> {
			if (entry.active()) {
				entry.cancel(clock.instant());
				entries.save(entry);
				reminders.clearPendingForEntry(entry.id());
			}
			return entry;
		});
	}

	private static String nextId() {
		return "cal_" + UUID.randomUUID().toString().replace("-", "");
	}

	/** O que o módulo dono manda. `scope` e `kind` vêm de quem sabe o tipo; aqui não há padrão. */
	public record UpsertInput(String ownerId, String sourceType, String sourceId, CalendarScope scope,
			CalendarKind kind, String title, String description, Instant startsAt, Instant endsAt,
			String timeZoneId, boolean allDay) {

		/** Origem de mentoria e evento nunca é de dia inteiro; o import de `.ics` pode ser. */
		public UpsertInput(String ownerId, String sourceType, String sourceId, CalendarScope scope,
				CalendarKind kind, String title, String description, Instant startsAt, Instant endsAt,
				String timeZoneId) {
			this(ownerId, sourceType, sourceId, scope, kind, title, description, startsAt, endsAt, timeZoneId,
				false);
		}
	}

	/**
	 * `created` diz se a linha nasceu agora; `moved` diz se um compromisso que já existia mudou de
	 * horário. Os dois falsos significam "já estava assim", que é o resultado do retry.
	 */
	public record UpsertResult(CalendarEntry entry, boolean created, boolean moved) {
	}
}
