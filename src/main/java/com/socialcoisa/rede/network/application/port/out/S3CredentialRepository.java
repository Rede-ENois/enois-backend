package com.socialcoisa.rede.network.application.port.out;

import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.network.domain.S3Credential;

/**
 * Credenciais do gateway S3 (brief §11, V356).
 *
 * <p>{@link #secretFor(String)} é o ponto sensível: o SigV4 exige o segredo em claro para recalcular
 * o HMAC. Ver o comentário da migração V356 — a lacuna é declarada, não escondida.</p>
 */
public interface S3CredentialRepository {

	Optional<S3Credential> findByAccessKey(String accessKey);

	/** O segredo em claro da credencial, ou {@code null} quando não há um guardado. */
	String secretFor(String credentialId);

	List<S3Credential> findByOwner(String ownerUserId, int limit);

	/**
	 * Grava a credencial. O segredo em claro entra aqui só para ser cifrado pelo {@code SecretCipher};
	 * o que fica no banco é o hash (conferência) e o cifrado (SigV4). Claro nunca é persistido.
	 */
	S3Credential save(S3Credential credential, String secretPlain);

	/** Revogação é terminal: a credencial deixa de autenticar e não é reativada. */
	boolean revoke(String id, java.time.Instant now);
}