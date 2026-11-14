package com.socialcoisa.rede.messaging.adapters.in.web;

import java.io.IOException;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.socialcoisa.rede.messaging.application.ConversationChanged;
import com.socialcoisa.rede.notifications.application.NotificationCreatedEvent;
import com.socialcoisa.rede.shared.broadcast.InstanceBroadcaster;
import com.socialcoisa.rede.shared.broadcast.InstanceSignals;

import tools.jackson.databind.ObjectMapper;

/**
 * Open message streams, one per browser tab, keyed by user. The stream only carries "conversation X
 * changed"; the client refetches through the regular endpoints, so message content (E2E or not)
 * never travels here and every read keeps its usual authorization.
 *
 * <p>O registro é em memória, por processo. Com mais de uma instância do backend, a aba aberta na
 * instância B não recebia o aviso de uma escrita atendida na instância A — o evento era entregue só
 * no processo que o publicou. Por isso cada aviso local também vira um sinal em
 * {@code network_instance_signals} (V307), e cada instância entrega no próprio registro o que as
 * outras emitiram. O sinal carrega só ids, como o evento local: conteúdo continua vindo dos
 * endpoints autorizados.</p>
 *
 * <p>Publico porque o chat de campanha (Éroleplay) consulta quem tem stream aberto para a
 * presença da mesa — o hub é a infraestrutura única de stream da plataforma, e ninguém cria um
 * segundo registro de "quem está com a aba aberta".</p>
 */
@Component
public class MessageStreamHub {

	private static final Logger LOG = LoggerFactory.getLogger(MessageStreamHub.class);

	/** Long enough to be cheap, short enough that a dead tab is dropped; EventSource reconnects. */
	static final Duration TIMEOUT = Duration.ofMinutes(30);
	/** A few tabs and devices per person; beyond that the oldest stream is closed. */
	static final int MAX_STREAMS_PER_USER = 5;
	private static final long RECONNECT_MS = 5_000;

	private final Map<String, ConcurrentLinkedDeque<SseEmitter>> streams = new ConcurrentHashMap<>();
	private final InstanceBroadcaster broadcaster;
	private final ObjectMapper objectMapper;

	MessageStreamHub(InstanceBroadcaster broadcaster, ObjectMapper objectMapper) {
		this.broadcaster = broadcaster;
		this.objectMapper = objectMapper;
	}

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
		deliver(change.userIds(), "conversation", data);
	}

	/**
	 * "Chegou aviso para você": o sino e a lista de notificações recarregam na hora, sem esperar a
	 * pessoa trocar de página. Antes o stream só falava de conversa e chamada, então o aviso criado
	 * com a aba aberta só aparecia no próximo carregamento. O corpo leva só o id e o tipo: o texto
	 * e o resto continuam vindo do endpoint de notificações, que autoriza de novo.
	 */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	void onNotificationCreated(NotificationCreatedEvent event) {
		Map<String, String> data = Map.of("notificationId", event.notificationId(), "kind", event.kind());
		deliver(java.util.List.of(event.userId()), "notification", data);
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
		deliver(change.userIds(), "call", Map.copyOf(data));
	}

	/**
	 * Entrega local e, depois, sinal para as outras instâncias.
	 *
	 * <p>A ordem importa: o stream deste processo recebe primeiro, porque o efeito já commitou
	 * aqui. O sinal é emitido na hora (não no commit) porque este método só é chamado de
	 * {@code AFTER_COMMIT} — registrar outra sincronização aqui nunca rodaria.</p>
	 */
	private void deliver(Collection<String> userIds, String eventName, Map<String, String> data) {
		for (String userId : userIds) {
			deliverLocal(userId, eventName, data);
		}
		String payload = payload(eventName, data);
		broadcaster.publishNow(InstanceSignals.USER_EVENT, userIds, payload);
	}

	/** Entrega no registro deste processo. Usado pelo caminho local e pelo sinal de outra instância. */
	void deliverLocal(String userId, String eventName, Map<String, String> data) {
		ConcurrentLinkedDeque<SseEmitter> userStreams = streams.get(userId);
		if (userStreams == null) {
			return;
		}
		for (SseEmitter emitter : userStreams) {
			send(userId, emitter, SseEmitter.event().name(eventName).data(data));
		}
	}

	private String payload(String eventName, Map<String, String> data) {
		try {
			return objectMapper.writeValueAsString(new StreamSignalPayload(eventName, data));
		}
		catch (RuntimeException failure) {
			// Sem payload não há sinal; o stream local já foi entregue e o reconnect cobre o resto.
			LOG.warn("Nao foi possivel serializar o sinal de stream {} ({})", eventName,
				failure.getClass().getSimpleName());
			return null;
		}
	}

	/**
	 * "O chat da campanha X mudou": mesmo canal do stream de mensagens, evento próprio do Éroleplay.
	 * O corpo só diz a campanha — o conteúdo real vem dos endpoints do chat, que autorizam de novo
	 * por leitor (participante lê o canal; aparte privado, só as pontas e o mestre).
	 */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	void onCampaignChatChanged(com.socialcoisa.rede.eroleplay.application.CampaignChatChanged change) {
		Map<String, String> data = Map.of("campaignId", change.campaignId());
		for (String userId : change.userIds()) {
			ConcurrentLinkedDeque<SseEmitter> userStreams = streams.get(userId);
			if (userStreams == null) {
				continue;
			}
			for (SseEmitter emitter : userStreams) {
				send(userId, emitter, SseEmitter.event().name("campaign").data(data));
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

	/** O usuário tem pelo menos um stream aberto agora (a presença do chat pergunta por pessoa). */
	public boolean hasOpenStream(String userId) {
		ConcurrentLinkedDeque<SseEmitter> userStreams = streams.get(userId);
		return userStreams != null && !userStreams.isEmpty();
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
		catch (RuntimeException failure) {
			// O aviso sai DEPOIS do commit: deixar a excecao subir transformaria em 500 uma mensagem
			// que ja esta gravada, e o cliente tentaria reenviar o que nao falhou. O stream e
			// best-effort por definicao — a tela busca o estado real nos endpoints autorizados.
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
