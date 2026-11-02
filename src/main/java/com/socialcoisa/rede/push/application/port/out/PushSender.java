package com.socialcoisa.rede.push.application.port.out;

/**
 * Envio de push para os dispositivos de um usuario. A implementacao decide o protocolo (Web Push)
 * e deve ser tolerante a falha: notificacao que nao entrega nao pode quebrar o fluxo que a criou.
 */
public interface PushSender {

	void sendToUser(String userId, PushMessage message);

	/** Push configurado nesta instalacao (chaves VAPID presentes). */
	boolean isEnabled();

	/** Chave publica VAPID que o navegador usa para se inscrever (nula se o push estiver desligado). */
	String publicKey();

	/** {@code notificationId} lets the app mark the notification read when the push is opened. */
	record PushMessage(String notificationId, String title, String body, String url) {
	}
}