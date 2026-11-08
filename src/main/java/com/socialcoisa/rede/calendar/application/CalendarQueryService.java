package com.socialcoisa.rede.calendar.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.socialcoisa.rede.calendar.application.port.out.CalendarEntryRepository;
import com.socialcoisa.rede.calendar.application.port.out.CalendarParticipantRepository;
import com.socialcoisa.rede.calendar.domain.CalendarEntry;
import com.socialcoisa.rede.calendar.domain.CalendarParticipant;
import com.socialcoisa.rede.calendar.domain.CalendarParticipantState;
import com.socialcoisa.rede.calendar.domain.CalendarScope;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Leitura da agenda. Toda consulta é limitada ao dono: a janela filtra por {@code ownerId} na
 * própria consulta, e o detalhe de outra pessoa responde igual a um identificador inexistente.
 */
@Service
public class CalendarQueryService {

	private final CalendarEntryRepository entries;
	private final CalendarParticipantRepository participants;

	public CalendarQueryService(CalendarEntryRepository entries, CalendarParticipantRepository participants) {
		this.entries = entries;
		this.participants = participants;
	}

	/**
	 * Entradas do dono que tocam a janela. {@code scope} nulo devolve as duas agendas, que é a
	 * visão consolidada — uma composição na consulta, não uma terceira agenda.
	 */
	public List<CalendarEntry> inWindow(String ownerId, Instant from, Instant to, CalendarScope scope) {
		validateWindow(from, to);
		return entries.findInWindow(ownerId, scope, from, to);
	}

	private static void validateWindow(Instant from, Instant to) {
		if (from == null || to == null) {
			throw new ApiException(400, "INVALID_WINDOW", "Informe from e to para consultar a agenda.");
		}
		if (!to.isAfter(from)) {
			throw new ApiException(400, "INVALID_WINDOW", "to precisa ser depois de from.");
		}
	}

	/**
	 * A agenda que a pessoa enxerga: o que ela organiza mais o que foi convidada a participar.
	 * Ordenada por início, e o compromisso em que ela é convidada vem com a resposta dela junto.
	 *
	 * <p>Um convite recusado continua aparecendo: a pessoa já respondeu, e o compromisso continua
	 * no histórico da agenda dela mesmo que ela não vá. Filtro de "só o que vou" é da tela.
	 */
	public List<CalendarAgendaItem> agenda(String userId, Instant from, Instant to, CalendarScope scope) {
		validateWindow(from, to);
		List<CalendarAgendaItem> items = new ArrayList<>();
		for (CalendarEntry entry : entries.findInWindow(userId, scope, from, to)) {
			items.add(CalendarAgendaItem.owned(entry));
		}
		List<CalendarEntry> invited = entries.findInvitedInWindow(userId, scope, from, to);
		if (!invited.isEmpty()) {
			Map<String, CalendarParticipantState> mine = new HashMap<>();
			for (CalendarParticipant participant : participants
				.findByUserAndEntryIds(userId, invited.stream().map(CalendarEntry::id).toList())) {
				mine.put(participant.entryId(), participant.state());
			}
			for (CalendarEntry entry : invited) {
				CalendarParticipantState myState = mine.get(entry.id());
				if (myState != null) {
					items.add(CalendarAgendaItem.invited(entry, myState));
				}
			}
		}
		items.sort(Comparator.comparing(item -> item.entry().startsAt()));
		return items;
	}

	/**
	 * Entrada de outro dono responde com o mesmo 404 de uma que não existe. Um 403 confirmaria que
	 * aquele identificador é real, o que já entrega informação sobre a agenda alheia.
	 */
	public CalendarEntry get(String ownerId, String entryId) {
		return entries.findById(entryId)
			.filter(entry -> entry.ownerId().equals(ownerId))
			.orElseThrow(() -> new ApiException(404, "CALENDAR_ENTRY_NOT_FOUND", "Compromisso nao encontrado."));
	}

	/**
	 * Detalhe para quem está lendo, com o lugar dela: o organizador vê o compromisso como dona; o
	 * convidado vê o mesmo compromisso com `owner=false` e a própria resposta.
	 *
	 * <p>Quem não organiza nem foi convidado responde o mesmo 404 de um id que não existe, porque um
	 * 403 confirmaria que aquele compromisso existe na agenda de alguém.
	 */
	public CalendarAgendaItem detail(String userId, String entryId) {
		CalendarEntry entry = entries.findById(entryId)
			.orElseThrow(() -> new ApiException(404, "CALENDAR_ENTRY_NOT_FOUND", "Compromisso nao encontrado."));
		if (entry.ownerId().equals(userId)) {
			return CalendarAgendaItem.owned(entry);
		}
		return participants.findByEntryAndUser(entry.id(), userId)
			.map(participant -> CalendarAgendaItem.invited(entry, participant.state()))
			.orElseThrow(() -> new ApiException(404, "CALENDAR_ENTRY_NOT_FOUND", "Compromisso nao encontrado."));
	}
}
