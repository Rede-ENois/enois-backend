package com.socialcoisa.rede.feed.application.port.in;

/**
 * Remocao de comentario por moderacao, sem exigir que quem remove seja o autor.
 */
public interface ModerationRemoveCommentUseCase {

	void removeAsModerator(String moderatorId, String commentId, String reason);
}
