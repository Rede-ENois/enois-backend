package com.socialcoisa.rede.feed.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

public class Post {

	private final PostId id;
	private final UserId authorId;
	private final PostType type;
	private final String content;
	/** Mini pagina (apenas em posts do tipo PAGE); HTML ja sanitizado, nulo nos demais tipos. */
	private final String pageHtml;
	private final List<String> tags;
	private final List<String> mediaIds;
	private final boolean mediaSpoiler;
	private final String communityId;
	private final Instant createdAt;
	private final Visibility visibility;
	private Counters counters;
	private PostStatus status;
	private Instant deletedAt;
	private Instant editedAt;
	/** Quando o post se apaga sozinho (MOMENT: +24h); null = nunca. */
	private Instant expiresAt;
	/** Quem assina: a identidade social (padrão) ou a profissional da mesma conta. Nunca nulo. */
	private final AuthorIdentity authorIdentity;
	/** Assunto da publicação (taxonomia das comunidades); nulo = sem classificação. */
	private final String topic;
	/** Canal privado da comunidade onde o post nasceu; nulo = post normal (aparece nos feeds). */
	private final String channelId;
	/** Post original de uma republicação (nulo = post original). */
	private String repostOf;
	/** @ do autor original, para o crédito no card sem join na listagem. */
	private String repostOfAuthor;
	/** Repost COM opinião: o content é o comentário do reposter e o original aparece citado. */
	private boolean repostWithOpinion;

	private Post(PostId id, UserId authorId, PostType type, String content, String pageHtml, List<String> tags,
			List<String> mediaIds, String communityId, String topic, String channelId, Instant createdAt,
			Visibility visibility, Counters counters, PostStatus status, Instant deletedAt, Instant editedAt,
			Instant expiresAt, AuthorIdentity authorIdentity) {
		this(id, authorId, type, content, pageHtml, tags, mediaIds, communityId, topic, channelId, createdAt,
			visibility, counters, status, deletedAt, editedAt, expiresAt, authorIdentity, false);
	}

	private Post(PostId id, UserId authorId, PostType type, String content, String pageHtml, List<String> tags,
			List<String> mediaIds, String communityId, String topic, String channelId, Instant createdAt,
			Visibility visibility, Counters counters, PostStatus status, Instant deletedAt, Instant editedAt,
			Instant expiresAt, AuthorIdentity authorIdentity, boolean mediaSpoiler) {
		this.id = id;
		this.authorId = authorId;
		this.type = type;
		this.content = content;
		this.pageHtml = pageHtml;
		this.tags = List.copyOf(tags);
		this.mediaIds = List.copyOf(mediaIds);
		this.mediaSpoiler = mediaSpoiler;
		this.communityId = communityId;
		this.createdAt = createdAt;
		this.visibility = visibility;
		this.counters = counters;
		this.status = status;
		this.deletedAt = deletedAt;
		this.editedAt = editedAt;
		this.expiresAt = expiresAt;
		// Invariante: toda publicação tem uma identidade. Nada no banco nem no código cria post sem.
		this.authorIdentity = authorIdentity == null ? AuthorIdentity.SOCIAL : authorIdentity;
		this.topic = topic == null || topic.isBlank() ? null : topic;
		this.channelId = channelId == null || channelId.isBlank() ? null : channelId;
	}

	public static Post publish(PostId id, UserId authorId, PostType type, String content, String pageHtml,
			List<String> tags, List<String> mediaIds, String communityId, Instant createdAt) {
		return publish(id, authorId, type, content, pageHtml, tags, mediaIds, communityId, null, createdAt,
			AuthorIdentity.SOCIAL);
	}

	/** Publicação com a identidade explícita (BUSINESS é sempre CORPORATE; o service valida). */
	public static Post publish(PostId id, UserId authorId, PostType type, String content, String pageHtml,
			List<String> tags, List<String> mediaIds, String communityId, Instant createdAt,
			AuthorIdentity authorIdentity) {
		return publish(id, authorId, type, content, pageHtml, tags, mediaIds, communityId, null, createdAt,
			authorIdentity);
	}

	/**
	 * Publicação completa: identidade + assunto. O assunto é a classificação REAL da publicação
	 * (o tópico da comunidade em que nasce) — nunca uma heurística do texto.
	 */
	public static Post publish(PostId id, UserId authorId, PostType type, String content, String pageHtml,
			List<String> tags, List<String> mediaIds, String communityId, String topic, Instant createdAt,
			AuthorIdentity authorIdentity) {
		// Momento é efêmero por definição: nasce com prazo de 24 horas e se apaga sozinho.
		Instant expiresAt = type == PostType.MOMENT ? createdAt.plus(Duration.ofHours(24)) : null;
		return new Post(id, authorId, type, content, pageHtml, tags, mediaIds, communityId, topic, null, createdAt,
			Visibility.PUBLIC, Counters.zero(), PostStatus.ACTIVE, null, null, expiresAt, authorIdentity);
	}

