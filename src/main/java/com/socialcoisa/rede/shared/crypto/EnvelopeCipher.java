package com.socialcoisa.rede.shared.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Envelope de dado sensível: {@code v1:<kid>:<base64(iv || ciphertext || tag)>}.
 *
 * <p><strong>Por que envelope e não só AES.</strong> O {@code kid} permite conviver com duas chaves
 * durante a rotação (o registro antigo continua legível) e a versão permite mudar o formato no futuro
 * sem adivinhar o que é o quê. O {@code AAD} amarra o conteúdo ao lugar de onde ele veio — mover a
 * cifra de uma linha (ou de uma coluna) para outra faz a decifra falhar em vez de devolver o dado
 * errado, que é a pior falha possível num sistema assim.</p>
 *
 * <p>O AAD é obrigatório e deve ser estável e único por registro, por exemplo
 * {@code users:email:user_123}. Nunca inclua no AAD algo que mude (timestamp de atualização, por
 * exemplo): o registro deixaria de abrir.</p>
 *
 * <p>Nada aqui guarda chave: o material vem do {@link KeyProvider}, que em produção fala com o KMS.
 * O envelope carrega apenas o identificador da chave.</p>
 */
public final class EnvelopeCipher {

	/** Versão do formato. Mudança de formato = nova versão, nunca reinterpretar a antiga. */
	public static final String VERSION = "v1";
	/** AES-256 exige 32 bytes de chave. */
	public static final int KEY_BYTES = 32;

	private static final int IV_BYTES = 12;
	private static final int TAG_BITS = 128;
	private static final String TRANSFORMATION = "AES/GCM/NoPadding";
	private static final SecureRandom RANDOM = new SecureRandom();

	private final KeyProvider keys;

	public EnvelopeCipher(KeyProvider keys) {
		if (keys == null) {
			throw new CryptoException("KEY_INVALID", "KeyProvider obrigatorio.");
		}
		this.keys = keys;
	}

	/**
	 * Cifra o texto e devolve o envelope.
	 *
	 * @param plaintext conteúdo em claro (nunca nulo)
	 * @param aad amarração estável do registro, ex. {@code users:email:user_123}
	 */
	public String encrypt(String plaintext, String aad) {
		if (plaintext == null) {
			throw new CryptoException("PLAINTEXT_REQUIRED", "Nao ha o que cifrar: conteudo nulo.");
		}
		requireAad(aad);
		String kid = keys.activeKid();
		byte[] key = keys.key(kid);
		requireKeyLength(key, kid);

		byte[] iv = new byte[IV_BYTES];
		RANDOM.nextBytes(iv);
		try {
			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
			cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
			byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

			byte[] payload = new byte[iv.length + encrypted.length];
			System.arraycopy(iv, 0, payload, 0, iv.length);
			System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
			return VERSION + ":" + kid + ":" + Base64.getEncoder().encodeToString(payload);
		}
		catch (GeneralSecurityException failure) {
			throw new CryptoException("ENCRYPT_FAILED", "Nao foi possivel cifrar o conteudo.", failure);
		}
	}

	/**
	 * Abre o envelope.
	 *
	 * @throws CryptoException {@code MALFORMED_ENVELOPE} formato errado, {@code KEY_UNKNOWN} chave
	 * ausente nesta instalação, {@code INTEGRITY_FAILED} conteúdo corrompido ou AAD diferente
	 */
	public String decrypt(String envelope, String aad) {
		requireAad(aad);
		Parsed parsed = parse(envelope);
		byte[] key = keys.key(parsed.kid());
		requireKeyLength(key, parsed.kid());

		byte[] payload = decodedPayload(parsed);
		if (payload.length <= IV_BYTES) {
			throw new CryptoException("MALFORMED_ENVELOPE", "Envelope sem conteudo cifrado.");
		}
		byte[] iv = java.util.Arrays.copyOfRange(payload, 0, IV_BYTES);
		byte[] encrypted = java.util.Arrays.copyOfRange(payload, IV_BYTES, payload.length);
		try {
			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
			cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
			return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
		}
		catch (GeneralSecurityException failure) {
			// Tag inválida: dado corrompido OU envelope aberto com outro AAD/chave. Os dois são
			// "não confie neste valor" — e é isso que o código diz.
			throw new CryptoException("INTEGRITY_FAILED",
				"O conteudo cifrado nao confere: dado corrompido, chave errada ou registro movido de lugar.",
				failure);
		}
	}

	/** Indica se o texto tem cara de envelope deste formato (útil em migração dual-read). */
	public static boolean isEnvelope(String value) {
		if (value == null) {
			return false;
		}
		String[] parts = value.split(":", 3);
		return parts.length == 3 && VERSION.equals(parts[0]) && !parts[1].isBlank() && !parts[2].isBlank();
	}

	// ------------------------------------------------------------------ interno

	private record Parsed(String kid, String payloadBase64) {
	}

	private static Parsed parse(String envelope) {
		if (envelope == null || envelope.isBlank()) {
			throw new CryptoException("MALFORMED_ENVELOPE", "Envelope vazio.");
		}
		String[] parts = envelope.split(":", 3);
		if (parts.length != 3) {
			throw new CryptoException("MALFORMED_ENVELOPE", "Envelope fora do formato v1:<kid>:<dados>.");
		}
		if (!VERSION.equals(parts[0])) {
			throw new CryptoException("MALFORMED_ENVELOPE", "Versao de envelope desconhecida: " + parts[0]);
		}
		if (parts[1].isBlank() || parts[2].isBlank()) {
			throw new CryptoException("MALFORMED_ENVELOPE", "Envelope sem identificador de chave ou sem dados.");
		}
		return new Parsed(parts[1], parts[2]);
	}

	private static byte[] decodedPayload(Parsed parsed) {
		try {
			return Base64.getDecoder().decode(parsed.payloadBase64());
		}
		catch (IllegalArgumentException failure) {
			throw new CryptoException("MALFORMED_ENVELOPE", "Os dados do envelope nao sao base64 valido.", failure);
		}
	}

	private static void requireAad(String aad) {
		if (aad == null || aad.isBlank()) {
			throw new CryptoException("AAD_REQUIRED",
				"AAD obrigatorio: sem ele nao ha como impedir que a cifra seja movida de registro.");
		}
	}

	private static void requireKeyLength(byte[] key, String kid) {
		if (key == null || key.length != KEY_BYTES) {
			throw new CryptoException("KEY_INVALID", "A chave '" + kid + "' precisa ter " + KEY_BYTES + " bytes.");
		}
	}
}
