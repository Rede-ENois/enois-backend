package com.socialcoisa.rede.identity.application.port.out;

import java.util.Optional;

import com.socialcoisa.rede.identity.domain.IdentityVerificationStatus;

/**
 * Provedor de verificacao de identidade. A regra de negocio nao conhece fornecedor: liga-se a esta
 * porta. Em producao, sem provedor autorizado configurado, nenhum provedor esta disponivel e a
 * verificacao fica indisponivel — nunca aprovada por simulacao.
 */
public interface IdentityVerificationProvider {

	/** Se o provedor esta realmente configurado (credenciais presentes). */
	default boolean available() {
		return true;
	}

	String name();

	/**
	 * Se este provedor so aceita quem foi liberado pela rodada progressiva. Provedor publico
	 * (gov.br) responde falso; provedor contratado responde verdadeiro, para que a liberacao seja
	 * conferida antes de qualquer chamada externa.
	 */
	default boolean requiresRelease() {
		return false;
	}

	/** Inicia uma verificacao e devolve a tentativa e o destino para o usuario concluir no provedor. */
	Start start(String userId, String consentVersion);

	/** Consulta o desfecho confirmado pelo provedor para uma tentativa (nao confia no navegador). */
	Optional<Outcome> fetchOutcome(String attemptId);

	/**
	 * Provedores com retorno por callback (OAuth/OIDC) trocam o codigo no backend e devolvem o
	 * desfecho. O `attemptId` aqui e o `state` de uso unico gravado no inicio. Padrao: vazio.
	 */
	default Optional<Outcome> handleCallback(String attemptId, String code) {
		return Optional.empty();
	}

	/** Cancela/limpa uma tentativa pendente, quando suportado. */
	default void cancel(String attemptId) {
	}

	record Start(String attemptId, String redirectUrl) {
	}

	record Outcome(IdentityVerificationStatus status, String reasonCode) {
	}
}
