package com.socialcoisa.rede.feed.adapters.out.persistence;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.feed.application.port.out.ScheduledPostRepository;
import com.socialcoisa.rede.feed.domain.PostType;
import com.socialcoisa.rede.feed.domain.ScheduledPost;

@Component
class ScheduledPostPersistenceAdapter implements ScheduledPostRepository {

	private final SpringDataScheduledPostJpaRepository repository;

	ScheduledPostPersistenceAdapter(SpringDataScheduledPostJpaRepository repository) {
		this.repository = repository;
	}

	@Override
	public ScheduledPost save(ScheduledPost post) {
		var entity = new ScheduledPostJpaEntity();
		entity.id = post.id();
		entity.userId = post.userId();
		entity.postType = post.type().name();
		entity.content = post.content();
		entity.pageHtml = post.pageHtml();
		entity.articleBody = post.articleBody();
		entity.tags = join(post.tags());
		entity.mediaIds = join(post.mediaIds());
		entity.mentionedIds = join(post.mentionedUserIds());
		entity.communityId = post.communityId();
		entity.topic = post.topic();
		entity.pollOptions = join(post.pollOptions());
		entity.scheduledAt = post.scheduledAt();
		entity.createdAt = post.createdAt();
		entity.lastError = post.lastError();
		entity.attempts = post.attempts();
		return toDomain(repository.save(entity));
	}

	@Override
	public List<ScheduledPost> findByUser(String userId) {
		return repository.findByUserIdOrderByScheduledAtAsc(userId).stream().map(this::toDomain).toList();
	}

	@Override
	public Optional<ScheduledPost> findById(String id) {
		return repository.findById(id).map(this::toDomain);
	}

	@Override
	public void delete(String id) {
		repository.deleteById(id);
	}

	@Override
	public List<ScheduledPost> findDue(Instant now, int attemptsBelow, int limit) {
		return repository
			.findTop20ByScheduledAtLessThanEqualAndAttemptsLessThanOrderByScheduledAtAsc(now, attemptsBelow)
			.stream()
			.limit(limit)
			.map(this::toDomain)
			.toList();
	}

	private ScheduledPost toDomain(ScheduledPostJpaEntity entity) {
		return new ScheduledPost(entity.id, entity.userId, PostType.valueOf(entity.postType), entity.content,
			entity.pageHtml, entity.articleBody, split(entity.tags), split(entity.mediaIds),
			split(entity.mentionedIds), entity.communityId, entity.topic, split(entity.pollOptions),
			entity.scheduledAt, entity.createdAt, entity.lastError, entity.attempts);
	}

	private String join(List<String> values) {
		return values == null || values.isEmpty() ? null : String.join(",", values);
	}

	private List<String> split(String value) {
		if (!StringUtils.hasText(value)) {
			return List.of();
		}
		return Arrays.stream(value.split(",")).map(String::trim).filter(StringUtils::hasText).toList();
	}
}