	/**
	 * Publicação num CANAL privado da comunidade: o canal define o tema (o tópico vem dele) e o
	 * post só é lido por quem participa da comunidade — nunca nos feeds.
	 */
	public static Post publish(PostId id, UserId authorId, PostType type, String content, String pageHtml,
			List<String> tags, List<String> mediaIds, String communityId, String topic, String channelId,
			Instant createdAt, AuthorIdentity authorIdentity) {
		return publish(id, authorId, type, content, pageHtml, tags, mediaIds, communityId, topic, channelId,
			createdAt, authorIdentity, false);
	}

	public static Post publish(PostId id, UserId authorId, PostType type, String content, String pageHtml,
			List<String> tags, List<String> mediaIds, String communityId, String topic, String channelId,
			Instant createdAt, AuthorIdentity authorIdentity, boolean mediaSpoiler) {
		Instant expiresAt = type == PostType.MOMENT ? createdAt.plus(Duration.ofHours(24)) : null;
		return new Post(id, authorId, type, content, pageHtml, tags, mediaIds, communityId, topic, channelId,
			createdAt, Visibility.PUBLIC, Counters.zero(), PostStatus.ACTIVE, null, null, expiresAt, authorIdentity,
			mediaSpoiler);
	}

	public static Post restore(PostId id, UserId authorId, PostType type, String content, String pageHtml,
			List<String> tags, List<String> mediaIds, String communityId, Instant createdAt, Visibility visibility,
			Counters counters, PostStatus status, Instant deletedAt, Instant editedAt, Instant expiresAt,
			AuthorIdentity authorIdentity) {
		return restore(id, authorId, type, content, pageHtml, tags, mediaIds, communityId, null, createdAt,
			visibility, counters, status, deletedAt, editedAt, expiresAt, authorIdentity);
	}

	public static Post restore(PostId id, UserId authorId, PostType type, String content, String pageHtml,
			List<String> tags, List<String> mediaIds, String communityId, String topic, Instant createdAt,
			Visibility visibility, Counters counters, PostStatus status, Instant deletedAt, Instant editedAt,
			Instant expiresAt, AuthorIdentity authorIdentity) {
		return restore(id, authorId, type, content, pageHtml, tags, mediaIds, communityId, topic, null, createdAt,
			visibility, counters, status, deletedAt, editedAt, expiresAt, authorIdentity);
	}

	public static Post restore(PostId id, UserId authorId, PostType type, String content, String pageHtml,
			List<String> tags, List<String> mediaIds, String communityId, String topic, String channelId,
			Instant createdAt, Visibility visibility, Counters counters, PostStatus status, Instant deletedAt,
			Instant editedAt, Instant expiresAt, AuthorIdentity authorIdentity) {
		return restore(id, authorId, type, content, pageHtml, tags, mediaIds, communityId, topic, channelId,
			createdAt, visibility, counters, status, deletedAt, editedAt, expiresAt, authorIdentity, false);
	}

	public static Post restore(PostId id, UserId authorId, PostType type, String content, String pageHtml,
			List<String> tags, List<String> mediaIds, String communityId, String topic, String channelId,
			Instant createdAt, Visibility visibility, Counters counters, PostStatus status, Instant deletedAt,
			Instant editedAt, Instant expiresAt, AuthorIdentity authorIdentity, boolean mediaSpoiler) {
		return new Post(id, authorId, type, content, pageHtml, tags, mediaIds, communityId, topic, channelId,
			createdAt, visibility, counters, status, deletedAt, editedAt, expiresAt, authorIdentity, mediaSpoiler);
	}

	/** Restore sem identidade explícita: post antigo/legado é sempre SOCIAL. */
	public static Post restore(PostId id, UserId authorId, PostType type, String content, String pageHtml,
			List<String> tags, List<String> mediaIds, String communityId, Instant createdAt, Visibility visibility,
			Counters counters, PostStatus status, Instant deletedAt, Instant editedAt, Instant expiresAt) {
		return restore(id, authorId, type, content, pageHtml, tags, mediaIds, communityId, createdAt, visibility,
			counters, status, deletedAt, editedAt, expiresAt, AuthorIdentity.SOCIAL);
	}

	/**
	 * Edicao do proprio post: troca conteudo, pagina e tags e marca a data. Devolve uma nova instancia
	 * porque o conteudo e imutavel; id, autor e contadores sao preservados (editar nao zera nada).
	 */
	public Post edited(String newContent, String newPageHtml, List<String> newTags, Instant editedAt) {
		return edited(type, newContent, newPageHtml, newTags, editedAt);
	}

	/**
	 * Edicao com TROCA DE TIPO (a categoria e editavel, como o texto): as regras de conteudo de
	 * cada tipo valem no service, e o prazo do MOMENT acompanha a virada — entrando em MOMENT
	 * ganha as 24h da criacao (ou aproveita o prazo que ja existia); saindo, o prazo some.
	 */
	public Post edited(PostType newType, String newContent, String newPageHtml, List<String> newTags,
			Instant editedAt) {
		return edited(newType, newContent, newPageHtml, newTags, topic, editedAt);
	}

