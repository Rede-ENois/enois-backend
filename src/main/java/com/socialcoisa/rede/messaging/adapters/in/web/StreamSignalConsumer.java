package com.socialcoisa.rede.messaging.adapters.in.web;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.socialcoisa.rede.shared.broadcast.InstanceSignal;
import com.socialcoisa.rede.shared.broadcast.InstanceSignals;
import com.socialcoisa.rede.shared.broadcast.LocalSignalConsumer;

import tools.jackson.databind.ObjectMapper;

/**
 * Entrega no {@link MessageStreamHub} desta instância o aviso de stream que outra instância emitiu.
 *
 * <p>Fica neste pacote porque o hub é package-private: o registro em memória é detalhe do adapter
 * web, e o contrato entre instâncias ({@link LocalSignalConsumer}) não precisa expor isso. O sinal
 * traz o mesmo par (evento, dados) que o caminho local monta, então a entrega é idêntica — a aba
 * aberta aqui recebe o aviso como se a escrita tivesse sido atendida aqui.</p>
 */
@Component
class StreamSignalConsumer implements LocalSignalConsumer {

	private static final Logger LOG = LoggerFactory.getLogger(StreamSignalConsumer.class);

	private final MessageStreamHub hub;
	private final ObjectMapper objectMapper;

	StreamSignalConsumer(MessageStreamHub hub, ObjectMapper objectMapper) {
		this.hub = hub;
		this.objectMapper = objectMapper;
	}

	@Override
	public String signalType() {
		return InstanceSignals.USER_EVENT;
	}

	@Override
	public void deliver(InstanceSignal signal) {
		StreamSignalPayload payload = objectMapper.readValue(signal.payload(), StreamSignalPayload.class);
		if (payload == null || payload.event() == null || payload.event().isBlank()) {
			return;
		}
		Map<String, String> data = payload.data() == null ? Map.of() : payload.data();
		hub.deliverLocal(signal.target(), payload.event(), data);
		LOG.debug("Aviso de stream {} entregue nesta instancia para {}", payload.event(), signal.target());
	}
}
