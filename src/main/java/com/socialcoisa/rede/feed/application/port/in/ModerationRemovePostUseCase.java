package com.socialcoisa.rede.feed.application.port.in;

/**
 * Remocao de post por moderacao, sem exigir que quem remove seja o autor.
 */
public interface ModerationRemovePostUseCase {

	void removeAsModerator(String moderatorId, String postId, String reason);
}