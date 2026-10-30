package com.socialcoisa.rede.push.application;

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

	private final PushSender pushSender;
	private final NotificationPreferenceService preferences;

	PushNotificationListener(PushSender pushSender, NotificationPreferenceService preferences) {
		this.pushSender = pushSender;
		this.preferences = preferences;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onNotificationCreated(NotificationCreatedEvent event) {
		if (!preferences.allowsPush(event.userId(), event.kind())) {
			return;
		}
		pushSender.sendToUser(event.userId(), new PushSender.PushMessage(event.title(), event.body(), event.href()));
	}
}
