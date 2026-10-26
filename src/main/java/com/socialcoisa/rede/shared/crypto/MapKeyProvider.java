package com.socialcoisa.rede.shared.crypto;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Chaves em memória — <strong>apenas testes e desenvolvimento</strong>.
 *
 * <p>Em produção a chave de dados é embrulhada pelo KMS e desembrulhada por papel do pod; a chave em
 * claro nunca fica numa variável de ambiente do ambiente real. Esta classe existe para os testes
 * (e para um dev local) poderem exercitar o envelope sem infraestrutura.</p>
 */
public final class MapKeyProvider implements KeyProvider {

	private final String activeKid;
	private final Map<String, byte[]> keys;

	/**
	 * @param activeKid identificador que cifra registros novos
	 * @param keysBase64 mapa {@code kid → chave de 32 bytes em base64}
	 */
	public MapKeyProvider(String activeKid, Map<String, String> keysBase64) {
		if (activeKid == null || activeKid.isBlank()) {
			throw new CryptoException("KEY_INVALID", "Informe o identificador da chave ativa.");
		}
		if (keysBase64 == null || !keysBase64.containsKey(activeKid)) {
			throw new CryptoException("KEY_INVALID", "A chave ativa precisa existir no conjunto de chaves.");
		}
		Map<String, byte[]> parsed = new LinkedHashMap<>();
		for (Map.Entry<String, String> entry : keysBase64.entrySet()) {
			byte[] decoded;
			try {
				decoded = Base64.getDecoder().decode(entry.getValue().trim());
			}
			catch (IllegalArgumentException failure) {
				throw new CryptoException("KEY_INVALID", "A chave '" + entry.getKey() + "' nao e base64 valido.",
					failure);
			}
			if (decoded.length != EnvelopeCipher.KEY_BYTES) {
				throw new CryptoException("KEY_INVALID", "A chave '" + entry.getKey() + "' precisa ter "
					+ EnvelopeCipher.KEY_BYTES + " bytes (AES-256).");
			}
			parsed.put(entry.getKey(), decoded);
		}
		this.activeKid = activeKid;
		this.keys = Map.copyOf(parsed);
	}

	@Override
	public String activeKid() {
		return activeKid;
	}

	@Override
	public byte[] key(String kid) {
		byte[] found = kid == null ? null : keys.get(kid);
		if (found == null) {
			throw new CryptoException("KEY_UNKNOWN", "Chave '" + kid + "' nao existe nesta instalacao.");
		}
		return found;
	}
}
