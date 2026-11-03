package com.socialcoisa.rede.notifications.application;

/**
 * Publicado quando uma notificacao e criada. Quem quiser reagir (ex.: enviar push) escuta este
 * evento fora da transacao da criacao. {@code notificationId} e a linha criada (num grupo, a do
 * grupo), para o push poder marcar a notificacao como lida ao ser aberto. {@code tag} (opcional)
 * agrupa os pushes no aparelho: um push novo com a mesma tag substitui o anterior na bandeja.
 */
public record NotificationCreatedEvent(String notificationId, String userId, String kind, String title, String body,
		String href, String tag) {

	public NotificationCreatedEvent(String notificationId, String userId, String kind, String title, String body,
			String href) {
		this(notificationId, userId, kind, title, body, href, null);
	}
}
