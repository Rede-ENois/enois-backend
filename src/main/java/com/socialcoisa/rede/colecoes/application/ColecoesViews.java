package com.socialcoisa.rede.colecoes.application;

import java.time.Instant;
import java.util.List;

import com.socialcoisa.rede.feed.application.PostView;

/** Views das Coleções pessoais. Autor do post vem no corpo do próprio {@link PostView}. */
public final class ColecoesViews {

	private ColecoesViews() {
	}

	/** Resumo da coleção: o dono já sabe quem é; a contagem é o que a página precisa. */
	public record ColecaoResumo(String id, String title, String description, long postCount, Instant createdAt) {
	}

	public record ColecoesPage(List<ColecaoResumo> items, String nextCursor) {
	}

	/** Um post guardado: o elo com a marca de quando entrou na coleção. */
	public record PostSalvoView(String postId, PostView post, Instant adicionadoAt) {
	}

	public record ColecaoPostsPage(List<PostSalvoView> items, String nextCursor) {
	}
}
