package com.socialcoisa.rede.events.application;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.socialcoisa.rede.calls.application.EventLookup;
import com.socialcoisa.rede.events.application.port.out.EventRepository;
import com.socialcoisa.rede.events.domain.Event;

/**
 * O encontro, para as chamadas. A existência é conferida no banco a cada pedido: um id inventado
 * não abre sala, e um evento apagado não deixa sala órfã sendo aberta de novo.
 */
@Component
public class EventCallLookup implements EventLookup {

	private final EventRepository repository;

	public EventCallLookup(EventRepository repository) {
		this.repository = repository;
	}

	@Override
	public void requireExists(String eventId) {
		repository.findById(eventId)
			.orElseThrow(() -> new com.socialcoisa.rede.shared.web.ApiException(404, "EVENT_NOT_FOUND",
				"Evento nao encontrado."));
	}

	/**
	 * Quem criou mais quem confirmou presença, sem repetir. A lista é só para o aviso "está ao vivo":
	 * ela não limita quem pode entrar, e por isso não precisa ser exata — precisa ser curta.
	 */
	@Override
	public List<String> interestedIn(String eventId) {
		Event event = repository.findById(eventId)
			.orElseThrow(() -> new com.socialcoisa.rede.shared.web.ApiException(404, "EVENT_NOT_FOUND",
				"Evento nao encontrado."));
		Set<String> people = new LinkedHashSet<>();
		people.add(event.createdBy());
		people.addAll(repository.findAttendeeIds(eventId));
		return new ArrayList<>(people);
	}
}
