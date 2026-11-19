package com.socialcoisa.rede.murais.application;

import java.time.Instant;
import java.util.List;

import com.socialcoisa.rede.feed.application.PostView;
import com.socialcoisa.rede.profile.application.NameStyleView;

/** Views HTTP dos Murais. A publicação cola vem inteira do feed (via GetPostsUseCase). */
public final class MuraisViews {

	public record MuralAutor(String id, String name, String username, String avatar, String avatarFrame,
			NameStyleView style) {
	}

	public record MuralResumo(String id, String title, String description, MuralAutor owner, long contributions,
			Instant createdAt) {
	}

	public record ContribuicaoView(String postId, PostView post, MuralAutor addedBy, Instant addedAt) {
	}

	public record MuraisPage(List<MuralResumo> items, String nextCursor) {
	}

	/**
	 * Um mural do dono na ótica de um post: {@code tem} diz se o post já está colado — o seletor
	 * do card marca o quadro e nem tenta a cola duplicada. Igual ao {@link MuralResumo}, menos
	 * o dono (é quem pergunta) e com a flag.
	 */
	public record MuralDoPost(String id, String title, String description, long contributions, boolean tem,
			Instant createdAt) {
	}

	public record MeusMuraisPage(List<MuralDoPost> items) {
	}

	public record ContribuicoesPage(List<ContribuicaoView> items, String nextCursor) {
	}

	private MuraisViews() {
	}
}
