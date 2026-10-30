package com.socialcoisa.rede.push.adapters.out.webpush;

import java.nio.charset.StandardCharsets;
import java.security.Security;
import java.util.List;
import java.util.Map;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.push.application.port.out.PushSender;
import com.socialcoisa.rede.push.application.port.out.PushSubscriptionRepository;
import com.socialcoisa.rede.push.domain.PushSubscription;

import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;

import tools.jackson.databind.ObjectMapper;

/**
 * Envio de Web Push (VAPID + payload criptografado) usando a lib nl.martijndwars:web-push.
 *
 * Se as chaves VAPID nao estiverem configuradas, o envio fica desligado (as notificacoes seguem
 * funcionando dentro do app) — nada quebra por falta de configuracao.
 */
@Component
class WebPushSender implements PushSender {

	private static final Logger LOG = LoggerFactory.getLogger(WebPushSender.class);
	private static final int TTL_SECONDS = 60 * 60 * 24;

	private final PushSubscriptionRepository subscriptions;
	private final ObjectMapper objectMapper;
	private final PushService pushService;
	private final String publicKey;

	WebPushSender(PushSubscriptionRepository subscriptions, ObjectMapper objectMapper,
			@Value("${rede.push.vapid.public-key:}") String publicKey,
			@Value("${rede.push.vapid.private-key:}") String privateKey,
			@Value("${rede.push.vapid.subject:}") String subject) {
		this.subscriptions = subscriptions;
		this.objectMapper = objectMapper;

		PushService service = null;
		if (StringUtils.hasText(publicKey) && StringUtils.hasText(privateKey)) {
			// A lib precisa do BouncyCastle para o ECDH do payload.
			if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
				Security.addProvider(new BouncyCastleProvider());
			}
			String vapidSubject = StringUtils.hasText(subject) ? subject : "mailto:suporte@enois.ia.br";
			try {
				service = new PushService(publicKey, privateKey, vapidSubject);
			}
			catch (Exception exception) {
				// Chave malformada na configuracao: desliga o push em vez de derrubar o boot.
				LOG.error("VAPID configurado mas invalido; Web Push ficara desligado: {}", exception.getMessage());
			}
		}
		else {
			LOG.info("Web Push desligado: configure VAPID_PUBLIC_KEY/VAPID_PRIVATE_KEY para ativar.");
		}
		this.pushService = service;
		this.publicKey = service != null ? publicKey.trim() : null;
	}

	@Override
	public boolean isEnabled() {
		return pushService != null;
	}

	@Override
	public String publicKey() {
		return pushService != null ? publicKey : null;
	}

	@Override
	public void sendToUser(String userId, PushMessage message) {
		if (pushService == null) {
			return;
		}
		List<PushSubscription> devices = subscriptions.findByUserId(userId);
		if (devices.isEmpty()) {
			return;
		}
		String payload = toJson(message);
		for (PushSubscription device : devices) {
			sendOne(device, payload);
		}
	}

	private void sendOne(PushSubscription device, String payload) {
		try {
			Notification notification = new Notification(device.endpoint(), device.p256dh(), device.auth(),
				payload.getBytes(StandardCharsets.UTF_8), TTL_SECONDS);
			int status = pushService.send(notification).getStatusLine().getStatusCode();
			if (status == HttpStatus.NOT_FOUND.value() || status == HttpStatus.GONE.value()) {
				// Dispositivo desinscrito/limpo: remove para nao tentar de novo.
				subscriptions.deleteById(device.id());
				LOG.info("push subscription expirada removida para o usuario {}", device.userId());
			}
			else if (status >= 400) {
				LOG.warn("push rejeitado (status {}) para o usuario {}", status, device.userId());
			}
		}
		catch (Exception exception) {
			// Push e melhor esforco: nunca deve derrubar o fluxo que gerou a notificacao.
			LOG.warn("falha ao enviar push para o usuario {}: {}", device.userId(), exception.getMessage());
		}
	}

	private String toJson(PushMessage message) {
		try {
			return objectMapper.writeValueAsString(Map.of("title", message.title(), "body", message.body(), "url",
				message.url() == null ? "/feed" : message.url()));
		}
		catch (Exception exception) {
			LOG.warn("falha ao serializar payload de push: {}", exception.getMessage());
			return "{}";
		}
	}
}
