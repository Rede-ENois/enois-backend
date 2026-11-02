package com.socialcoisa.rede.groups.domain;

import java.time.Instant;

/**
 * Comentário numa publicação do grupo.
 *
 * Pertence à publicação (e ao grupo dela): a resposta a um comentário só pode apontar para um
 * comentário DA MESMA publicação — o serviço confere. Mora em tabela própria, separada dos
 * comentários do feed principal.
 */
public class GroupComment {

	/** Teto do texto, o mesmo da coluna (`group_post_comments.content`). */
	public static final int MAX_CONTENT = 1_200;

	private final String id;
	private final String postId;
	private final String groupId;
	private final String authorId;
	private final String parentId;
	private final String content;
	private final GroupCommentStatus status;
	private final Instant createdAt;
	private final Instant updatedAt;

	private GroupComment(String id, String postId, String groupId, String authorId, String parentId, String content,
			GroupCommentStatus status, Instant createdAt, Instant updatedAt) {
		this.id = id;
		this.postId = postId;
		this.groupId = groupId;
		this.authorId = authorId;
		this.parentId = parentId;
		this.content = content;
		this.status = status;
		this.createdAt = createdAt;
		this.updatedAt = updatedAt;
	}

	public static GroupComment create(String id, String postId, String groupId, String authorId, String parentId,
			String content, Instant now) {
		return new GroupComment(id, postId, groupId, authorId, parentId, content, GroupCommentStatus.PUBLISHED, now,
			now);
	}

	public static GroupComment restore(String id, String postId, String groupId, String authorId, String parentId,
			String content, GroupCommentStatus status, Instant createdAt, Instant updatedAt) {
		return new GroupComment(id, postId, groupId, authorId, parentId, content, status, createdAt, updatedAt);
	}

	public GroupComment edit(String newContent, Instant now) {
		return new GroupComment(id, postId, groupId, authorId, parentId, newContent, status, createdAt, now);
	}

	public GroupComment remove(Instant now) {
		return new GroupComment(id, postId, groupId, authorId, parentId, content, GroupCommentStatus.REMOVED,
			createdAt, now);
	}

	public boolean isPublished() {
		return status == GroupCommentStatus.PUBLISHED;
	}

	public String id() {
		return id;
	}

	public String postId() {
		return postId;
	}

	public String groupId() {
		return groupId;
	}

	public String authorId() {
		return authorId;
	}

	public String parentId() {
		return parentId;
	}

	public String content() {
		return content;
	}

	public GroupCommentStatus status() {
		return status;
	}

	public Instant createdAt() {
		return createdAt;
	}

	public Instant updatedAt() {
		return updatedAt;
	}
}
