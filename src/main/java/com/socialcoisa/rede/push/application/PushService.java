package com.socialcoisa.rede.push.application;

import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.push.application.port.out.PushSender;
import com.socialcoisa.rede.push.application.port.out.PushSubscriptionRepository;
import com.socialcoisa.rede.push.domain.PushSubscription;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Registro das inscricoes de Web Push do usuario (uma por dispositivo/navegador).
 */
@Service
public class PushService {

	/**
	 * O teto do endpoint de push: e o tamanho da coluna (`push_subscriptions.endpoint varchar(700)`).
	 * O numero nao e arbitrario — 700 caracteres cabem no limite de chave do InnoDB (3072 bytes em
	 * utf8mb4) sem precisar de indice com prefixo, e o H2 (usado nos testes) nao aceita o prefixo.
	 * A entidade declarava 1000, ou seja, prometia mais do que a coluna guarda: passar do limite
	 * quebrava no INSERT com "Data too long", que o tradutor de excecoes devolve como 409
	 * "Operacao conflita com o estado atual dos dados" — sem dizer que era o endpoint longo.
	 */
	static final int MAX_ENDPOINT_LENGTH = 700;

	/**
	 * Teto de dispositivos por conta. Sem ele, uma conta podia registrar milhares de endpoints e
	 * transformar cada notificacao em milhares de POSTs server-side (fan-out).
	 */
	public static final int MAX_SUBSCRIPTIONS_PER_USER = 10;

	/**
	 * Hosts dos servicos de push realmente usados pelos navegadores. O endpoint e informado pelo
	 * cliente e o servidor faz POST nele, entao aceitar qualquer https transformava o registro em
	 * um SSRF cego. A comparacao e por host exato ou sufixo de dominio.
	 */
	public static final Set<String> ALLOWED_PUSH_HOSTS = Set.of(
		"fcm.googleapis.com",
		"updates.push.services.mozilla.com",
		"push.services.mozilla.com",
		"web.push.apple.com",
		"notify.windows.com");

	private final PushSubscriptionRepository repository;
	private final PushSender sender;
	private final Clock clock;

	public PushService(PushSubscriptionRepository repository, PushSender sender, Clock clock) {
		this.repository = repository;
		this.sender = sender;
		this.clock = clock;
	}

	public boolean isEnabled() {
		return sender.isEnabled();
	}

	/** Chave publica VAPID para o app se inscrever (nula se o push estiver desligado). */
	public String publicKey() {
		return sender.publicKey();
	}

	@Transactional
	public PushSubscription register(String userId, String endpoint, String p256dh, String auth) {
		if (!StringUtils.hasText(endpoint) || !endpoint.startsWith("https://")) {
			throw new ApiException(400, "INVALID_PUSH_SUBSCRIPTION", "Endpoint de push invalido.");
		}
		if (!StringUtils.hasText(p256dh) || !StringUtils.hasText(auth)) {
			throw new ApiException(400, "INVALID_PUSH_SUBSCRIPTION", "Chaves do dispositivo obrigatorias.");
		}
		if (endpoint.length() > MAX_ENDPOINT_LENGTH) {
			throw new ApiException(400, "INVALID_PUSH_SUBSCRIPTION",
				"Endpoint de push longo demais (maximo " + MAX_ENDPOINT_LENGTH + " caracteres).");
		}
		String trimmedEndpoint = endpoint.trim();
		if (!allowedPushEndpoint(trimmedEndpoint)) {
			throw new ApiException(400, "INVALID_PUSH_SUBSCRIPTION",
				"Endpoint de push de um servico nao suportado.");
		}
		var existingSubscription = repository.findByEndpoint(trimmedEndpoint);
		if (existingSubscription.isEmpty()
				&& repository.findByUserId(userId).size() >= MAX_SUBSCRIPTIONS_PER_USER) {
			throw new ApiException(429, "TOO_MANY_PUSH_SUBSCRIPTIONS",
				"Limite de " + MAX_SUBSCRIPTIONS_PER_USER + " dispositivos por conta atingido.");
		}
		PushSubscription subscription = existingSubscription
			.map(existing -> {
				// Mesmo dispositivo voltando (ou trocando de conta): atualiza o dono e as chaves.
				return PushSubscription.restore(existing.id(), userId, trimmedEndpoint, p256dh.trim(), auth.trim(),
					existing.createdAt(), clock.instant());
			})
			.orElseGet(() -> PushSubscription.create("push_" + UUID.randomUUID().toString().replace("-", ""), userId,
				trimmedEndpoint, p256dh.trim(), auth.trim(), clock.instant()));
		return repository.save(subscription);
	}

	/** Só https, sem credenciais na URL e com host de um servico de push conhecido. */
	public static boolean allowedPushEndpoint(String endpoint) {
		return allowedPushEndpoint(endpoint, ALLOWED_PUSH_HOSTS);
	}

	/** Mesma checagem contra um conjunto de hosts (os testes usam um servico de push local). */
	public static boolean allowedPushEndpoint(String endpoint, Set<String> allowedHosts) {
		try {
			URI uri = URI.create(endpoint);
			if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null
					|| uri.getHost() == null) {
				return false;
			}
			int port = uri.getPort();
			if (port != -1 && port != 443) {
				return false;
			}
			String host = uri.getHost().toLowerCase(java.util.Locale.ROOT);
			for (String allowed : allowedHosts) {
				if (host.equals(allowed) || host.endsWith("." + allowed)) {
					return true;
				}
			}
			return false;
		}
		catch (RuntimeException invalid) {
			return false;
		}
	}

	@Transactional
	public void unregister(String userId, String endpoint) {
		if (!StringUtils.hasText(endpoint)) {
			throw new ApiException(400, "INVALID_PUSH_SUBSCRIPTION", "Endpoint de push obrigatorio.");
		}
		repository.deleteByEndpointAndUserId(endpoint.trim(), userId);
	}

	public List<PushSubscription> listByUser(String userId) {
		return repository.findByUserId(userId);
	}
}