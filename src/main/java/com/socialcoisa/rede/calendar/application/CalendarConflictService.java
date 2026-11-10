package com.socialcoisa.rede.calendar.application;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;

import com.socialcoisa.rede.calendar.domain.CalendarParticipantState;
import com.socialcoisa.rede.calendar.domain.CalendarScope;

/**
 * Conflito de horário de uma pessoa: o que já ocupa o intervalo que ela está tentando usar.
 *
 * <p>A definição é a mesma da agenda que ela enxerga (D13): conta o que ela organiza e o que ela
 * aceitou ou marcou como "talvez" em convite alheio. Convite pendente ou recusado não ocupa horário
 * — ninguém deixa de marcar uma consulta porque tem um convite parado na caixa.
 *
 * <p>Sobrepor de propósito é permitido: a agenda social não bloqueia a escrita, e o app mostra o
 * aviso para a pessoa decidir. Um 409 aqui impediria justamente o caso legítimo de duas atividades
 * no mesmo horário.
 *
 * <p>Reaproveita {@link CalendarQueryService#agenda} em vez de uma consulta própria: a janela
 * validada, o estado do convite e a ordenação por início são exatamente os mesmos da tela.
 */
@Service
public class CalendarConflictService {

	private final CalendarQueryService agenda;

	public CalendarConflictService(CalendarQueryService agenda) {
		this.agenda = agenda;
	}

	/**
	 * Compromissos ativos que ocupam o intervalo, na ordem de início.
	 *
	 * @param exceptEntryId entrada que não deve contar como conflito consigo mesma, na edição; nulo
	 *                      na criação
	 */
	public List<CalendarAgendaItem> conflicts(String userId, Instant from, Instant to, CalendarScope scope,
			String exceptEntryId) {
		return agenda.agenda(userId, from, to, scope).stream()
			.filter(CalendarConflictService::occupiesTime)
			.filter(item -> item.entry().active())
			.filter(item -> exceptEntryId == null || !exceptEntryId.equals(item.entry().id()))
			.toList();
	}

	public boolean hasConflict(String userId, Instant from, Instant to, CalendarScope scope, String exceptEntryId) {
		return !conflicts(userId, from, to, scope, exceptEntryId).isEmpty();
	}

	/** O convite só ocupa horário depois de respondido como ACCEPTED ou TENTATIVE. */
	private static boolean occupiesTime(CalendarAgendaItem item) {
		if (item.owner()) {
			return true;
		}
		return item.participation() == CalendarParticipantState.ACCEPTED
			|| item.participation() == CalendarParticipantState.TENTATIVE;
	}

}