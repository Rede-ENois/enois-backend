package com.socialcoisa.rede.shared.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HKDF-SHA256 (RFC 5869): transforma o segredo compartilhado do ECDH numa chave AES.
 *
 * <p>Derivar em vez de usar o segredo cru importa: o segredo do ECDH não é uniformemente aleatório e
 * não deve ir direto para o AES. O {@code salt} e o {@code info} também separam contextos — uma chave
 * derivada para "requisição" não serve para "resposta".</p>
 */
public final class Hkdf {

	private Hkdf() {
	}

	/**
	 * @param ikm material de entrada (segredo do ECDH)
	 * @param salt valor público que amarra o contexto (aqui, o id da sessão)
	 * @param info rótulo do uso ("enois-e2e-v1")
	 * @param length bytes de saída (32 para AES-256)
	 */
	public static byte[] derive(byte[] ikm, byte[] salt, String info, int length) {
		return derive(ikm, salt, info == null ? new byte[0] : info.getBytes(StandardCharsets.UTF_8), length);
	}

	/** Variante binária do {@code info} — usada no vetor de teste do RFC 5869. */
	public static byte[] derive(byte[] ikm, byte[] salt, byte[] info, int length) {
		if (ikm == null || ikm.length == 0) {
			throw new CryptoException("HKDF_FAILED", "Segredo de entrada vazio.");
		}
		if (length <= 0 || length > 255 * 32) {
			throw new CryptoException("HKDF_FAILED", "Tamanho de derivacao invalido.");
		}
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(salt == null || salt.length == 0 ? new byte[32] : salt, "HmacSHA256"));
			byte[] prk = mac.doFinal(ikm);

			byte[] infoBytes = info == null ? new byte[0] : info;
			mac.init(new SecretKeySpec(prk, "HmacSHA256"));
			byte[] output = new byte[length];
			byte[] previous = new byte[0];
			int offset = 0;
			for (int block = 1; offset < length; block++) {
				mac.reset();
				mac.update(previous);
				mac.update(infoBytes);
				mac.update((byte) block);
				previous = mac.doFinal();
				int take = Math.min(previous.length, length - offset);
				System.arraycopy(previous, 0, output, offset, take);
				offset += take;
			}
			return output;
		}
		catch (GeneralSecurityException failure) {
			throw new CryptoException("HKDF_FAILED", "Nao foi possivel derivar a chave de canal.", failure);
		}
	}
}
