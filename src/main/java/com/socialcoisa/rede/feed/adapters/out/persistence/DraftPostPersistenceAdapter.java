package com.socialcoisa.rede.feed.adapters.out.persistence;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.feed.application.port.out.DraftPostRepository;
import com.socialcoisa.rede.feed.domain.DraftPost;
import com.socialcoisa.rede.feed.domain.PostType;

@Component
class DraftPostPersistenceAdapter implements DraftPostRepository {

	private final SpringDataDraftPostJpaRepository repository;

	DraftPostPersistenceAdapter(SpringDataDraftPostJpaRepository repository) {
		this.repository = repository;
	}

	@Override
	public DraftPost save(DraftPost draft) {
		var entity = new DraftPostJpaEntity();
		entity.id = draft.id();
		entity.userId = draft.userId();
		entity.postType = draft.type().name();
		entity.content = draft.content();
		entity.pageHtml = draft.pageHtml();
		entity.articleBody = draft.articleBody();
		entity.topic = draft.topic();
		entity.pollOptions = join(draft.pollOptions());
		entity.pollMultipleChoice = draft.pollMultipleChoice();
		entity.pollEndsAt = draft.pollEndsAt();
		entity.mentionedIds = join(draft.mentionedUserIds());
		entity.createdAt = draft.createdAt();
		entity.updatedAt = draft.updatedAt();
		return toDomain(repository.save(entity));
	}

	@Override
	public Optional<DraftPost> findByUser(String userId) {
		return repository.findByUserId(userId).map(this::toDomain);
	}

	@Override
	public List<DraftPost> findAllByUser(String userId) {
		return repository.findByUserIdOrderByUpdatedAtDesc(userId).stream().map(this::toDomain).toList();
	}

	@Override
	public Optional<DraftPost> findById(String id) {
		return repository.findById(id).map(this::toDomain);
	}

	@Override
	public void delete(String id) {
		repository.deleteById(id);
	}

	private DraftPost toDomain(DraftPostJpaEntity entity) {
		return new DraftPost(entity.id, entity.userId, PostType.valueOf(entity.postType), entity.content,
			entity.pageHtml, entity.articleBody, entity.topic, split(entity.pollOptions),
			entity.pollMultipleChoice, entity.pollEndsAt, split(entity.mentionedIds), entity.createdAt,
			entity.updatedAt);
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
