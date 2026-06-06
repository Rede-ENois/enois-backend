package com.socialcoisa.rede.feed.adapters.out.persistence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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

	@Column(nullable = false, length = 1024)
	private String content;

	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "post_tags", joinColumns = @JoinColumn(name = "post_id"),
			indexes = @Index(name = "idx_post_tags_tag", columnList = "tag"))
	@Column(name = "tag", nullable = false)
	private List<String> tags = new ArrayList<>();

	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "post_media", joinColumns = @JoinColumn(name = "post_id"))
	@Column(name = "media_id", nullable = false)
	private List<String> mediaIds = new ArrayList<>();

	@Column(name = "community_id")
	private String communityId;

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

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private PostStatus status;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	protected PostJpaEntity() {
	}

	static PostJpaEntity fromDomain(Post post) {
		PostJpaEntity entity = new PostJpaEntity();
		entity.id = post.id().value();
		entity.authorId = post.authorId().value();
		entity.type = post.type();
		entity.content = post.content();
		entity.tags = new ArrayList<>(post.tags());
		entity.mediaIds = new ArrayList<>(post.mediaIds());
		entity.communityId = post.communityId();
		entity.createdAt = post.createdAt();
		entity.visibility = post.visibility();
		entity.reactions = post.counters().reactions();
		entity.comments = post.counters().comments();
		entity.saves = post.counters().saves();
		entity.shares = post.counters().shares();
		entity.status = post.status();
		entity.deletedAt = post.deletedAt();
		return entity;
	}

	Post toDomain() {
		return Post.restore(new PostId(id), new UserId(authorId), type, content, tags, mediaIds, communityId, createdAt,
			visibility, new Counters(reactions, comments, saves, shares), status, deletedAt);
	}
}
