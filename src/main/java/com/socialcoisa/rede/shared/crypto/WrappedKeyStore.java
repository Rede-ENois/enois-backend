package com.socialcoisa.rede.shared.crypto;

import java.util.Optional;

/**
 * Onde as DEKs embrulhadas vivem (tabela de chaves de dados, no banco).
 *
 * <p>O banco guarda o <strong>ciphertext do cofre</strong>, nunca a chave. Perder esta tabela sem
 * backup = perder a capacidade de decifrar; por isso ela entra no backup com a mesma prioridade do
 * restante do dado cifrado.</p>
 */
public interface WrappedKeyStore {

	/** Identificador da chave que cifra registros novos. */
	String activeKid();

	/** Ciphertext do cofre para o identificador, se existir. */
	Optional<String> wrappedByKid(String kid);
}