	/**
	 * Edicao com assunto declarado: so o post AVULSO troca o assunto na edicao (o de comunidade
	 * herda o dela e o service recusa qualquer tentativa). Validacao do valor e do service, pois
	 * a taxonomia mora la.
	 */
	public Post edited(PostType newType, String newContent, String newPageHtml, List<String> newTags,
			String newTopic, Instant editedAt) {
		Instant newExpiresAt = newType == PostType.MOMENT
			? (expiresAt != null ? expiresAt : editedAt.plus(Duration.ofHours(24)))
			: null;
		// Editar nunca muda a autoria: tipo, conteúdo, tags e assunto são editáveis.
		return new Post(id, authorId, newType, newContent, newPageHtml, newTags, mediaIds, communityId, newTopic,
			channelId, createdAt, visibility, counters, status, deletedAt, editedAt, newExpiresAt, authorIdentity,
			mediaSpoiler);
	}

	public boolean isActive() {
		return status == PostStatus.ACTIVE;
	}

	/** Está em quarentena de moderação (fora do ar, aguardando revisão humana). */
	public boolean isQuarantined() {
		return status == PostStatus.QUARANTINED;
	}

	/**
	 * Tira o post do ar SEM apagar: quarentena é precaução, não punição. O conteúdo continua no
	 * banco e {@link #release()} devolve tudo ao normal — por isso não mexe em {@code deletedAt}.
	 */
	public boolean quarantine() {
		if (status != PostStatus.ACTIVE) {
			return false;
		}
		status = PostStatus.QUARANTINED;
		deletedAt = null;
		return true;
	}

	/** Revisão humana concluiu que não havia violação: o post volta ao ar como estava. */
	public boolean release() {
		if (status != PostStatus.QUARANTINED) {
			return false;
		}
		status = PostStatus.ACTIVE;
		deletedAt = null;
		return true;
	}

	public Instant expiresAt() {
		return expiresAt;
	}

	/** O prazo já venceu? (o job apaga; a leitura também pode esconder antes dele rodar) */
	public boolean expired(Instant now) {
		return expiresAt != null && !expiresAt.isAfter(now);
	}

	public boolean belongsTo(UserId userId) {
		return authorId.equals(userId);
	}

	/** Quem assina esta publicação: a identidade social ou a profissional da mesma conta. */
	public AuthorIdentity authorIdentity() {
		return authorIdentity;
	}

	/** Assunto da publicação (da taxonomia das comunidades) ou null se não classificado. */
	public String topic() {
		return topic;
	}

	/** Canal privado onde o post nasceu (null = post normal). */
	public String channelId() {
		return channelId;
	}

	/** Marca esta publicação como republicação de outra (chamado só pelo serviço de repost). */
	public Post markRepostOf(String originalPostId, String originalAuthorUsername) {
		return markRepostOf(originalPostId, originalAuthorUsername, false);
	}

	public Post markRepostOf(String originalPostId, String originalAuthorUsername, boolean withOpinion) {
		this.repostOf = originalPostId;
		this.repostOfAuthor = originalAuthorUsername;
		this.repostWithOpinion = withOpinion;
		return this;
	}

	public boolean repostWithOpinion() {
		return repostWithOpinion;
	}

	public String repostOf() {
		return repostOf;
	}

	public String repostOfAuthor() {
		return repostOfAuthor;
	}

	public boolean delete(Instant deletedAt) {
		if (status == PostStatus.DELETED) {
			return false;
		}
		this.status = PostStatus.DELETED;
		this.deletedAt = deletedAt;
		return true;
	}

	/**
	 * Reativa um post excluido (desfazer da exclusao). Como a exclusao e logica, reverter e seguro:
	 * o conteudo nunca saiu do banco. So faz sentido a partir de DELETED.
	 */
	public boolean undelete() {
		if (status != PostStatus.DELETED) {
			return false;
		}
		this.status = PostStatus.ACTIVE;
		this.deletedAt = null;
		return true;
	}

	public void addReaction() {
		counters = counters.incrementReactions();
	}

	public void removeReaction() {
		counters = counters.decrementReactions();
	}

	public void addComment() {
		counters = counters.incrementComments();
	}

	public void removeComment() {
		counters = counters.decrementComments();
	}

	public void addSave() {
		counters = counters.incrementSaves();
	}

	public void removeSave() {
		counters = counters.decrementSaves();
	}

	public void addShare() {
		counters = counters.incrementShares();
	}

	public PostId id() {
		return id;
	}

	public UserId authorId() {
		return authorId;
	}

	public PostType type() {
		return type;
	}

	public String content() {
		return content;
	}

	public String pageHtml() {
		return pageHtml;
	}

	public List<String> tags() {
		return tags;
	}

	public List<String> mediaIds() {
		return mediaIds;
	}

	public boolean mediaSpoiler() {
		return mediaSpoiler;
	}

	public String communityId() {
		return communityId;
	}

	public Instant createdAt() {
		return createdAt;
	}

	public Visibility visibility() {
		return visibility;
	}

	public Counters counters() {
		return counters;
	}

	public PostStatus status() {
		return status;
	}

	public Instant deletedAt() {
		return deletedAt;
	}

	public Instant editedAt() {
		return editedAt;
	}
}
