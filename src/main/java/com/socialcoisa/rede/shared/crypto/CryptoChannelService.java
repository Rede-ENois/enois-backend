package com.socialcoisa.rede.shared.crypto;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import javax.crypto.KeyAgreement;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Handshake do canal de aplicação: cliente e servidor combinam uma chave efêmera e passam a trocar
 * corpo cifrado, mesmo que o TLS termine antes (proxy, CDN, inspeção de rede corporativa, log de
 * borda).
 *
 * <p><strong>O que isso protege e o que não protege</strong>: protege o conteúdo no caminho e em
 * qualquer lugar que guarde o corpo da requisição; <em>não</em> protege de quem opera o servidor —
 * o servidor precisa decifrar para responder. E2E de verdade (servidor cego) é outra trilha e só
 * existe para conteúdo que o servidor não precisa ler.</p>
 *
 * <p>Algoritmo: ECDH P-256 (suportado por todo navegador via WebCrypto) + HKDF-SHA256 com
 * {@code salt = sessionId} e {@code info = enois-e2e-v1}. A chave derivada tem 32 bytes (AES-256-GCM)
 * e nunca sai do processo.</p>
 */
@Service
public class CryptoChannelService {

	/** Rótulo de contexto da derivação — mudar isto invalida todos os canais em uso. */
	public static final String HKDF_INFO = "enois-e2e-v1";
	public static final String CURVE = "secp256r1";
	private static final int KEY_BYTES = 32;
	private static final int SESSION_ID_BYTES = 16;
	private static final SecureRandom RANDOM = new SecureRandom();

	private final CryptoSessionRegistry registry;
	private final Clock clock;
	private final Duration ttl;

	public CryptoChannelService(CryptoSessionRegistry registry, Clock clock,
			@Value("${rede.crypto.session-ttl-minutes:30}") long ttlMinutes) {
		this.registry = registry;
		this.clock = clock;
		this.ttl = Duration.ofMinutes(Math.max(1, ttlMinutes));
	}

	/** Resposta do handshake: identificador da sessão e a chave pública efêmera do servidor. */
	public record Handshake(String sessionId, String serverPublicKey, String expiresAt, String algorithm) {
	}

	/**
	 * Executa o handshake a partir da chave pública efêmera do cliente.
	 *
	 * @param clientPublicKeyBase64 chave pública EC P-256 em X.509/SPKI, base64
	 */
	public Handshake accept(String clientPublicKeyBase64) {
		PublicKey clientKey = parseClientKey(clientPublicKeyBase64);
		KeyPair serverPair = generateEphemeral();
		byte[] shared = agree(serverPair.getPrivate(), clientKey);
		String sessionId = newSessionId();
		byte[] key = Hkdf.derive(shared, sessionId.getBytes(java.nio.charset.StandardCharsets.UTF_8), HKDF_INFO,
			KEY_BYTES);
		Instant expiresAt = clock.instant().plus(ttl);
		registry.put(new CryptoSession(sessionId, key, expiresAt));
		return new Handshake(sessionId,
			Base64.getEncoder().encodeToString(serverPair.getPublic().getEncoded()),
			expiresAt.toString(), "ECDH-P256+HKDF-SHA256");
	}

	/** Chave da sessão para cifrar/decifrar — vazio quando o canal não existe ou venceu. */
	java.util.Optional<CryptoSession> session(String sessionId) {
		return registry.find(sessionId);
	}

	// ------------------------------------------------------------------ interno

	private static PublicKey parseClientKey(String base64) {
		if (base64 == null || base64.isBlank()) {
			throw new ApiException(400, "CRYPTO_INVALID_PUBLIC_KEY", "Informe a chave publica do canal.");
		}
		byte[] encoded;
		try {
			encoded = Base64.getDecoder().decode(base64.trim());
		}
		catch (IllegalArgumentException failure) {
			throw new ApiException(400, "CRYPTO_INVALID_PUBLIC_KEY", "A chave publica nao e base64 valido.");
		}
		try {
			PublicKey key = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(encoded));
			if (key instanceof ECPublicKey ec) {
				java.security.spec.ECParameterSpec params = ec.getParams();
				String curve = params == null ? "" : params.getCurve().getField() == null ? "" : curveName(params);
				if (!CURVE.equals(curve)) {
					throw new ApiException(400, "CRYPTO_UNSUPPORTED_CURVE",
						"A chave do canal precisa ser P-256 (secp256r1).");
				}
				return key;
			}
			throw new ApiException(400, "CRYPTO_INVALID_PUBLIC_KEY", "A chave publica nao e EC.");
		}
		catch (ApiException failure) {
			throw failure;
		}
		catch (java.security.GeneralSecurityException failure) {
			throw new ApiException(400, "CRYPTO_INVALID_PUBLIC_KEY", "Nao foi possivel ler a chave publica.");
		}
	}

	/** Nome da curva a partir dos parâmetros (o tamanho do campo identifica P-256: 256 bits). */
	private static String curveName(java.security.spec.ECParameterSpec params) {
		int fieldSize = params.getCurve().getField().getFieldSize();
		return fieldSize == 256 ? CURVE : "outra-" + fieldSize;
	}

	private static KeyPair generateEphemeral() {
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
			generator.initialize(new ECGenParameterSpec(CURVE));
			return generator.generateKeyPair();
		}
		catch (java.security.GeneralSecurityException failure) {
			throw new CryptoException("CRYPTO_HANDSHAKE_FAILED", "Nao foi possivel gerar a chave efemera do canal.",
				failure);
		}
	}

	private static byte[] agree(java.security.PrivateKey mine, PublicKey theirs) {
		try {
			KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
			agreement.init(mine);
			agreement.doPhase(theirs, true);
			return agreement.generateSecret();
		}
		catch (java.security.GeneralSecurityException failure) {
			throw new ApiException(400, "CRYPTO_HANDSHAKE_FAILED",
				"A chave publica informada nao serve para combinar o canal.");
		}
	}

	private static String newSessionId() {
		byte[] id = new byte[SESSION_ID_BYTES];
		RANDOM.nextBytes(id);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(id);
	}
}
