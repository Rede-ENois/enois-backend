package com.socialcoisa.rede.shared.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.FilterChain;

/**
 * O filtro é a fronteira: rota marcada ou vem cifrada num canal válido, ou é recusada.
 *
 * <p>Nenhum teste aqui "quase passa": ou o controller recebe o JSON decifrado, ou a resposta é um erro
 * com código próprio. Um corpo em claro numa rota marcada é falha, não conveniência.</p>
 */
class CryptoChannelFilterTests {

	private static final String ROTA = "/auth/login";
	private static final String CORPO = "{\"email\":\"pessoa@exemplo.invalid\",\"senha\":\"segredo\"}";

	private final CryptoSessionRegistry registry =
		new CryptoSessionRegistry(Clock.systemUTC(), 100);
	private final CryptoChannelService channel =
		new CryptoChannelService(registry, Clock.systemUTC(), 30);

	private record Canal(String sessionId, byte[] key) {
	}

	private Canal abrirCanal() throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
		generator.initialize(new ECGenParameterSpec("secp256r1"));
		KeyPair client = generator.generateKeyPair();
		CryptoChannelService.Handshake handshake = channel.accept(
			Base64.getEncoder().encodeToString(client.getPublic().getEncoded()));

		PublicKey serverPublic = java.security.KeyFactory.getInstance("EC").generatePublic(
			new java.security.spec.X509EncodedKeySpec(Base64.getDecoder().decode(handshake.serverPublicKey())));
		javax.crypto.KeyAgreement agreement = javax.crypto.KeyAgreement.getInstance("ECDH");
		agreement.init(client.getPrivate());
		agreement.doPhase(serverPublic, true);
		byte[] key = Hkdf.derive(agreement.generateSecret(),
			handshake.sessionId().getBytes(StandardCharsets.UTF_8), CryptoChannelService.HKDF_INFO, 32);
		return new Canal(handshake.sessionId(), key);
	}

	private String envelope(Canal canal, String aad) {
		return new EnvelopeCipher(new CryptoSessionKeyProvider(
			new CryptoSession(canal.sessionId(), canal.key(), Instant.now().plusSeconds(600))))
			.encrypt(CORPO, aad);
	}

	private MockHttpServletRequest request(String path, String method, String body) {
		MockHttpServletRequest request = new MockHttpServletRequest(method, path);
		request.setContent(body.getBytes(StandardCharsets.UTF_8));
		return request;
	}

	private FilterChain chainCapture(AtomicReference<String> captured) {
		return (req, res) -> captured.set(new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
	}

	@Test
	void rotaNaoMarcadaPassaDireto() throws Exception {
		CryptoChannelFilter filter = new CryptoChannelFilter(registry, "");
		AtomicReference<String> viu = new AtomicReference<>();

		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilter(request(ROTA, "POST", CORPO), response, chainCapture(viu));

		assertThat(viu.get()).isEqualTo(CORPO);
		assertThat(response.getStatus()).isEqualTo(200);
	}

	@Test
	void rotaMarcadaDecifraOCorpoParaOController() throws Exception {
		CryptoChannelFilter filter = new CryptoChannelFilter(registry, ROTA);
		Canal canal = abrirCanal();
		String ts = String.valueOf(Instant.now().getEpochSecond());
		String nonce = "nonce-1";
		String aad = "REQ POST " + ROTA + " " + ts + " " + nonce;

		MockHttpServletRequest request = request(ROTA, "POST", envelope(canal, aad));
		request.addHeader(CryptoChannelFilter.SESSION_HEADER, canal.sessionId());
		request.addHeader(CryptoChannelFilter.TIMESTAMP_HEADER, ts);
		request.addHeader(CryptoChannelFilter.NONCE_HEADER, nonce);
		AtomicReference<String> viu = new AtomicReference<>();

		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilter(request, response, chainCapture(viu));

		assertThat(viu.get()).isEqualTo(CORPO);
		assertThat(response.getStatus()).isEqualTo(200);
	}

	@Test
	void rotaMarcadaSemCanalResponde409() throws Exception {
		CryptoChannelFilter filter = new CryptoChannelFilter(registry, ROTA);
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request(ROTA, "POST", CORPO), response, chainCapture(new AtomicReference<>()));

		assertThat(response.getStatus()).isEqualTo(409);
		assertThat(response.getContentAsString()).contains("CRYPTO_CHANNEL_REQUIRED");
	}

	@Test
	void nonceRepetidoERecusado() throws Exception {
		CryptoChannelFilter filter = new CryptoChannelFilter(registry, ROTA);
		Canal canal = abrirCanal();
		String ts = String.valueOf(Instant.now().getEpochSecond());
		String nonce = "nonce-repetido";
		String aad = "REQ POST " + ROTA + " " + ts + " " + nonce;

		for (int tentativa = 0; tentativa < 2; tentativa++) {
			MockHttpServletRequest request = request(ROTA, "POST", envelope(canal, aad));
			request.addHeader(CryptoChannelFilter.SESSION_HEADER, canal.sessionId());
			request.addHeader(CryptoChannelFilter.TIMESTAMP_HEADER, ts);
			request.addHeader(CryptoChannelFilter.NONCE_HEADER, nonce);
			MockHttpServletResponse response = new MockHttpServletResponse();
			filter.doFilter(request, response, chainCapture(new AtomicReference<>()));
			if (tentativa == 1) {
				assertThat(response.getStatus()).isEqualTo(409);
				assertThat(response.getContentAsString()).contains("CRYPTO_REPLAY");
			}
		}
	}

	@Test
	void timestampVelhoERecusado() throws Exception {
		CryptoChannelFilter filter = new CryptoChannelFilter(registry, ROTA);
		Canal canal = abrirCanal();
		String ts = String.valueOf(Instant.now().minusSeconds(3600).getEpochSecond());
		String nonce = "nonce-antigo";
		String aad = "REQ POST " + ROTA + " " + ts + " " + nonce;

		MockHttpServletRequest request = request(ROTA, "POST", envelope(canal, aad));
		request.addHeader(CryptoChannelFilter.SESSION_HEADER, canal.sessionId());
		request.addHeader(CryptoChannelFilter.TIMESTAMP_HEADER, ts);
		request.addHeader(CryptoChannelFilter.NONCE_HEADER, nonce);
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, chainCapture(new AtomicReference<>()));

		assertThat(response.getStatus()).isEqualTo(409);
		assertThat(response.getContentAsString()).contains("CRYPTO_TIMESTAMP_INVALID");
	}

	@Test
	void corpoCifradoParaOutraRotaNaoAbre() throws Exception {
		CryptoChannelFilter filter = new CryptoChannelFilter(registry, ROTA);
		Canal canal = abrirCanal();
		String ts = String.valueOf(Instant.now().getEpochSecond());
		String nonce = "nonce-outra-rota";
		// AAD de outra rota: o corpo não pode ser reaproveitado aqui.
		String aadDeOutraRota = "REQ POST /auth/signup " + ts + " " + nonce;

		MockHttpServletRequest request = request(ROTA, "POST", envelope(canal, aadDeOutraRota));
		request.addHeader(CryptoChannelFilter.SESSION_HEADER, canal.sessionId());
		request.addHeader(CryptoChannelFilter.TIMESTAMP_HEADER, ts);
		request.addHeader(CryptoChannelFilter.NONCE_HEADER, nonce);
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, chainCapture(new AtomicReference<>()));

		assertThat(response.getStatus()).isEqualTo(400);
		assertThat(response.getContentAsString()).contains("INTEGRITY_FAILED");
	}

	@Test
	void getEmRotaMarcadaNaoExigeCanal() throws Exception {
		CryptoChannelFilter filter = new CryptoChannelFilter(registry, ROTA);
		MockHttpServletResponse response = new MockHttpServletResponse();
		AtomicReference<String> viu = new AtomicReference<>();

		filter.doFilter(request(ROTA, "GET", ""), response, chainCapture(viu));

		assertThat(response.getStatus()).isEqualTo(200);
	}
}
