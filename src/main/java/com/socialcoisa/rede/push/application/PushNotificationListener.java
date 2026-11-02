package com.socialcoisa.rede.push.application;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.socialcoisa.rede.notifications.application.NotificationCreatedEvent;
import com.socialcoisa.rede.notifications.application.NotificationPreferenceService;
import com.socialcoisa.rede.push.application.port.out.PushSender;

/**
 * Espelha as notificacoes do app como push no celular. Roda depois do commit: o envio faz rede e
 * nao pode segurar nem reverter a transacao que criou a notificacao.
 *
 * So filtra o push: a notificacao in-app ja foi criada (a caixa de entrada e completa). A categoria
 * desligada pela pessoa nao vira push; seguranca, moderacao e sancao sempre viram.
 */
@Component
class PushNotificationListener {

	private static final Logger LOG = LoggerFactory.getLogger(PushNotificationListener.class);

	private final PushSender pushSender;
	private final NotificationPreferenceService preferences;
	private final Executor executor;

	PushNotificationListener(PushSender pushSender, NotificationPreferenceService preferences,
			@Qualifier("pushExecutor") Executor executor) {
		this.pushSender = pushSender;
		this.preferences = preferences;
		this.executor = executor;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onNotificationCreated(NotificationCreatedEvent event) {
		try {
			executor.execute(() -> {
				if (!preferences.allowsPush(event.userId(), event.kind())) {
					return;
				}
				pushSender.sendToUser(event.userId(),
					new PushSender.PushMessage(event.title(), event.body(), event.href()));
			});
		}
		catch (RejectedExecutionException queueFull) {
			// Fila cheia: o push e descartado; a notificacao in-app continua na caixa de entrada.
			LOG.warn("fila de push cheia; notificacao nao enviada ao celular do usuario {}", event.userId());
		}
	}
}
