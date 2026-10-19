package com.socialcoisa.rede.feed.application;

import java.time.Instant;
import java.util.List;

import com.socialcoisa.rede.feed.domain.Counters;
import com.socialcoisa.rede.feed.domain.Post;
import com.socialcoisa.rede.feed.domain.PostType;
import com.socialcoisa.rede.feed.domain.ReactionType;

public record PostView(String id, String authorId, String authorUsername, String authorName, String authorAvatar,
		String authorBadge, List<String> authorBadges,
		PostType type, String content, String pageHtml, String articleBody, List<String> tags, List<String> mediaIds,
		Instant createdAt,
		Counters counters, ReactionType currentUserReaction, String communityId, CommunityView community,
		Instant editedAt,
		/** Prazo do Momento (null = post normal). O front mostra "some em Xh". */
		Instant expiresAt, String authorAvatarFrame) {

	/** Comunidade onde o post foi publicado (nome para o texto "publicou em ...") + se quem le participa. */
	public record CommunityView(String id, String name, boolean joined) {
	}

	/**
	 * Post sem o corpo da pagina: e o formato de listagem, de feed e das respostas de publicacao e
	 * edicao. A pagina de um post do tipo PAGE pode ser grande (dezenas de milhares de caracteres)
	 * e nao tem por que viajar em toda listagem — quem precisa dela pede o post unico, que anexa o
	 * HTML via {@link #withPageHtml(String)}.
	 *
	 * Ha um motivo concreto alem do tamanho: a camada de idempotencia guarda o corpo da resposta
	 * das escritas, e um HTML grande nao cabe nesse registro (a escrita chega a falhar depois de o
	 * post ja ter sido gravado). Devolver so o essencial mantem essas respostas pequenas.
	 */
	public static PostView from(Post post) {
		return new PostView(post.id().value(), post.authorId().value(), null, null, null, null, List.of(),
			post.type(), post.content(), null, null, post.tags(), post.mediaIds(), post.createdAt(), post.counters(),
			null, post.communityId(), null, post.editedAt(), post.expiresAt(), null);
	}

	public static PostView from(Post post, String authorUsername, String authorName, String authorAvatar) {
		return from(post, authorUsername, authorName, authorAvatar, List.of());
	}

	/**
	 * Com TODOS os emblemas do autor (até 5 PNGs junto ao nome, onde ele aparecer): o perfil mostra
	 * a lista inteira, e o resto do app passa a mostrar a mesma coisa.
	 *
	 * `authorBadge` continua sendo o primeiro — é o campo que uma versão antiga do app lê.
	 */
	public static PostView from(Post post, String authorUsername, String authorName, String authorAvatar,
			List<String> authorBadges) {
		List<String> badges = authorBadges == null ? List.of() : List.copyOf(authorBadges);
		return new PostView(post.id().value(), post.authorId().value(), authorUsername, authorName, authorAvatar,
			badges.isEmpty() ? null : badges.get(0), badges, post.type(), post.content(), null, null, post.tags(),
			post.mediaIds(), post.createdAt(), post.counters(), null, post.communityId(), null, post.editedAt(),
			post.expiresAt(), null);
	}

	public PostView withAvatarFrame(String frame) {
		return new PostView(id, authorId, authorUsername, authorName, authorAvatar, authorBadge, authorBadges, type,
			content, pageHtml, articleBody, tags, mediaIds, createdAt, counters, currentUserReaction, communityId,
			community, editedAt, expiresAt, frame);
	}

	public PostView withCurrentUserReaction(ReactionType reaction) {
		return new PostView(id, authorId, authorUsername, authorName, authorAvatar, authorBadge, authorBadges, type,
			content, pageHtml, articleBody, tags, mediaIds, createdAt, counters, reaction, communityId, community,
			editedAt, expiresAt, authorAvatarFrame);
	}

	/** Anexa o HTML da pagina; so o post unico faz isso (ver {@link #from(Post)}). */
	public PostView withPageHtml(String pageHtml) {
		return new PostView(id, authorId, authorUsername, authorName, authorAvatar, authorBadge, authorBadges, type,
			content, pageHtml, articleBody, tags, mediaIds, createdAt, counters, currentUserReaction, communityId,
			community, editedAt, expiresAt, authorAvatarFrame);
	}

	/** Anexa a comunidade (nome + participacao do leitor) depois do enriquecimento. */
	public PostView withCommunity(String communityId, CommunityView community) {
		return new PostView(id, authorId, authorUsername, authorName, authorAvatar, authorBadge, authorBadges, type,
			content, pageHtml, articleBody, tags, mediaIds, createdAt, counters, currentUserReaction, communityId,
			community, editedAt, expiresAt, authorAvatarFrame);
	}

	/** Anexa o corpo do artigo; so a leitura do post unico faz isso (mesma regra da pagina). */
	public PostView withArticleBody(String body) {
		return new PostView(id, authorId, authorUsername, authorName, authorAvatar, authorBadge, authorBadges, type,
			content, pageHtml, body, tags, mediaIds, createdAt, counters, currentUserReaction, communityId, community,
			editedAt, expiresAt, authorAvatarFrame);
	}
}
