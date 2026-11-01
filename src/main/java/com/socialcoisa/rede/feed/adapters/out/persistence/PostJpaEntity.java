package com.socialcoisa.rede.feed.adapters.out.persistence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.socialcoisa.rede.feed.domain.AuthorIdentity;
import com.socialcoisa.rede.feed.domain.Counters;
import com.socialcoisa.rede.feed.domain.Post;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.PostStatus;
import com.socialcoisa.rede.feed.domain.PostType;
import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.feed.domain.Visibility;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

@Entity
@Table(name = "posts", indexes = {
		@Index(name = "idx_posts_author_created", columnList = "author_id, created_at"),
		@Index(name = "idx_posts_author_status_created", columnList = "author_id, status, created_at"),
		@Index(name = "idx_posts_created", columnList = "created_at") })
class PostJpaEntity {

	@Id
	private String id;

	@Column(name = "author_id", nullable = false)
	private String authorId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private PostType type;

	/**
	 * Guarda o maior limite do PostContentLimits (TUTORIAL, 5.000). Ficou menor uma vez
	 * (V32, 1200) e o MariaDB de produção recusava o INSERT que a validação tinha deixado passar.
	 */
	@Column(nullable = false, length = 5000)
	private String content;

	/**
	 * So preenchido em posts do tipo PAGE. HTML de verdade nao cabe em varchar (esbarraria no
	 * tamanho maximo da linha) e uma pagina colada ocupa espaco: guardado como texto grande, fora
	 * da linha, o tamanho da pagina nao disputa espaco com as outras colunas de `posts`.
	 */
	@Column(name = "page_html", columnDefinition = "longtext")
	private String pageHtml;

	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "post_tags", joinColumns = @JoinColumn(name = "post_id"),
			indexes = @Index(name = "idx_post_tags_tag", columnList = "tag"))
	@Column(name = "tag", nullable = false)
	private List<String> tags = new ArrayList<>();

	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "post_media", joinColumns = @JoinColumn(name = "post_id"))
	@Column(name = "media_id", nullable = false)
	private List<String> mediaIds = new ArrayList<>();

	@Column(name = "media_spoiler", nullable = false)
	private boolean mediaSpoiler;

	@Column(name = "community_id")
	private String communityId;

	@Column(name = "channel_id", length = 64)
	private String channelId;

	@Column(name = "repost_of", length = 64)
	private String repostOf;

	@Column(name = "repost_of_author", length = 80)
	private String repostOfAuthor;

	@Column(name = "repost_with_opinion", nullable = false)
	private boolean repostWithOpinion;

	/** Read-only here: written by SpringDataPostJpaRepository#updateAcceptedAnswer only. */
	@Column(name = "accepted_comment_id", insertable = false, updatable = false)
	private String acceptedCommentId;

	@Column(name = "resolved_at", insertable = false, updatable = false)
	private Instant resolvedAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Visibility visibility;

	@Column(nullable = false)
	private int reactions;

	@Column(nullable = false)
	private int comments;

	@Column(nullable = false)
	private int saves;

	@Column(nullable = false)
	private int shares;

	@Column(nullable = false)
	private int reposts;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private PostStatus status;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	@Column(name = "edited_at")
	private Instant editedAt;

	@Column(name = "expires_at")
	private Instant expiresAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "author_identity", nullable = false)
	private AuthorIdentity authorIdentity;

	@Column(name = "topic")
	private String topic;

	protected PostJpaEntity() {
	}

	static PostJpaEntity fromDomain(Post post) {
		PostJpaEntity entity = new PostJpaEntity();
		entity.id = post.id().value();
		entity.authorId = post.authorId().value();
		entity.type = post.type();
		entity.content = post.content();
		entity.pageHtml = post.pageHtml();
		entity.tags = new ArrayList<>(post.tags());
		entity.mediaIds = new ArrayList<>(post.mediaIds());
		entity.mediaSpoiler = post.mediaSpoiler();
		entity.communityId = post.communityId();
		entity.channelId = post.channelId();
		entity.repostOf = post.repostOf();
		entity.repostOfAuthor = post.repostOfAuthor();
		entity.repostWithOpinion = post.repostWithOpinion();
		entity.createdAt = post.createdAt();
		entity.visibility = post.visibility();
		entity.reactions = post.counters().reactions();
		entity.comments = post.counters().comments();
		entity.saves = post.counters().saves();
		entity.shares = post.counters().shares();
		entity.reposts = post.counters().reposts();
		entity.status = post.status();
		entity.deletedAt = post.deletedAt();
		entity.editedAt = post.editedAt();
		entity.expiresAt = post.expiresAt();
		entity.authorIdentity = post.authorIdentity();
		entity.topic = post.topic();
		return entity;
	}

	Post toDomain() {
		return Post.restore(new PostId(id), new UserId(authorId), type, content, pageHtml, tags, mediaIds, communityId,
			topic, channelId, createdAt, visibility, new Counters(reactions, comments, saves, shares, reposts), status,
			deletedAt, editedAt, expiresAt, authorIdentity, mediaSpoiler)
			.markRepostOf(repostOf, repostOfAuthor, repostWithOpinion)
			.markAcceptedAnswer(acceptedCommentId, resolvedAt);
	}
}
