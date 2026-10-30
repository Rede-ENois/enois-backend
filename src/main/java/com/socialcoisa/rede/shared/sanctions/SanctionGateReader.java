package com.socialcoisa.rede.shared.sanctions;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Leitura de sancao ativa para enforcement (publicar, comentar, logar).
 *
 * Fica em `shared` pelo mesmo motivo do {@code BlockedUsersReader}: feed e identidade precisam da
 * mesma resposta sem depender do modulo de moderacao, que e o dono da escrita.
 */
public interface SanctionGateReader {

	/** Conta com sancao ativa (restricao ou ban): nao publica nem comenta. */
	boolean restricted(String userId);

	/** Conta banida (recurso negado): nem loga. */
	boolean banned(String userId);

	/** The banned among these accounts, for a list (the adapter answers in one query). */
	default Set<String> bannedAmong(Collection<String> userIds) {
		return userIds.stream().filter(this::banned).collect(Collectors.toSet());
	}

	/** Motivo da sancao ativa, para a mensagem ao usuario. Vazio quando livre. */
	Optional<String> activeReason(String userId);
}
