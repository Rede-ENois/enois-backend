package com.socialcoisa.rede.desafios.application.port.in;

/**
 * O que a moderação pode fazer com conteúdo de Desafios. Implementado por
 * {@code DesafiosService}; a denúncia confirmada chega por aqui, do mesmo jeito que chega ao
 * Confessionário por {@code ConfessionarioModerationPort}.
 */
public interface DesafiosModerationPort {

	/** Remove o desafio e as participações dele. {@code false} quando o id não está mais no ar. */
	boolean removeDesafioAsModerator(String actorId, String desafioId, String reason);

	/** Remove uma participação. {@code false} quando já saiu do ar. */
	boolean removeParticipacaoAsModerator(String actorId, String participacaoId, String reason);
}
