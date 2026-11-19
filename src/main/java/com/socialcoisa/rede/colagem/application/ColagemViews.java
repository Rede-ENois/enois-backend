package com.socialcoisa.rede.colagem.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.socialcoisa.rede.profile.application.NameStyleView;

/** Views HTTP da Colagem. A mídia vem resolvida (url/dimensões) do módulo de mídia. */
public final class ColagemViews {

	public record ColagemAuthor(String id, String name, String username, String avatar, String avatarFrame,
			NameStyleView style) {
	}

	public record ColagemPostView(String id, ColagemAuthor author, String mediaId, String mediaUrl, String mimeType,
			Integer width, Integer height, String description, boolean mediaSpoiler, Instant createdAt,
			long reactions, Map<String, Long> reactionSummary, String myReaction, long comments) {
	}

	public record ColagemCommentView(String id, String postId, ColagemAuthor author, String parentCommentId,
			String content, String mediaId, String mediaUrl, Integer width, Integer height, boolean mediaSpoiler,
			Instant createdAt) {
	}

	public record ColagemPage(List<ColagemPostView> items, String nextCursor) {
	}

	public record ColagemCommentsPage(List<ColagemCommentView> items, String nextCursor) {
	}

	private ColagemViews() {
	}
}
