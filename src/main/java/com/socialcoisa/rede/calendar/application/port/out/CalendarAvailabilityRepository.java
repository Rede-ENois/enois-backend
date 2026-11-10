package com.socialcoisa.rede.calendar.application.port.out;

import java.util.List;

import com.socialcoisa.rede.calendar.domain.CalendarAvailabilityWindow;

/** Porta de persistência da disponibilidade semanal. A interface não conhece JPA. */
public interface CalendarAvailabilityRepository {

	/** As janelas de uma pessoa, na ordem em que a tela mostra: dia e depois começo. */
	List<CalendarAvailabilityWindow> findByUser(String userId);

	/**
	 * Substitui a semana inteira de uma pessoa. A escrita é por substituição porque a regra é o
	 * conjunto: apagar uma janela é não mandá-la de novo, e não há id estável para atualizar.
	 */
	void replaceFor(String userId, List<CalendarAvailabilityWindow> windows);

}