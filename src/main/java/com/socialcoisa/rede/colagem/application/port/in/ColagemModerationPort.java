package com.socialcoisa.rede.colagem.application.port.in;

/**
 * O que a moderação pode fazer com conteúdo da Colagem. Implementado por {@code ColagemService};
 * a denúncia confirmada e o takedown de mídia sensível chegam por aqui, do mesmo jeito que chegam
 * ao feed por {@code ModerationRemovePostUseCase}.
 */
public interface ColagemModerationPort {

	/** Remove a foto e os comentários dela. {@code false} quando o id não está mais no ar. */
	boolean removePostAsModerator(String actorId, String postId, String reason);

	/** Remove um comentário (texto ou foto). {@code false} quando já saiu do ar. */
	boolean removeCommentAsModerator(String actorId, String commentId, String reason);

	/**
	 * Takedown de mídia: derruba TODAS as fotos que usam a mídia (com os comentários) e os
	 * comentários fotográficos que a carregam. Devolve quantos itens saíram do ar.
	 */
	int removeByMedia(String mediaId, String reason);
}
