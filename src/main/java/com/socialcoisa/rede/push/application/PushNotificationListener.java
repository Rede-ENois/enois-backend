package com.socialcoisa.rede.push.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.socialcoisa.rede.notifications.application.NotificationCreatedEvent;
import com.socialcoisa.rede.presence.application.PresenceService;
import com.socialcoisa.rede.push.application.port.out.PushSender;

/**
 * Espelha as notificacoes do app como push no celular. Roda depois do commit: o envio faz rede e
 * nao pode segurar nem reverter a transacao que criou a notificacao.
 */
@Component
class PushNotificationListener {

	private final PushSender pushSender;
	private final PresenceService presenceService;

	PushNotificationListener(PushSender pushSender, PresenceService presenceService) {
		this.pushSender = pushSender;
		this.presenceService = presenceService;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onNotificationCreated(NotificationCreatedEvent event) {
		if (!presenceService.allowPush(event.userId())) return;
		pushSender.sendToUser(event.userId(), new PushSender.PushMessage(event.title(), event.body(), event.href()));
	}
}
