package com.socialcoisa.rede.shared.crypto;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link KeyProvider} que desembrulha a DEK no cofre e mantém cache em memória.
 *
 * <p>Por que cache: chamar o cofre a cada linha cifrada transformaria leitura em latência de rede. A
 * chave desembrulhada fica em memória do processo, sem prazo próprio — ela é esquecida quando o
 * processo reinicia, e a rotação invalida na prática (o {@code kid} novo não está no cache). O cache
 * tem teto para não crescer com rotação infinita.</p>
 *
 * <p>Não é {@code @Component} de propósito: só entra no ar quando a tabela de chaves existir, porque
 * um provider sem store derrubaria o boot.</p>
 */
public final class VaultBackedKeyProvider implements KeyProvider {

	private static final int MAX_CACHED_KEYS = 64;

	private final WrappedKeyStore store;
	private final VaultTransitClient vault;
	private final Map<String, byte[]> cache = new ConcurrentHashMap<>();

	public VaultBackedKeyProvider(WrappedKeyStore store, VaultTransitClient vault) {
		if (store == null || vault == null) {
			throw new CryptoException("KEY_INVALID", "Store e cofre sao obrigatorios.");
		}
		this.store = store;
		this.vault = vault;
	}

	@Override
	public String activeKid() {
		String kid = store.activeKid();
		if (kid == null || kid.isBlank()) {
			throw new CryptoException("KEY_INVALID", "Nenhuma chave de dados ativa configurada.");
		}
		return kid;
	}

	@Override
	public byte[] key(String kid) {
		if (kid == null || kid.isBlank()) {
			throw new CryptoException("KEY_UNKNOWN", "Identificador de chave ausente.");
		}
		byte[] cached = cache.get(kid);
		if (cached != null) {
			return cached;
		}
		String wrapped = store.wrappedByKid(kid)
			.orElseThrow(() -> new CryptoException("KEY_UNKNOWN",
				"Chave de dados '" + kid + "' nao existe nesta instalacao."));
		byte[] key = vault.decrypt(wrapped);
		if (key.length != EnvelopeCipher.KEY_BYTES) {
			throw new CryptoException("KEY_INVALID", "A chave '" + kid + "' precisa ter "
				+ EnvelopeCipher.KEY_BYTES + " bytes.");
		}
		if (cache.size() >= MAX_CACHED_KEYS) {
			// Rotação longa: esquece tudo e reabre sob demanda. Simples de propósito; o custo é uma
			// chamada ao cofre por chave em uso, e só depois de 64 chaves diferentes no processo.
			cache.clear();
		}
		cache.put(kid, key);
		return key;
	}
}
