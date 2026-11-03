package com.socialcoisa.rede.notifications.application;

/**
 * Publicado quando uma notificacao e criada. Quem quiser reagir (ex.: enviar push) escuta este
 * evento fora da transacao da criacao. {@code notificationId} e a linha criada (num grupo, a do
 * grupo), para o push poder marcar a notificacao como lida ao ser aberto.
 */
public record NotificationCreatedEvent(String notificationId, String userId, String kind, String title, String body, String href) {
}
