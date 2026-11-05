package com.socialcoisa.rede.feed.adapters.out.persistence;

import java.time.Clock;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.feed.application.port.out.PollRepository;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.UserId;

@Repository
class PollPersistenceAdapter implements PollRepository {

	private final SpringDataPollOptionJpaRepository options;
	private final SpringDataPollVoteJpaRepository votes;
	private final Clock clock;

	PollPersistenceAdapter(SpringDataPollOptionJpaRepository options, SpringDataPollVoteJpaRepository votes,
			Clock clock) {
		this.options = options;
		this.votes = votes;
		this.clock = clock;
	}

	@Override
	public void saveOptions(PostId postId, List<String> values) {
		List<PollOptionJpaEntity> rows = new java.util.ArrayList<>(values.size());
		for (int position = 0; position < values.size(); position++) {
			rows.add(new PollOptionJpaEntity(newId("poll_option"), postId.value(), position, values.get(position)));
		}
		options.saveAll(rows);
	}

	@Override
	public Map<String, List<String>> optionsByPostIds(Collection<String> postIds) {
		if (postIds == null || postIds.isEmpty()) {
			return Map.of();
		}
		Map<String, List<String>> byPost = new HashMap<>();
		for (PollOptionJpaEntity option : options.findByPostIdInOrderByPositionAsc(postIds)) {
			byPost.computeIfAbsent(option.getPostId(), key -> new java.util.ArrayList<>()).add(option.getText());
		}
		return byPost;
	}

	@Override
	public Map<String, Map<Integer, Long>> countsByPostIds(Collection<String> postIds) {
		if (postIds == null || postIds.isEmpty()) {
			return Map.of();
		}
		Map<String, Map<Integer, Long>> byPost = new HashMap<>();
		for (Object[] row : votes.countByPostIdsGroupedByPosition(postIds)) {
			byPost.computeIfAbsent((String) row[0], key -> new LinkedHashMap<>())
				.put(((Number) row[1]).intValue(), ((Number) row[2]).longValue());
		}
		return byPost;
	}

	@Override
	public Map<String, Integer> votesByUserAndPostIds(UserId user, Collection<String> postIds) {
		if (user == null || postIds == null || postIds.isEmpty()) {
			return Map.of();
		}
		Map<String, Integer> byPost = new HashMap<>();
		for (PollVoteJpaEntity vote : votes.findByPostIdInAndUserId(postIds, user.value())) {
			byPost.put(vote.getPostId(), vote.getOptionPosition());
		}
		return byPost;
	}

	@Override
	public long countVotes(PostId postId) {
		return votes.countByPostId(postId.value());
	}

	@Override
	public void vote(PostId postId, UserId user, int optionPosition) {
		// O primeiro voto ganha: se ja existe (ou outra requisicao chegou junto), o banco recusa o
		// INSERT pela unique (post_id, user_id) — nunca ha UPDATE do voto anterior.
		votes.saveAndFlush(new PollVoteJpaEntity(newId("poll_vote"), postId.value(), user.value(), optionPosition,
			clock.instant()));
	}

	private String newId(String prefix) {
		return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
	}
}
