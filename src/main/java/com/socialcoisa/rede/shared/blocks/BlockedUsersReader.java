package com.socialcoisa.rede.shared.blocks;

import java.util.Collections;
import java.util.Set;

/**
 * Leitura dos usuarios escondidos para um visitante (bloqueio nos dois sentidos).
 *
 * Fica em `shared` porque feed, notificacoes, busca e rede precisam da mesma resposta; a escrita
 * (bloquear/desbloquear) continua no modulo de rede, que e dono do assunto.
 */
public interface BlockedUsersReader {

	/** Ids que nao devem aparecer para este usuario. Vazio quando nao ha sessao nem bloqueios. */
	Set<String> hiddenIdsFor(String userId);

	/** A block between the two, in either direction (mutes do not count). */
	boolean blockedBetween(String userA, String userB);

	/**
	 * Everyone with a block against this user, in either direction (mutes do not count). One read
	 * answers "can this person reach any of these people?" without a lookup per recipient.
	 */
	Set<String> blockedIdsFor(String userId);

	/** Implementacao neutra para quem precisa do tipo sem depender do modulo de rede. */
	static BlockedUsersReader none() {
		return new BlockedUsersReader() {
			@Override
			public Set<String> hiddenIdsFor(String userId) {
				return Collections.emptySet();
			}

			@Override
			public boolean blockedBetween(String userA, String userB) {
				return false;
			}

			@Override
			public Set<String> blockedIdsFor(String userId) {
				return Collections.emptySet();
			}
		};
	}
}
