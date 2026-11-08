package com.socialcoisa.rede.calendar.application;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;

import com.socialcoisa.rede.calendar.application.port.out.CalendarEntryRepository;
import com.socialcoisa.rede.calendar.domain.CalendarEntry;
import com.socialcoisa.rede.calendar.domain.CalendarScope;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Leitura da agenda. Toda consulta é limitada ao dono: a janela filtra por {@code ownerId} na
 * própria consulta, e o detalhe de outra pessoa responde igual a um identificador inexistente.
 */
@Service
public class CalendarQueryService {

	private final CalendarEntryRepository entries;

	public CalendarQueryService(CalendarEntryRepository entries) {
		this.entries = entries;
	}

	/**
	 * Entradas do dono que tocam a janela. {@code scope} nulo devolve as duas agendas, que é a
	 * visão consolidada — uma composição na consulta, não uma terceira agenda.
	 */
	public List<CalendarEntry> inWindow(String ownerId, Instant from, Instant to, CalendarScope scope) {
		if (from == null || to == null) {
			throw new ApiException(400, "INVALID_WINDOW", "Informe from e to para consultar a agenda.");
		}
		if (!to.isAfter(from)) {
			throw new ApiException(400, "INVALID_WINDOW", "to precisa ser depois de from.");
		}
		return entries.findInWindow(ownerId, scope, from, to);
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
}
