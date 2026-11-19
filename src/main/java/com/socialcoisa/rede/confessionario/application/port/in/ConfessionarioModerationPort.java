package com.socialcoisa.rede.confessionario.application.port.in;

/**
 * O que a moderação pode fazer com conteúdo do Confessionário. Implementado por
 * {@code ConfessionarioService}; a denúncia confirmada chega por aqui, do mesmo jeito que
 * chega aos Desabafos por {@code DesabafosModerationPort} e à Colagem por
 * {@code ColagemModerationPort}.
 */
public interface ConfessionarioModerationPort {

	/** Remove a confissão e os comentários dela. {@code false} quando o id não está mais no ar. */
	boolean removePostAsModerator(String actorId, String postId, String reason);

	/** Remove um comentário. {@code false} quando já saiu do ar. */
	boolean removeCommentAsModerator(String actorId, String commentId, String reason);
}
