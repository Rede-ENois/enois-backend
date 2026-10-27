package com.socialcoisa.rede.shared.crypto;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Chaves de dados embrulhadas pelo cofre, guardadas no banco.
 *
 * <p>A tabela {@code data_keys} guarda só material já cifrado pela chave-mestra do cofre. Este
 * store não decifra nada: entrega o embrulho para o {@link VaultTransitClient}, que fala com o
 * cofre. Por isso ele pode viver no banco sem que deem o banco por comprometido.</p>
 *
 * <p>Não é {@code @Component}: só entra no ar com a cifra em repouso ligada
 * ({@code rede.crypto.at-rest.enabled}), porque sem cofre configurado ele não teria o que abrir.</p>
 */
public final class JdbcWrappedKeyStore implements WrappedKeyStore {

	private final JdbcTemplate jdbc;

	public JdbcWrappedKeyStore(JdbcTemplate jdbc) {
		if (jdbc == null) {
			throw new CryptoException("KEYSTORE_INVALID", "JdbcTemplate obrigatorio.");
		}
		this.jdbc = jdbc;
	}

	/**
	 * Chave que cifra registros novos: a mais recente ainda ativa.
	 *
	 * <p>Sem chave ativa é erro explícito — cifrar com uma chave que ninguém sabe de onde veio é
	 * pior do que falhar. O bootstrap cria a primeira.</p>
	 */
	@Override
	public String activeKid() {
		List<String> kids = jdbc.queryForList(
			"SELECT kid FROM data_keys WHERE retired_at IS NULL ORDER BY created_at DESC, kid DESC LIMIT 1",
			String.class);
		if (kids.isEmpty()) {
			throw new CryptoException("KEY_NOT_CONFIGURED",
				"Nenhuma chave de dados ativa. Rode o bootstrap do cofre antes de cifrar.");
		}
		return kids.get(0);
	}

	@Override
	public Optional<String> wrappedByKid(String kid) {
		if (kid == null || kid.isBlank()) {
			return Optional.empty();
		}
		return jdbc.queryForList("SELECT wrapped_key FROM data_keys WHERE kid = ?", String.class, kid)
			.stream()
			.findFirst();
	}

	/** Registra uma chave recém-embrulhada pelo cofre. */
	public void save(String kid, String wrappedKey) {
		if (kid == null || kid.isBlank() || wrappedKey == null || wrappedKey.isBlank()) {
			throw new CryptoException("KEYSTORE_INVALID", "kid e chave embrulhada sao obrigatorios.");
		}
		jdbc.update("INSERT INTO data_keys (kid, wrapped_key) VALUES (?, ?)", kid, wrappedKey);
	}

	/**
	 * Tira a chave de circulação sem apagar a linha: os registros antigos continuam abrindo, e os
	 * novos passam a usar a próxima ativa.
	 */
	public void retire(String kid) {
		jdbc.update("UPDATE data_keys SET retired_at = CURRENT_TIMESTAMP(6) WHERE kid = ? AND retired_at IS NULL",
			kid);
	}
}
