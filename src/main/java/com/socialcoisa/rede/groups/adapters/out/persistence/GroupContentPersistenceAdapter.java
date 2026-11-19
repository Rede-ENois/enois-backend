package com.socialcoisa.rede.groups.adapters.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.feed.domain.ReactionType;
import com.socialcoisa.rede.groups.application.port.out.GroupContentRepository;
import com.socialcoisa.rede.groups.domain.GroupComment;
import com.socialcoisa.rede.groups.domain.GroupCommentStatus;
import com.socialcoisa.rede.groups.domain.GroupPost;
import com.socialcoisa.rede.groups.domain.GroupPostStatus;
import com.socialcoisa.rede.groups.domain.GroupReaction;
import com.socialcoisa.rede.groups.domain.GroupTopic;

@Repository
class GroupContentPersistenceAdapter implements GroupContentRepository {

	private final SpringDataGroupTopicJpaRepository topics;
	private final SpringDataGroupPostJpaRepository posts;
	private final SpringDataGroupCommentJpaRepository comments;
	private final SpringDataGroupPostReactionJpaRepository reactions;

	GroupContentPersistenceAdapter(SpringDataGroupTopicJpaRepository topics,
			SpringDataGroupPostJpaRepository posts, SpringDataGroupCommentJpaRepository comments,
			SpringDataGroupPostReactionJpaRepository reactions) {
		this.topics = topics;
		this.posts = posts;
		this.comments = comments;
		this.reactions = reactions;
	}

	@Override
	public void saveTopic(GroupTopic topic) {
		topics.save(GroupTopicJpaEntity.fromDomain(topic));
	}

	@Override
	public Optional<GroupTopic> findTopic(String topicId) {
		return topics.findById(topicId).map(GroupTopicJpaEntity::toDomain);
	}

	@Override
	public List<GroupTopic> listTopics(String groupId) {
		return topics.findByGroupIdOrderByPositionAsc(groupId).stream().map(GroupTopicJpaEntity::toDomain).toList();
	}

	@Override
	public boolean topicNameExists(String groupId, String name) {
		return topics.existsByGroupIdAndNameIgnoreCase(groupId, name);
	}

	@Override
	public int nextTopicPosition(String groupId) {
		return topics.maxPosition(groupId) + 1;
	}

	@Override
	public void savePost(GroupPost post) {
		posts.save(GroupPostJpaEntity.fromDomain(post));
	}

	@Override
	public Optional<GroupPost> findPost(String postId) {
		return posts.findById(postId).map(GroupPostJpaEntity::toDomain);
	}

	@Override
	public List<GroupPost> listPosts(String groupId, String topicId, Instant before, int limit) {
		return posts.list(groupId, GroupPostStatus.PUBLISHED, topicId, before, PageRequest.of(0, limit))
			.stream()
			.map(GroupPostJpaEntity::toDomain)
			.toList();
	}

	@Override
	public Optional<GroupPost> findPinned(String groupId) {
		return posts.findFirstByGroupIdAndPinnedTrueAndStatus(groupId, GroupPostStatus.PUBLISHED)
			.map(GroupPostJpaEntity::toDomain);
	}

	@Override
	public void clearPinned(String groupId) {
		posts.clearPinned(groupId);
	}

	@Override
	public void saveComment(GroupComment comment) {
		comments.save(GroupCommentJpaEntity.fromDomain(comment));
	}

	@Override
	public Optional<GroupComment> findComment(String commentId) {
		return comments.findById(commentId).map(GroupCommentJpaEntity::toDomain);
	}

	@Override
	public List<GroupComment> listComments(String postId, int limit) {
		return comments.findByPostIdAndStatusOrderByCreatedAtAsc(postId, GroupCommentStatus.PUBLISHED,
			PageRequest.of(0, limit))
			.stream()
			.map(GroupCommentJpaEntity::toDomain)
			.toList();
	}

	@Override
	public long countComments(String postId) {
		return comments.countByPostIdAndStatus(postId, GroupCommentStatus.PUBLISHED);
	}

	@Override
	public void saveReaction(GroupReaction reaction) {
		reactions.findByPostIdAndUserId(reaction.postId(), reaction.userId()).ifPresentOrElse(existing -> {
			existing.changeType(reaction.type());
			reactions.save(existing);
		}, () -> reactions.save(GroupReactionJpaEntity.fromDomain(reaction)));
	}

	@Override
	public Optional<GroupReaction> findReaction(String postId, String userId) {
		return reactions.findByPostIdAndUserId(postId, userId).map(GroupReactionJpaEntity::toDomain);
	}

	@Override
	public void removeReaction(String postId, String userId) {
		reactions.deleteByPostIdAndUserId(postId, userId);
	}

	@Override
	public long countReactions(String postId) {
		return reactions.countByPostId(postId);
	}

	@Override
	public Map<String, ReactionType> reactionsByUser(String userId, List<String> postIds) {
		if (postIds == null || postIds.isEmpty()) {
			return Map.of();
		}
		return reactions.findByUserIdAndPostIdIn(userId, postIds)
			.stream()
			.collect(Collectors.toMap(GroupReactionJpaEntity::postId, GroupReactionJpaEntity::type));
	}

	@Override
	@Transactional
	public int deleteAllForGroup(String groupId) {
		// Ordem do mais preso ao mais solto: reação depende da publicação, comentário também; tópico
		// por último. Tudo na transação de quem chamou (a exclusão inteira é uma decisão só).
		List<String> postIds = posts.findByGroupId(groupId).stream().map(entity -> entity.getId()).toList();
		if (!postIds.isEmpty()) {
			reactions.deleteByPostIds(postIds);
		}
		comments.deleteByGroupId(groupId);
		int removed = posts.deleteByGroupId(groupId);
		topics.deleteByGroupId(groupId);
		return removed;
	}
}
