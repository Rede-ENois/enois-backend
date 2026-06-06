package com.socialcoisa.rede.feed.domain;

import java.time.Instant;
import java.util.List;

public class Post {

	private final PostId id;
	private final UserId authorId;
	private final PostType type;
	private final String content;
	private final List<String> tags;
	private final List<String> mediaIds;
	private final String communityId;
	private final Instant createdAt;
	private final Visibility visibility;
	private Counters counters;
	private PostStatus status;
	private Instant deletedAt;

	private Post(PostId id, UserId authorId, PostType type, String content, List<String> tags, List<String> mediaIds,
			String communityId, Instant createdAt, Visibility visibility, Counters counters, PostStatus status,
			Instant deletedAt) {
		this.id = id;
		this.authorId = authorId;
		this.type = type;
		this.content = content;
		this.tags = List.copyOf(tags);
		this.mediaIds = List.copyOf(mediaIds);
		this.communityId = communityId;
		this.createdAt = createdAt;
		this.visibility = visibility;
		this.counters = counters;
		this.status = status;
		this.deletedAt = deletedAt;
	}

	public static Post publish(PostId id, UserId authorId, PostType type, String content, List<String> tags,
			List<String> mediaIds, String communityId, Instant createdAt) {
		return new Post(id, authorId, type, content, tags, mediaIds, communityId, createdAt, Visibility.PUBLIC,
			Counters.zero(), PostStatus.ACTIVE, null);
	}

	public static Post restore(PostId id, UserId authorId, PostType type, String content, List<String> tags,
			List<String> mediaIds, String communityId, Instant createdAt, Visibility visibility, Counters counters,
			PostStatus status, Instant deletedAt) {
		return new Post(id, authorId, type, content, tags, mediaIds, communityId, createdAt, visibility, counters,
			status, deletedAt);
	}

	public boolean isActive() {
		return status == PostStatus.ACTIVE;
	}

	public boolean belongsTo(UserId userId) {
		return authorId.equals(userId);
	}

	public boolean delete(Instant deletedAt) {
		if (status == PostStatus.DELETED) {
			return false;
		}
		this.status = PostStatus.DELETED;
		this.deletedAt = deletedAt;
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

	public List<String> tags() {
		return tags;
	}

	public List<String> mediaIds() {
		return mediaIds;
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
}
