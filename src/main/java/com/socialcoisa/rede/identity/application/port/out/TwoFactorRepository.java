package com.socialcoisa.rede.identity.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.identity.domain.TwoFactorChallenge;

/**
 * Persistencia do segundo fator: desafios do login, segredos do TOTP (cifrados) e codigos de
 * recuperacao. Os segredos nao passam pelo dominio de perfil de proposito.
 */
public interface TwoFactorRepository {

	/** Inserts a new challenge. Existing challenges change only through the conditional updates below. */
	void saveChallenge(TwoFactorChallenge challenge);

	/**
	 * New code for a challenge that is still open (attempts reset). False when it was consumed in
	 * the meantime: a resend must never reopen a completed login.
	 */
	boolean replaceCode(String challengeId, String codeHash, Instant expiresAt);

	/**
	 * Atomically claims one attempt before the code is checked, in its own transaction: a wrong code
	 * fails and rolls the request back, and the attempt must survive that. False when the challenge
	 * is consumed, gone or out of attempts, so parallel requests cannot share one read of the counter.
	 * The reason is read inside the same new transaction, so it reflects what made the claim fail
	 * (an outer REPEATABLE READ snapshot could still show the challenge as open).
	 */
	AttemptClaim claimAttempt(String challengeId);

	enum AttemptClaim { CLAIMED, CONSUMED, OUT_OF_ATTEMPTS }

	/**
	 * Marks the challenge consumed in the caller's transaction; true only for the one request that
	 * flips it, so two parallel correct codes cannot both complete.
	 */
	boolean consumeChallenge(String challengeId, Instant now);

	/** Marks the TOTP step as used; false when this step (or a later one) was already accepted. */
	boolean markTotpStepUsed(String userId, long step);

	Optional<TwoFactorChallenge> findChallenge(String id);

	/** Desafio mais recente do usuario por metodo (usado ao confirmar a ativacao por e-mail). */
	Optional<TwoFactorChallenge> findLatestChallenge(String userId, com.socialcoisa.rede.identity.domain.TwoFactorMethod method);

	/** Desafios vencidos sao descartaveis: nao precisam ficar no banco. */
	int deleteExpiredChallenges(Instant now);

	/** Segredo pendente (gerado no setup e ainda nao confirmado por um codigo). */
	void savePendingSecret(String userId, String encryptedSecret, Instant now);

	Optional<String> findPendingSecret(String userId);

	Optional<Instant> findPendingSecretCreatedAt(String userId);

	/** Ativa o TOTP: o segredo pendente passa a ser o valido. */
	void activateTotpSecret(String userId, String encryptedSecret, Instant now);

	/** Segredo ativo (cifrado) para validar um codigo no login. */
	Optional<String> findActiveSecret(String userId);

	void clearSecrets(String userId);

	void saveBackupCodes(String userId, java.util.List<String> codeHashes, Instant now);

	int countUnusedBackupCodes(String userId);

	/** Consome o codigo de recuperacao: uso unico. Devolve false se nao existir/ja foi usado. */
	boolean consumeBackupCode(String userId, String codeHash, Instant now);

	void deleteBackupCodes(String userId);
}