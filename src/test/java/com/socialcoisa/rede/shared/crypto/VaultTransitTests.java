package com.socialcoisa.rede.shared.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * O adaptador do cofre é a peça que decide se um dado some ou não.
 *
 * <p>Cada teste cobre um modo de falha real: cofre recusando credencial, cofre fora, chave
 * desconhecida, tamanho errado e — o mais importante para latência — desembrulhar uma vez e reaproveitar.
 * O token do cofre nunca pode aparecer em resposta nem em log, então o stub também confere o cabeçalho.
 */
class VaultTransitTests {

	private static final String TRANSIT_KEY = "enois-data";
	private static final String TOKEN = "token-de-teste";
	private static final ObjectMapper MAPPER = JsonMapper.builder().build();

	private HttpServer server;
	private final List<String> paths = new CopyOnWriteArrayList<>();
	private final List<String> tokens = new CopyOnWriteArrayList<>();
	private final List<String> bodies = new CopyOnWriteArrayList<>();
	private final AtomicInteger decryptCalls = new AtomicInteger();

	private byte[] chave() {
		byte[] key = new byte[32];
		new SecureRandom().nextBytes(key);
		return key;
	}

	private void responder(int status, String json) {
		server.createContext("/v1/transit", (HttpExchange exchange) -> {
			paths.add(exchange.getRequestURI().getPath());
			tokens.add(exchange.getRequestHeaders().getFirst("X-Vault-Token"));
			bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			if (exchange.getRequestURI().getPath().contains("/decrypt/")) {
				decryptCalls.incrementAndGet();
			}
			byte[] payload = json.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(status, payload.length);
			exchange.getResponseBody().write(payload);
			exchange.close();
		});
	}

