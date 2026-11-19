package com.socialcoisa.rede.network.application.port.out;

import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.network.domain.S3Credential;

/**
 * Credenciais do gateway S3 (brief §11, V357).
 *
 * <p>{@link #secretFor(String)} é o ponto sensível: o SigV4 exige o segredo em claro para recalcular
 * o HMAC. Ver o comentário da migração V357 — a lacuna é declarada, não escondida.</p>
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

	/** A credencial pelo id, <b>do dono informado</b>: credencial de outra conta não é encontrada. */
	Optional<S3Credential> findForOwner(String ownerUserId, String id);

	/**
	 * Revogação é terminal: a credencial deixa de autenticar e não é reativada.
	 *
	 * <p>O dono vai no `where` junto com o id: quem chama não precisa listar antes para decidir, e
	 * não existe janela entre listar e alterar em que a credencial mude de dono. Devolve {@code false}
	 * quando a credencial não é do dono ou já estava revogada — os dois casos são "nada a fazer".</p>
	 */
	boolean revoke(String id, String ownerUserId, java.time.Instant now);
}