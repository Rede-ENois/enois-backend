package com.socialcoisa.rede.shared.crypto;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.PostConstruct;

/**
 * Garante que existe uma chave de dados ativa quando a cifra em repouso é ligada.
 *
 * <p>Sem isso, a primeira gravação falharia com {@code KEY_NOT_CONFIGURED} e ninguém saberia o que
 * fazer. O bootstrap gera a DEK no processo, manda o cofre embrulhar e guarda só o embrulho — a
 * chave em claro nunca é gravada, logada nem devolvida.</p>
 *
 * <p>Se já existe chave ativa, não toca em nada: rodar de novo (reinício de pod) não cria chave
 * nova, o que seria rotação acidental.</p>
 */
final class DataKeyBootstrap {

	private static final Logger LOG = LoggerFactory.getLogger(DataKeyBootstrap.class);

	private static final int KEY_BYTES = 32;

	private final WrappedKeyStore store;
	private final VaultTransitClient vault;
	private final SecureRandom random = new SecureRandom();

	DataKeyBootstrap(WrappedKeyStore store, VaultTransitClient vault) {
		this.store = store;
		this.vault = vault;
	}

	@PostConstruct
	void ensureActiveKey() {
		try {
			LOG.info("crypto_data_key_present kid={}", store.activeKid());
			return;
		}
		catch (CryptoException missing) {
			if (!"KEY_NOT_CONFIGURED".equals(missing.code())) {
				throw missing;
			}
		}

		byte[] dek = new byte[KEY_BYTES];
		random.nextBytes(dek);
		try {
			String wrapped = vault.encrypt(dek);
			String kid = "dek-" + Instant.now().toEpochMilli() + "-" + HexFormat.of().toHexDigits(random.nextInt());
			store.save(kid, wrapped);
			LOG.info("crypto_data_key_created kid={}", kid);
		}
		finally {
			// A chave em claro vive o menor tempo possível nesta pilha.
			Arrays.fill(dek, (byte) 0);
		}
	}
}
