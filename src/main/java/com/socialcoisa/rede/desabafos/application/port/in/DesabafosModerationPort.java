package com.socialcoisa.rede.desabafos.application.port.in;

/**
 * O que a moderação pode fazer com conteúdo dos Desabafos. Implementado por
 * {@code DesabafosService}; a denúncia confirmada chega por aqui, do mesmo jeito que chega ao
 * feed por {@code ModerationRemovePostUseCase} e à Colagem por {@code ColagemModerationPort}.
 */
public interface DesabafosModerationPort {

	/** Remove o desabafo e os comentários dele. {@code false} quando o id não está mais no ar. */
	boolean removePostAsModerator(String actorId, String postId, String reason);

	/** Remove um comentário. {@code false} quando já saiu do ar. */
	boolean removeCommentAsModerator(String actorId, String commentId, String reason);
}
