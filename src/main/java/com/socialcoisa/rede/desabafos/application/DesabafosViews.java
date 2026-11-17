package com.socialcoisa.rede.desabafos.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.socialcoisa.rede.profile.application.NameStyleView;

/**
 * Views HTTP dos Desabafos. A reação é contada, nunca nominada: o resumo traz só os totais por
 * tipo — quem apoiou fica com o apoio, sem lista de pessoas.
 */
public final class DesabafosViews {

	public record DesabafosAuthor(String id, String name, String username, String avatar, String avatarFrame,
			NameStyleView style) {
	}

	public record DesabafoPostView(String id, DesabafosAuthor author, String content, Instant createdAt,
			long reactions, Map<String, Long> reactionSummary, String myReaction, long comments) {
	}

	public record DesabafoCommentView(String id, String postId, DesabafosAuthor author, String content,
			Instant createdAt) {
	}

	public record DesabafosPage(List<DesabafoPostView> items, String nextCursor) {
	}

	public record DesabafosCommentsPage(List<DesabafoCommentView> items) {
	}

	private DesabafosViews() {
	}
}
