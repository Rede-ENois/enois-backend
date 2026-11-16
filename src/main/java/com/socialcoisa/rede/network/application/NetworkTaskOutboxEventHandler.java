package com.socialcoisa.rede.network.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.socialcoisa.rede.network.domain.DataClass;
import com.socialcoisa.rede.network.domain.TaskType;
import com.socialcoisa.rede.shared.outbox.OutboxEventHandler;

/**
 * O scheduler como handler do outbox (ADR-004).
 *
 * <p>Um evento {@code NetworkTaskRequested} cria a tarefa e dispara uma rodada de designação. O
 * handler herda do outbox o claim entre instâncias, o backoff, a DLQ e a métrica — não existe fila
 * paralela.</p>
 *
 * <p>O que ele <b>não</b> faz é esperar o nó: o handler cria a tarefa e devolve. A execução no nó é
 * assíncrona por natureza, e o estado dela vive na linha da tarefa, não no evento. Se o handler
 * esperasse o nó, um nó lento seguraria o lote de 50 do outbox.</p>
 */
@Component
class NetworkTaskOutboxEventHandler implements OutboxEventHandler {

	private static final Logger LOG = LoggerFactory.getLogger(NetworkTaskOutboxEventHandler.class);

	/** Evento que pede uma tarefa distribuída. */
	static final String EVENT_TASK_REQUESTED = "NetworkTaskRequested";

	private final NetworkTaskService tasks;

	NetworkTaskOutboxEventHandler(NetworkTaskService tasks) {
		this.tasks = tasks;
	}

	@Override
	public void handle(String eventType, String aggregateId, String payload) {
		switch (eventType) {
			case EVENT_TASK_REQUESTED -> handleRequested(aggregateId, payload);
			default -> LOG.debug("Evento {} nao gera tarefa distribuida", eventType);
		}
	}

	private void handleRequested(String aggregateId, String payload) {
		TaskType type = parseType(extract(payload, "type"));
		if (type == null) {
			// Evento sem tipo é evento malformado: falhar aqui faria o outbox retentar para sempre
			// um payload que nunca vai ficar válido. Registra e segue.
			LOG.warn("Evento {} sem tipo de tarefa valido; ignorado", aggregateId);
			return;
		}
		DataClass dataClass = parseDataClass(extract(payload, "dataClass"));
		// A chave de idempotência é o id do agregado: o mesmo evento reprocessado não cria tarefa
		// nova, e a tarefa criada é a mesma entre tentativas.
		tasks.enqueue(new NetworkTaskService.EnqueueCommand(type, "evt:" + aggregateId, payload, dataClass, null));
		tasks.dispatchPending();
	}

	private static TaskType parseType(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return TaskType.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
		}
		catch (IllegalArgumentException unknown) {
			return null;
		}
	}

	private static DataClass parseDataClass(String raw) {
		if (raw == null || raw.isBlank()) {
			return DataClass.PUBLIC;
		}
		try {
			return DataClass.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
		}
		catch (IllegalArgumentException unknown) {
			// Classe desconhecida cai no mais restritivo: erra para o lado de não vazar dado.
			return DataClass.PERSONAL;
		}
	}

	private static String extract(String json, String key) {
		if (json == null) {
			return null;
		}
		String search = "\"" + key + "\":\"";
		int start = json.indexOf(search);
		if (start < 0) {
			return null;
		}
		start += search.length();
		int end = json.indexOf("\"", start);
		return end < 0 ? null : json.substring(start, end);
	}
}