	@BeforeEach
	void subirStub() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.start();
	}

	@AfterEach
	void derrubarStub() {
		if (server != null) {
			server.stop(0);
		}
	}

	private URI endereco() {
		return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
	}

	private VaultTransitClient cliente() {
		return new VaultTransitClient(endereco(), TOKEN, TRANSIT_KEY, Duration.ofSeconds(2), MAPPER);
	}

	@Test
	void desembrulhaUsandoOTokenENaoVazaNada() {
		byte[] key = chave();
		responder(200, "{\"data\":{\"plaintext\":\"" + Base64.getEncoder().encodeToString(key) + "\"}}");

		byte[] unwrapped = cliente().decrypt("vault:v1:abc");

		assertThat(unwrapped).isEqualTo(key);
		assertThat(paths).containsExactly("/v1/transit/decrypt/" + TRANSIT_KEY);
		assertThat(tokens).containsExactly(TOKEN);
		assertThat(bodies.getFirst()).contains("vault:v1:abc");
	}

	@Test
	void embrulhaEnviandoABase64DaChave() {
		responder(200, "{\"data\":{\"ciphertext\":\"vault:v1:novo\"}}");
		byte[] key = chave();

		String wrapped = cliente().encrypt(key);

		assertThat(wrapped).isEqualTo("vault:v1:novo");
		assertThat(paths).containsExactly("/v1/transit/encrypt/" + TRANSIT_KEY);
		assertThat(bodies.getFirst()).contains(Base64.getEncoder().encodeToString(key));
	}

	@Test
	void credencialRecusadaTemCodigoProprio() {
		responder(403, "{\"errors\":[\"permission denied\"]}");

		assertThatThrownBy(() -> cliente().decrypt("vault:v1:x"))
			.isInstanceOfSatisfying(CryptoException.class,
				failure -> assertThat(failure.code()).isEqualTo("VAULT_DENIED"));
	}

	@Test
	void cofreForaDoArTemCodigoProprio() {
		responder(503, "{}");

		assertThatThrownBy(() -> cliente().decrypt("vault:v1:x"))
			.isInstanceOfSatisfying(CryptoException.class,
				failure -> assertThat(failure.code()).isEqualTo("VAULT_UNAVAILABLE"));
	}

	@Test
	void respostaSemChaveEConsideradaInvalida() {
		responder(200, "{\"data\":{}}");

		assertThatThrownBy(() -> cliente().decrypt("vault:v1:x"))
			.isInstanceOfSatisfying(CryptoException.class,
				failure -> assertThat(failure.code()).isEqualTo("VAULT_BAD_RESPONSE"));
	}

	@Test
	void providerDesembrulhaUmaVezEReaproveitaOCache() {
		byte[] key = chave();
		responder(200, "{\"data\":{\"plaintext\":\"" + Base64.getEncoder().encodeToString(key) + "\"}}");
		WrappedKeyStore store = new WrappedKeyStore() {
			@Override
			public String activeKid() {
				return "dek-1";
			}

			@Override
			public Optional<String> wrappedByKid(String kid) {
				return "dek-1".equals(kid) ? Optional.of("vault:v1:abc") : Optional.empty();
			}
		};
		VaultBackedKeyProvider provider = new VaultBackedKeyProvider(store, cliente());

		assertThat(provider.activeKid()).isEqualTo("dek-1");
		assertThat(provider.key("dek-1")).isEqualTo(key);
		assertThat(provider.key("dek-1")).isEqualTo(key);
		assertThat(decryptCalls.get()).isEqualTo(1);
	}

	@Test
	void providerRecusaChaveDesconhecidaSemChamarOCofre() {
		responder(200, "{}");
		WrappedKeyStore store = new WrappedKeyStore() {
			@Override
			public String activeKid() {
				return "dek-1";
			}

			@Override
			public Optional<String> wrappedByKid(String kid) {
				return Optional.empty();
			}
		};
		VaultBackedKeyProvider provider = new VaultBackedKeyProvider(store, cliente());

		assertThatThrownBy(() -> provider.key("dek-9"))
			.isInstanceOfSatisfying(CryptoException.class,
				failure -> assertThat(failure.code()).isEqualTo("KEY_UNKNOWN"));
		assertThat(paths).isEmpty();
	}

	@Test
	void providerRecusaChaveComTamanhoErrado() {
		byte[] curta = new byte[16];
		responder(200, "{\"data\":{\"plaintext\":\"" + Base64.getEncoder().encodeToString(curta) + "\"}}");
		WrappedKeyStore store = new WrappedKeyStore() {
			@Override
			public String activeKid() {
				return "dek-1";
			}

			@Override
			public Optional<String> wrappedByKid(String kid) {
				return Optional.of("vault:v1:abc");
			}
		};

		assertThatThrownBy(() -> new VaultBackedKeyProvider(store, cliente()).key("dek-1"))
			.isInstanceOfSatisfying(CryptoException.class,
				failure -> assertThat(failure.code()).isEqualTo("KEY_INVALID"));
	}

	@Test
	void semTokenOClienteNemEhCriado() {
		assertThatThrownBy(() -> new VaultTransitClient(endereco(), " ", TRANSIT_KEY, Duration.ofSeconds(1), MAPPER))
			.isInstanceOfSatisfying(CryptoException.class,
				failure -> assertThat(failure.code()).isEqualTo("VAULT_CONFIG_INVALID"));
		assertThat(paths).isEmpty();
	}

	@Test
	void oEnvelopeAbreComAChaveDesembrulhada() {
		byte[] key = chave();
		responder(200, "{\"data\":{\"plaintext\":\"" + Base64.getEncoder().encodeToString(key) + "\"}}");
		WrappedKeyStore store = new WrappedKeyStore() {
			@Override
			public String activeKid() {
				return "dek-1";
			}

			@Override
			public Optional<String> wrappedByKid(String kid) {
				return Optional.of("vault:v1:abc");
			}
		};
		EnvelopeCipher cipher = new EnvelopeCipher(new VaultBackedKeyProvider(store, cliente()));

		String envelope = cipher.encrypt("telefone da pessoa", "users:phone:user_1");

		assertThat(envelope).startsWith("v1:dek-1:");
		assertThat(cipher.decrypt(envelope, "users:phone:user_1")).isEqualTo("telefone da pessoa");
		assertThat(Map.of("chamadas", decryptCalls.get())).containsEntry("chamadas", 1);
	}
}
