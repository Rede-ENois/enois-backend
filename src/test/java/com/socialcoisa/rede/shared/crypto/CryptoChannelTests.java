package com.socialcoisa.rede.shared.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;

import javax.crypto.KeyAgreement;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.shared.web.ApiException;

/**
 * O canal de aplicação é o que cifra o corpo das requisições. Estes testes provam três coisas que
 * quebram em silêncio se erradas: a derivação de chave está correta (vetor do RFC 5869), os dois
 * lados chegam à <em>mesma</em> chave, e um corpo cifrado num canal não abre em outro.
 */
class CryptoChannelTests {

	private static final Instant NOW = Instant.parse("2026-10-26T23:00:00Z");

	private static CryptoChannelService service() {
		return new CryptoChannelService(new CryptoSessionRegistry(Clock.fixed(NOW, ZoneOffset.UTC), 100),
			Clock.fixed(NOW, ZoneOffset.UTC), 30);
	}

	private static KeyPair p256() throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
		generator.initialize(new ECGenParameterSpec("secp256r1"));
		return generator.generateKeyPair();
	}

	private static String spki(PublicKey key) {
		return Base64.getEncoder().encodeToString(key.getEncoded());
	}

	private static PublicKey parse(String base64) throws Exception {
		return KeyFactory.getInstance("EC")
			.generatePublic(new java.security.spec.X509EncodedKeySpec(Base64.getDecoder().decode(base64)));
	}

	// ------------------------------------------------------------------ HKDF

	@Test
	void hkdfBateComOVetorDoRfc5869() {
		// RFC 5869, Test Case 1 (SHA-256): IKM 0x0b*22, salt 0x000102...0c, info 0xf0..f9, L=42.
		byte[] ikm = new byte[22];
		java.util.Arrays.fill(ikm, (byte) 0x0b);
		byte[] salt = HexFormat.of().parseHex("000102030405060708090a0b0c");
		byte[] info = HexFormat.of().parseHex("f0f1f2f3f4f5f6f7f8f9");

		byte[] okm = Hkdf.derive(ikm, salt, info, 42);

		assertThat(HexFormat.of().formatHex(okm)).isEqualTo(
			"3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865");
	}

	// ------------------------------------------------------------------ handshake

	@Test
	void osDoisLadosChegamAMesmaChaveEOCorpoCifraNosDoisSentidos() throws Exception {
		CryptoChannelService service = service();
		KeyPair client = p256();

		CryptoChannelService.Handshake handshake = service.accept(spki(client.getPublic()));

		assertThat(handshake.sessionId()).isNotBlank();
		assertThat(handshake.algorithm()).isEqualTo("ECDH-P256+HKDF-SHA256");

		// Lado do cliente: mesma derivação, com a chave pública do servidor.
		PublicKey serverPublic = parse(handshake.serverPublicKey());
		KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
		agreement.init(client.getPrivate());
		agreement.doPhase(serverPublic, true);
		byte[] clientKey = Hkdf.derive(agreement.generateSecret(),
			handshake.sessionId().getBytes(java.nio.charset.StandardCharsets.UTF_8),
			CryptoChannelService.HKDF_INFO, 32);

		CryptoSession serverSession = service.session(handshake.sessionId()).orElseThrow();
		assertThat(clientKey).isEqualTo(serverSession.key());

		// Corpo do cliente -> servidor.
		EnvelopeCipher clientSide = new EnvelopeCipher(new CryptoSessionKeyProvider(
			new CryptoSession(handshake.sessionId(), clientKey, serverSession.expiresAt())));
		EnvelopeCipher serverSide = new EnvelopeCipher(new CryptoSessionKeyProvider(serverSession));

		String corpo = "{\"email\":\"pessoa@exemplo.invalid\",\"senha\":\"segredo\"}";
		String envelope = clientSide.encrypt(corpo, "POST /auth/login");
		assertThat(serverSide.decrypt(envelope, "POST /auth/login")).isEqualTo(corpo);

		// Resposta do servidor -> cliente.
		String resposta = "{\"ok\":true}";
		assertThat(clientSide.decrypt(serverSide.encrypt(resposta, "RESP /auth/login"), "RESP /auth/login"))
			.isEqualTo(resposta);
	}

	@Test
	void envelopeDeOutroCanalNaoAbre() throws Exception {
		CryptoChannelService service = service();
		KeyPair clientA = p256();
		KeyPair clientB = p256();
		CryptoChannelService.Handshake a = service.accept(spki(clientA.getPublic()));
		CryptoChannelService.Handshake b = service.accept(spki(clientB.getPublic()));

		EnvelopeCipher canalA = new EnvelopeCipher(
			new CryptoSessionKeyProvider(service.session(a.sessionId()).orElseThrow()));
		EnvelopeCipher canalB = new EnvelopeCipher(
			new CryptoSessionKeyProvider(service.session(b.sessionId()).orElseThrow()));

		String envelope = canalA.encrypt("corpo do canal A", "POST /x");

		// Mesmo AAD, outro canal: recusado pela identidade do canal (o `kid`), antes de tocar na
		// cifra — o erro certo é "envelope de outro canal", não "integridade".
		assertThatThrownBy(() -> canalB.decrypt(envelope, "POST /x"))
			.isInstanceOfSatisfying(CryptoException.class,
				failure -> assertThat(failure.code()).isEqualTo("KEY_UNKNOWN"));
	}

	@Test
	void chavePublicaInvalidaResponde400() {
		CryptoChannelService service = service();

		assertThatThrownBy(() -> service.accept("nao-e-base64!!"))
			.isInstanceOfSatisfying(ApiException.class, failure -> {
				assertThat(failure.status()).isEqualTo(400);
				assertThat(failure.code()).isEqualTo("CRYPTO_INVALID_PUBLIC_KEY");
			});
	}

	@Test
	void curvaDiferenteDeP256ERecusada() throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
		generator.initialize(new ECGenParameterSpec("secp384r1"));
		String outraCurva = spki(generator.generateKeyPair().getPublic());

		assertThatThrownBy(() -> service().accept(outraCurva))
			.isInstanceOfSatisfying(ApiException.class, failure -> {
				assertThat(failure.status()).isEqualTo(400);
				assertThat(failure.code()).isEqualTo("CRYPTO_UNSUPPORTED_CURVE");
			});
	}

	// ------------------------------------------------------------------ registro

	@Test
	void sessaoVencidaNaoEEncontrada() {
		CryptoSessionRegistry registry = new CryptoSessionRegistry(Clock.fixed(NOW, ZoneOffset.UTC), 10);
		registry.put(new CryptoSession("expirada", new byte[32], NOW.minusSeconds(1)));

		assertThat(registry.find("expirada")).isEmpty();
	}

	@Test
	void tetoDeSessoesDescartaAQueVencePrimeiro() {
		CryptoSessionRegistry registry = new CryptoSessionRegistry(Clock.fixed(NOW, ZoneOffset.UTC), 2);
		registry.put(new CryptoSession("curta", new byte[32], NOW.plusSeconds(60)));
		registry.put(new CryptoSession("longa", new byte[32], NOW.plusSeconds(600)));
		registry.put(new CryptoSession("nova", new byte[32], NOW.plusSeconds(300)));

		assertThat(registry.size()).isEqualTo(2);
		assertThat(registry.find("curta")).isEmpty();
		assertThat(registry.find("longa")).isPresent();
		assertThat(registry.find("nova")).isPresent();
	}

}
