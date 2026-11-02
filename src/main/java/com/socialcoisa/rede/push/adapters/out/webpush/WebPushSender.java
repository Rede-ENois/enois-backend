package com.socialcoisa.rede.push.adapters.out.webpush;

import java.nio.charset.StandardCharsets;
import java.security.Security;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClients;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.push.application.PushService;
import com.socialcoisa.rede.push.application.port.out.PushSender;
import com.socialcoisa.rede.push.application.port.out.PushSubscriptionRepository;
import com.socialcoisa.rede.push.domain.PushSubscription;

import jakarta.annotation.PreDestroy;
import nl.martijndwars.webpush.Encoding;
import nl.martijndwars.webpush.Notification;

import tools.jackson.databind.ObjectMapper;

/**
 * Envio de Web Push (VAPID + payload criptografado) usando a lib nl.martijndwars:web-push.
 *
 * Se as chaves VAPID nao estiverem configuradas, o envio fica desligado (as notificacoes seguem
 * funcionando dentro do app) — nada quebra por falta de configuracao.
 *
 * <p>O cliente HTTP e criado uma vez, com timeout de conexao e de resposta. A lib criava um
 * cliente novo por envio e sem prazo: um servico de push lento segurava a thread e o socket.</p>
 */
@Component
class WebPushSender implements PushSender {

	private static final Logger LOG = LoggerFactory.getLogger(WebPushSender.class);
	private static final int TTL_SECONDS = 60 * 60 * 24;
	private static final int CONNECT_TIMEOUT_MS = 5_000;
	private static final int RESPONSE_TIMEOUT_MS = 10_000;

	/** O resultado vem pelo Future; o callback so existe porque a API exige um. */
	private static final FutureCallback<HttpResponse> IGNORE_RESULT = new FutureCallback<>() {
		@Override
		public void completed(HttpResponse response) {
		}

		@Override
		public void failed(Exception exception) {
		}

		@Override
		public void cancelled() {
		}
	};

	private final PushSubscriptionRepository subscriptions;
	private final ObjectMapper objectMapper;
	private final nl.martijndwars.webpush.PushService pushService;
	private final CloseableHttpAsyncClient httpClient;
	private final java.util.function.Predicate<String> endpointAllowed;
	private final String publicKey;

	@Autowired
	WebPushSender(PushSubscriptionRepository subscriptions, ObjectMapper objectMapper,
			@Value("${rede.push.vapid.public-key:}") String publicKey,
			@Value("${rede.push.vapid.private-key:}") String privateKey,
			@Value("${rede.push.vapid.subject:}") String subject) {
		this(subscriptions, objectMapper, publicKey, privateKey, subject, PushService::allowedPushEndpoint);
	}

	/** Variante com filtro proprio de endpoint, usada pelos testes com um servico de push local. */
	WebPushSender(PushSubscriptionRepository subscriptions, ObjectMapper objectMapper, String publicKey,
			String privateKey, String subject, java.util.function.Predicate<String> endpointAllowed) {
		this.subscriptions = subscriptions;
		this.objectMapper = objectMapper;
		this.endpointAllowed = endpointAllowed;

		nl.martijndwars.webpush.PushService service = null;
		CloseableHttpAsyncClient client = null;
		if (StringUtils.hasText(publicKey) && StringUtils.hasText(privateKey)) {
			// A lib precisa do BouncyCastle para o ECDH do payload.
			if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
				Security.addProvider(new BouncyCastleProvider());
			}
			String vapidSubject = StringUtils.hasText(subject) ? subject : "mailto:suporte@enois.ia.br";
			try {
				service = new nl.martijndwars.webpush.PushService(publicKey, privateKey, vapidSubject);
				client = HttpAsyncClients.custom()
					.setDefaultRequestConfig(RequestConfig.custom()
						.setConnectTimeout(CONNECT_TIMEOUT_MS)
						.setSocketTimeout(RESPONSE_TIMEOUT_MS)
						.build())
					.build();
				client.start();
			}
			catch (Exception exception) {
				// Chave malformada na configuracao: desliga o push em vez de derrubar o boot.
				LOG.error("VAPID configurado mas invalido; Web Push ficara desligado: {}", exception.getMessage());
				closeQuietly(client);
				service = null;
				client = null;
			}
		}
		else {
			LOG.info("Web Push desligado: configure VAPID_PUBLIC_KEY/VAPID_PRIVATE_KEY para ativar.");
		}
		this.pushService = service;
		this.httpClient = client;
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
		int sent = 0;
		for (PushSubscription device : devices) {
			if (sent >= PushService.MAX_SUBSCRIPTIONS_PER_USER) {
				LOG.warn("push: conta {} tem mais dispositivos que o teto; excedente ignorado", userId);
				break;
			}
			// Linhas antigas podem ter endpoint fora da allowlist: nao vira POST server-side.
			if (!endpointAllowed.test(device.endpoint())) {
				LOG.warn("push: endpoint fora da allowlist ignorado para o usuario {}", userId);
				continue;
			}
			sendOne(device, payload);
			sent++;
		}
	}

	private void sendOne(PushSubscription device, String payload) {
		try {
			Notification notification = new Notification(device.endpoint(), device.p256dh(), device.auth(),
				payload.getBytes(StandardCharsets.UTF_8), TTL_SECONDS);
			HttpPost post = pushService.preparePost(notification, Encoding.AESGCM);
			int status = httpClient.execute(post, IGNORE_RESULT)
				.get(CONNECT_TIMEOUT_MS + RESPONSE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
				.getStatusLine()
				.getStatusCode();
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

	@PreDestroy
	void close() {
		closeQuietly(httpClient);
	}

	private static void closeQuietly(CloseableHttpAsyncClient client) {
		if (client == null) {
			return;
		}
		try {
			client.close();
		}
		catch (Exception ignored) {
			// Encerrando: nao ha o que fazer com a falha.
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
