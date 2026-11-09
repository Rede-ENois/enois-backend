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
	 * A sala so existe se quem criou o evento a ligou: o card sem a caixinha nao ganha lugar de
	 * conversa. O 409 diz o motivo — o 404 seria mentira, porque o evento esta ali.
	 */
	@Override
	public void requireTamoJunto(String eventId) {
		Event event = repository.findById(eventId)
			.orElseThrow(() -> new com.socialcoisa.rede.shared.web.ApiException(404, "EVENT_NOT_FOUND",
				"Evento nao encontrado."));
		if (!event.tamoJunto()) {
			throw new com.socialcoisa.rede.shared.web.ApiException(409, "EVENT_TAMO_JUNTO_DISABLED",
				"Este evento nao tem a sala Tamo junto.");
		}
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
