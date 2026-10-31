package com.socialcoisa.rede.feed.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.socialcoisa.rede.feed.domain.AuthorIdentity;
import com.socialcoisa.rede.feed.domain.Counters;
import com.socialcoisa.rede.feed.domain.Post;
import com.socialcoisa.rede.feed.domain.PostType;
import com.socialcoisa.rede.feed.domain.ReactionType;
import com.socialcoisa.rede.profile.application.ContributorTagView;
import com.socialcoisa.rede.profile.application.NameStyleView;

public record PostView(String id, String authorId, String authorUsername, String authorName, String authorAvatar,
		String authorBadge, List<String> authorBadges,
		PostType type, String content, String pageHtml, String articleBody, List<String> tags, List<String> mediaIds,
		Instant createdAt,
		Counters counters, ReactionType currentUserReaction, String communityId, CommunityView community,
		Instant editedAt,
		/** Prazo do Momento (null = post normal). O front mostra "some em Xh". */
		Instant expiresAt,
		/** Quem assinou: a apresentação (nome/@/avatar) acima vem da identidade dona do post. */
		AuthorIdentity authorIdentity,
		/** Moldura do avatar do autor (cosmético); nulo = sem moldura. */
		String authorAvatarFrame,
		/** Assunto do post (taxonomia da plataforma) ou null quando o autor não classificou. */
		String topic,
		/** Post original de uma republicação (null = post original). */
		String repostOf,
		/** @ do autor original, para o crédito no card. */
		String repostOfAuthor,
		/** Repost com opinião: o content é o comentário e o original aparece citado. */
		boolean repostWithOpinion,
		/** Conteúdo do original, preenchido na leitura dos reposts com opinião. */
		String repostOfContent,
		/** Mídias do original, preenchidas na leitura dos reposts com opinião. */
		List<String> repostOfMediaIds,
		/**
		 * Reaction counts per type ({"LIKE": 3}). Filled by the feed/post reads (one batched query
		 * per page) so the card does not fetch it per post; null where the read does not compute it
		 * (publish/edit responses).
		 */
		Map<ReactionType, Long> reactionSummary, boolean mediaSpoiler,
		/** Personalizacao do nome do autor (tema/fonte/cor); nulo = padrao. */
		NameStyleView authorStyle,
		/** Tag "Contribuinte" quando o autor tem selo ativo; nulo = nao e contribuinte. */
		ContributorTagView authorContributor,
		/** Notas de rodape aprovadas (verificacao da comunidade); vazio quando nao ha nenhuma. */
		List<PostNoteView> communityNotes,
		/** Nota do proprio leitor neste post, em qualquer estado; nulo quando ele nao enviou. */
		MyPostNoteView myNote,
		/** QUESTION only: the comment the asker accepted as the answer; null = none. */
		String acceptedCommentId,
		/**
		 * POLL only: alternativas, contagem e o voto de quem le; nulo nos demais tipos. Anexado em
		 * lote pelas leituras (uma consulta por pagina), nunca por um fetch do card.
		 */
		PollView poll) {

	/** The question has an accepted answer (derived, so it can never disagree with the id). */
	@JsonProperty
	public boolean resolved() {
		return acceptedCommentId != null;
	}

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
			null, post.communityId(), null, post.editedAt(), post.expiresAt(), post.authorIdentity(), null,
			post.topic(), post.repostOf(), post.repostOfAuthor(), post.repostWithOpinion(), null, List.of(),
			null, post.mediaSpoiler(), null, null, List.of(), null, post.acceptedCommentId(), null);
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
		return from(post, authorUsername, authorName, authorAvatar, authorBadges, post.authorIdentity());
	}

	/** A apresentação vem da identidade que assinou (social ou profissional). */
	public static PostView from(Post post, String authorUsername, String authorName, String authorAvatar,
			List<String> authorBadges, AuthorIdentity authorIdentity) {
		List<String> badges = authorBadges == null ? List.of() : List.copyOf(authorBadges);
		return new PostView(post.id().value(), post.authorId().value(), authorUsername, authorName, authorAvatar,
			badges.isEmpty() ? null : badges.get(0), badges, post.type(), post.content(), null, null, post.tags(),
			post.mediaIds(), post.createdAt(), post.counters(), null, post.communityId(), null, post.editedAt(),
			post.expiresAt(), authorIdentity, null, post.topic(), post.repostOf(), post.repostOfAuthor(),
			post.repostWithOpinion(), null, List.of(), null, post.mediaSpoiler(), null, null, List.of(), null, post.acceptedCommentId(), null);
	}

	/** Anexa a moldura do avatar do autor (cosmético) depois do enriquecimento do perfil. */
	public PostView withAvatarFrame(String frame) {
		return new PostView(id, authorId, authorUsername, authorName, authorAvatar, authorBadge, authorBadges, type,
			content, pageHtml, articleBody, tags, mediaIds, createdAt, counters, currentUserReaction, communityId,
			community, editedAt, expiresAt, authorIdentity, frame, topic, repostOf, repostOfAuthor, repostWithOpinion, repostOfContent, repostOfMediaIds,
			reactionSummary, mediaSpoiler, authorStyle, authorContributor, communityNotes, myNote, acceptedCommentId, poll);
	}

	public PostView withCurrentUserReaction(ReactionType reaction) {
		return new PostView(id, authorId, authorUsername, authorName, authorAvatar, authorBadge, authorBadges, type,
			content, pageHtml, articleBody, tags, mediaIds, createdAt, counters, reaction, communityId, community,
			editedAt, expiresAt, authorIdentity, authorAvatarFrame, topic, repostOf, repostOfAuthor, repostWithOpinion, repostOfContent, repostOfMediaIds,
			reactionSummary, mediaSpoiler, authorStyle, authorContributor, communityNotes, myNote, acceptedCommentId, poll);
	}

	/** Anexa o HTML da pagina; so o post unico faz isso (ver {@link #from(Post)}). */
	public PostView withPageHtml(String pageHtml) {
		return new PostView(id, authorId, authorUsername, authorName, authorAvatar, authorBadge, authorBadges, type,
			content, pageHtml, articleBody, tags, mediaIds, createdAt, counters, currentUserReaction, communityId,
			community, editedAt, expiresAt, authorIdentity, authorAvatarFrame, topic, repostOf, repostOfAuthor, repostWithOpinion, repostOfContent, repostOfMediaIds,
			reactionSummary, mediaSpoiler, authorStyle, authorContributor, communityNotes, myNote, acceptedCommentId, poll);
	}

	/** Anexa a comunidade (nome + participacao do leitor) depois do enriquecimento. */
	public PostView withCommunity(String communityId, CommunityView community) {
		return new PostView(id, authorId, authorUsername, authorName, authorAvatar, authorBadge, authorBadges, type,
			content, pageHtml, articleBody, tags, mediaIds, createdAt, counters, currentUserReaction, communityId,
			community, editedAt, expiresAt, authorIdentity, authorAvatarFrame, topic, repostOf, repostOfAuthor, repostWithOpinion, repostOfContent, repostOfMediaIds,
			reactionSummary, mediaSpoiler, authorStyle, authorContributor, communityNotes, myNote, acceptedCommentId, poll);
	}

	/** Anexa a citação do original (só reposts com opinião precisam; a listagem busca em lote). */
	public PostView withRepostQuote(String originalContent, List<String> originalMediaIds) {
		return new PostView(id, authorId, authorUsername, authorName, authorAvatar, authorBadge, authorBadges, type,
			content, pageHtml, articleBody, tags, mediaIds, createdAt, counters, currentUserReaction, communityId,
			community, editedAt, expiresAt, authorIdentity, authorAvatarFrame, topic, repostOf, repostOfAuthor,
			repostWithOpinion, originalContent, originalMediaIds == null ? List.of() : originalMediaIds,
			reactionSummary, mediaSpoiler, authorStyle, authorContributor, communityNotes, myNote, acceptedCommentId, poll);
	}

	public PostView withReactionSummary(Map<ReactionType, Long> summary) {
		return new PostView(id, authorId, authorUsername, authorName, authorAvatar, authorBadge, authorBadges, type,
			content, pageHtml, articleBody, tags, mediaIds, createdAt, counters, currentUserReaction, communityId,
			community, editedAt, expiresAt, authorIdentity, authorAvatarFrame, topic, repostOf, repostOfAuthor,
			repostWithOpinion, repostOfContent, repostOfMediaIds, summary, mediaSpoiler, authorStyle, authorContributor, communityNotes, myNote, acceptedCommentId, poll);
	}

	/** Anexa o corpo do artigo; so a leitura do post unico faz isso (mesma regra da pagina). */
	/** Anexa a personalizacao do nome do autor (cosmetico) depois do enriquecimento do perfil. */
	public PostView withAuthorStyle(NameStyleView style) {
		return new PostView(id, authorId, authorUsername, authorName, authorAvatar, authorBadge, authorBadges, type,
			content, pageHtml, articleBody, tags, mediaIds, createdAt, counters, currentUserReaction, communityId,
			community, editedAt, expiresAt, authorIdentity, authorAvatarFrame, topic, repostOf, repostOfAuthor,
			repostWithOpinion, repostOfContent, repostOfMediaIds, reactionSummary, mediaSpoiler, style,
			authorContributor, communityNotes, myNote, acceptedCommentId, poll);
	}

	/** Anexa a tag de contribuinte do autor (cosmetica) depois de conferir os selos ativos. */
	public PostView withContributor(ContributorTagView tag) {
		return new PostView(id, authorId, authorUsername, authorName, authorAvatar, authorBadge, authorBadges, type,
			content, pageHtml, articleBody, tags, mediaIds, createdAt, counters, currentUserReaction, communityId,
			community, editedAt, expiresAt, authorIdentity, authorAvatarFrame, topic, repostOf, repostOfAuthor,
			repostWithOpinion, repostOfContent, repostOfMediaIds, reactionSummary, mediaSpoiler, authorStyle, tag,
			communityNotes, myNote, acceptedCommentId, poll);
	}

	/** Anexa as notas de rodape aprovadas e a nota do leitor (cosmetico; lote por pagina). */
	public PostView withNotes(List<PostNoteView> notes, MyPostNoteView mine) {
		return new PostView(id, authorId, authorUsername, authorName, authorAvatar, authorBadge, authorBadges, type,
			content, pageHtml, articleBody, tags, mediaIds, createdAt, counters, currentUserReaction, communityId,
			community, editedAt, expiresAt, authorIdentity, authorAvatarFrame, topic, repostOf, repostOfAuthor,
			repostWithOpinion, repostOfContent, repostOfMediaIds, reactionSummary, mediaSpoiler, authorStyle,
			authorContributor, notes == null ? List.of() : List.copyOf(notes), mine, acceptedCommentId, poll);
	}

	/** Anexa o estado da enquete (lote por pagina nas leituras; resposta direta no voto). */
	public PostView withPoll(PollView poll) {
		return new PostView(id, authorId, authorUsername, authorName, authorAvatar, authorBadge, authorBadges, type,
			content, pageHtml, articleBody, tags, mediaIds, createdAt, counters, currentUserReaction, communityId,
			community, editedAt, expiresAt, authorIdentity, authorAvatarFrame, topic, repostOf, repostOfAuthor,
			repostWithOpinion, repostOfContent, repostOfMediaIds, reactionSummary, mediaSpoiler, authorStyle,
			authorContributor, communityNotes, myNote, acceptedCommentId, poll);
	}

	public PostView withArticleBody(String body) {
		return new PostView(id, authorId, authorUsername, authorName, authorAvatar, authorBadge, authorBadges, type,
			content, pageHtml, body, tags, mediaIds, createdAt, counters, currentUserReaction, communityId, community,
			editedAt, expiresAt, authorIdentity, authorAvatarFrame, topic, repostOf, repostOfAuthor, repostWithOpinion, repostOfContent, repostOfMediaIds,
			reactionSummary, mediaSpoiler, authorStyle, authorContributor, communityNotes, myNote, acceptedCommentId, poll);
	}
}
