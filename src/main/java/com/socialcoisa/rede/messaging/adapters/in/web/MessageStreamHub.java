package com.socialcoisa.rede.messaging.adapters.in.web;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.socialcoisa.rede.messaging.application.ConversationChanged;

/**
 * Open message streams, one per browser tab, keyed by user. The stream only carries "conversation X
 * changed"; the client refetches through the regular endpoints, so message content (E2E or not)
 * never travels here and every read keeps its usual authorization.
 *
 * <p>ponytail: in-memory registry, correct while production runs a single backend instance. A
 * second instance needs a broadcast between instances (Redis pub/sub or similar) before scaling out.
 */
@Component
class MessageStreamHub {

	/** Long enough to be cheap, short enough that a dead tab is dropped; EventSource reconnects. */
	static final Duration TIMEOUT = Duration.ofMinutes(30);
	/** A few tabs and devices per person; beyond that the oldest stream is closed. */
	static final int MAX_STREAMS_PER_USER = 5;
	private static final long RECONNECT_MS = 5_000;

	private final Map<String, ConcurrentLinkedDeque<SseEmitter>> streams = new ConcurrentHashMap<>();

	SseEmitter open(String userId) {
		SseEmitter emitter = new SseEmitter(TIMEOUT.toMillis());
		ConcurrentLinkedDeque<SseEmitter> userStreams = streams.computeIfAbsent(userId,
			id -> new ConcurrentLinkedDeque<>());
		userStreams.addLast(emitter);
		while (userStreams.size() > MAX_STREAMS_PER_USER) {
			SseEmitter oldest = userStreams.pollFirst();
			if (oldest != null) {
				oldest.complete();
			}
		}
		Runnable remove = () -> remove(userId, emitter);
		emitter.onCompletion(remove);
		emitter.onTimeout(remove);
		emitter.onError(error -> remove.run());
		// First bytes go out right away: proxies that wait for a body before forwarding the response
		// would otherwise hold the stream until the first real event.
		send(userId, emitter, SseEmitter.event().reconnectTime(RECONNECT_MS).comment("open"));
		return emitter;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	void onConversationChanged(ConversationChanged change) {
		Map<String, String> data = Map.of("conversationId", change.conversationId());
		for (String userId : change.userIds()) {
			ConcurrentLinkedDeque<SseEmitter> userStreams = streams.get(userId);
			if (userStreams == null) {
				continue;
			}
			for (SseEmitter emitter : userStreams) {
				send(userId, emitter, SseEmitter.event().name("conversation").data(data));
			}
		}
	}

	/**
	 * "A chamada X mudou": mesmo canal do stream de mensagens, evento próprio. O corpo só diz ids e
	 * estado — quem recebe busca o estado real pelo endpoint de chamadas, que autoriza de novo.
	 */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	void onCallChanged(com.socialcoisa.rede.calls.application.CallChanged change) {
		Map<String, String> data = new java.util.HashMap<>();
		data.put("callId", change.callId());
		data.put("state", change.state());
		if (change.conversationId() != null) {
			data.put("conversationId", change.conversationId());
		}
		if (change.groupId() != null) {
			data.put("groupId", change.groupId());
		}
		if (change.eventId() != null) {
			data.put("eventId", change.eventId());
		}
		Map<String, String> immutable = Map.copyOf(data);
		for (String userId : change.userIds()) {
			ConcurrentLinkedDeque<SseEmitter> userStreams = streams.get(userId);
			if (userStreams == null) {
				continue;
			}
			for (SseEmitter emitter : userStreams) {
				send(userId, emitter, SseEmitter.event().name("call").data(immutable));
			}
		}
	}

	/** Keeps idle streams alive through proxies that drop silent connections (often at 60s). */
	@Scheduled(fixedDelay = 25_000, initialDelay = 25_000)
	void heartbeat() {
		streams.forEach((userId, userStreams) -> {
			for (SseEmitter emitter : userStreams) {
				send(userId, emitter, SseEmitter.event().comment("ping"));
			}
		});
	}

	int openStreams(String userId) {
		ConcurrentLinkedDeque<SseEmitter> userStreams = streams.get(userId);
		return userStreams == null ? 0 : userStreams.size();
	}

	private void send(String userId, SseEmitter emitter, SseEmitter.SseEventBuilder event) {
		try {
			emitter.send(event);
		}
		catch (IOException | IllegalStateException closed) {
			// The tab went away; the container completes the emitter, here it only leaves the registry.
			remove(userId, emitter);
		}
	}

	private void remove(String userId, SseEmitter emitter) {
		streams.computeIfPresent(userId, (id, userStreams) -> {
			userStreams.remove(emitter);
			return userStreams.isEmpty() ? null : userStreams;
		});
	}
}
