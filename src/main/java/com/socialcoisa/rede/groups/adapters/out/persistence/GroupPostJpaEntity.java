package com.socialcoisa.rede.groups.adapters.out.persistence;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import com.socialcoisa.rede.groups.domain.GroupPost;
import com.socialcoisa.rede.groups.domain.GroupPostStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "group_posts")
class GroupPostJpaEntity {

	@Id
	private String id;

	String getId() {
		return id;
	}

	@Column(name = "group_id", nullable = false)
	private String groupId;

	@Column(name = "topic_id", nullable = false)
	private String topicId;

	@Column(name = "author_id", nullable = false)
	private String authorId;

	@Column(nullable = false, length = GroupPost.MAX_CONTENT)
	private String content;

	/** Ids de mídia separados por linha (a coluna é varchar; a mídia continua no módulo de mídia). */
	@Column(name = "media_ids", nullable = false, length = 1000)
	private String mediaIdsRaw = "";

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private GroupPostStatus status;

	@Column(nullable = false)
	private boolean pinned;

	@Column(nullable = false)
	private int reactions;

	@Column(nullable = false)
	private int comments;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected GroupPostJpaEntity() {
	}

	static GroupPostJpaEntity fromDomain(GroupPost post) {
		GroupPostJpaEntity entity = new GroupPostJpaEntity();
		entity.id = post.id();
		entity.groupId = post.groupId();
		entity.topicId = post.topicId();
		entity.authorId = post.authorId();
		entity.content = post.content();
		entity.mediaIdsRaw = String.join("\n", post.mediaIds());
		entity.status = post.status();
		entity.pinned = post.pinned();
		entity.reactions = post.reactions();
		entity.comments = post.comments();
		entity.createdAt = post.createdAt();
		entity.updatedAt = post.updatedAt();
		return entity;
	}

	GroupPost toDomain() {
		return GroupPost.restore(id, groupId, topicId, authorId, content, split(mediaIdsRaw), status, pinned,
			reactions, comments, createdAt, updatedAt);
	}

	private static List<String> split(String raw) {
		if (raw == null || raw.isBlank()) {
			return List.of();
		}
		return Arrays.stream(raw.split("\n")).map(String::trim).filter(value -> !value.isEmpty()).toList();
	}
}
