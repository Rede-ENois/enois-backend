package com.socialcoisa.rede.push.adapters.out.webpush;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.bouncycastle.jce.interfaces.ECPrivateKey;
import org.bouncycastle.jce.interfaces.ECPublicKey;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.push.application.port.out.PushSender.PushMessage;
import com.socialcoisa.rede.push.application.port.out.PushSubscriptionRepository;
import com.socialcoisa.rede.push.domain.PushSubscription;
import com.sun.net.httpserver.HttpServer;

import nl.martijndwars.webpush.Base64Encoder;
import nl.martijndwars.webpush.Utils;

import tools.jackson.databind.ObjectMapper;

/**
 * O envio de verdade: o pacote sai cifrado com a chave do "dispositivo", assinado com a chave VAPID
 * do servidor, e a resposta do serviço de push decide o que fazer com a inscrição.
 *
 * O "serviço de push" aqui é um HTTP local que só grava o que recebeu — nenhuma notificação sai
 * para a internet.
 */
class WebPushDeliveryTests {

	private HttpServer server;

	private final List<String> received = new CopyOnWriteArrayList<>();

	private final List<String> removed = new CopyOnWriteArrayList<>();

	/** Resposta por caminho: 410 é dispositivo desinscrito, 413 é payload recusado (mantém). */
	private Map<String, Integer> responses = Map.of("/gone", 410, "/rejected", 413, "/ok", 201);

	private WebPushSender sender;

	private PushSubscriptionRepository repository;

	@BeforeEach
	void setUp() throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			received.add(exchange.getRequestURI().getPath());
			int status = responses.getOrDefault(exchange.getRequestURI().getPath(), 201);
			exchange.sendResponseHeaders(status, -1);
			exchange.close();
		});
		server.start();

		KeyPair vapid = keyPair();
		repository = new InMemorySubscriptions();
		sender = new WebPushSender(repository, new ObjectMapper(),
			Base64Encoder.encodeUrlWithoutPadding(Utils.encode((ECPublicKey) vapid.getPublic())),
			Base64Encoder.encodeUrlWithoutPadding(Utils.encode((ECPrivateKey) vapid.getPrivate())),
			"mailto:teste@enois.test");
	}

	@AfterEach
	void tearDown() {
		server.stop(0);
	}

	@Test
	void deliversToEveryDeviceOfTheUser() {
		repository.save(subscription("push_a", "user_1", "/ok"));
		repository.save(subscription("push_b", "user_1", "/rejected"));

		sender.sendToUser("user_1", new PushMessage("Titulo", "Corpo", "/par-de-estudo"));

		assertThat(received).containsExactlyInAnyOrder("/ok", "/rejected");
		// 413 é recusa pontual do serviço (payload, limite): a inscrição continua valendo.
		assertThat(removed).isEmpty();
	}

	@Test
	void removesTheDeviceWhenThePushServiceSaysItIsGone() {
		repository.save(subscription("push_gone", "user_1", "/gone"));

		sender.sendToUser("user_1", new PushMessage("Titulo", "Corpo", "/feed"));

		assertThat(received).containsExactly("/gone");
		assertThat(removed).containsExactly("push_gone");
		assertThat(repository.findByUserId("user_1")).isEmpty();
	}

	@Test
	void doesNothingWithoutDevices() {
		sender.sendToUser("user_sem_dispositivo", new PushMessage("Titulo", "Corpo", "/feed"));
		assertThat(received).isEmpty();
	}

	@Test
	void reportsItselfDisabledWithoutKeys() {
		WebPushSender off = new WebPushSender(repository, new ObjectMapper(), "", "", "mailto:teste@enois.test");
		assertThat(off.isEnabled()).isFalse();
		assertThat(off.publicKey()).isNull();
		off.sendToUser("user_1", new PushMessage("Titulo", "Corpo", "/feed"));
		assertThat(received).isEmpty();
	}

	private PushSubscription subscription(String id, String userId, String path) {
		KeyPair device = keyPair();
		String browserPublic = Base64Encoder.encodeUrlWithoutPadding(Utils.encode((ECPublicKey) device.getPublic()));
		String auth = Base64Encoder.encodeUrlWithoutPadding(randomBytes(16));
		String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + path;
		return PushSubscription.create(id, userId, endpoint, browserPublic, auth, Instant.now());
	}

	private static byte[] randomBytes(int size) {
		byte[] bytes = new byte[size];
		new java.security.SecureRandom().nextBytes(bytes);
		return bytes;
	}

	private static KeyPair keyPair() {
		if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
			Security.addProvider(new BouncyCastleProvider());
		}
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("ECDH", BouncyCastleProvider.PROVIDER_NAME);
			generator.initialize(new ECGenParameterSpec("secp256r1"));
			return generator.generateKeyPair();
		}
		catch (Exception exception) {
			throw new IllegalStateException("nao foi possivel gerar o par de chaves do teste", exception);
		}
	}

	private final class InMemorySubscriptions implements PushSubscriptionRepository {

		private final Map<String, PushSubscription> byId = new java.util.LinkedHashMap<>();

		@Override
		public PushSubscription save(PushSubscription subscription) {
			byId.put(subscription.id(), subscription);
			return subscription;
		}

		@Override
		public Optional<PushSubscription> findByEndpoint(String endpoint) {
			return byId.values().stream().filter(s -> s.endpoint().equals(endpoint)).findFirst();
		}

		@Override
		public List<PushSubscription> findByUserId(String userId) {
			return byId.values().stream().filter(s -> s.userId().equals(userId)).toList();
		}

		@Override
		public void deleteById(String id) {
			removed.add(id);
			byId.remove(id);
		}

		@Override
		public void deleteByEndpointAndUserId(String endpoint, String userId) {
			byId.values().removeIf(s -> s.endpoint().equals(endpoint) && s.userId().equals(userId));
		}
	}
}