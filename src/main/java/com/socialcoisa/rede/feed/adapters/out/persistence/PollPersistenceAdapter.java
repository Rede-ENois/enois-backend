package com.socialcoisa.rede.feed.adapters.out.persistence;

import java.time.Clock;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.feed.application.port.out.PollRepository;
import com.socialcoisa.rede.feed.domain.PollConfig;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.UserId;

@Repository
class PollPersistenceAdapter implements PollRepository {

	private final SpringDataPollOptionJpaRepository options;
	private final SpringDataPollVoteJpaRepository votes;
	private final SpringDataPollConfigJpaRepository configs;
	private final Clock clock;

	PollPersistenceAdapter(SpringDataPollOptionJpaRepository options, SpringDataPollVoteJpaRepository votes,
			SpringDataPollConfigJpaRepository configs, Clock clock) {
		this.options = options;
		this.votes = votes;
		this.configs = configs;
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
	public Map<String, Long> votersCountByPostIds(Collection<String> postIds) {
		if (postIds == null || postIds.isEmpty()) {
			return Map.of();
		}
		Map<String, Long> byPost = new HashMap<>();
		for (Object[] row : votes.countVotersByPostIds(postIds)) {
			byPost.put((String) row[0], ((Number) row[1]).longValue());
		}
		return byPost;
	}

	@Override
	public Map<String, List<Integer>> myVotesByPostIds(UserId user, Collection<String> postIds) {
		if (user == null || postIds == null || postIds.isEmpty()) {
			return Map.of();
		}
		Map<String, List<Integer>> byPost = new HashMap<>();
		for (PollVoteJpaEntity vote : votes.findByPostIdInAndUserId(postIds, user.value())) {
			byPost.computeIfAbsent(vote.getPostId(), key -> new java.util.ArrayList<>()).add(vote.getOptionPosition());
		}
		// A selecao e um conjunto: a ordem crescente e so apresentacao.
		for (List<Integer> posicoes : byPost.values()) {
			java.util.Collections.sort(posicoes);
		}
		return byPost;
	}

	@Override
	@Transactional
	public void replaceVotes(PostId postId, UserId user, List<Integer> positions) {
		String postIdValue = postId.value();
		List<PollVoteJpaEntity> atuais = votes.findByPostIdAndUserId(postIdValue, user.value());
		java.util.Set<Integer> desejadas = new java.util.TreeSet<>(positions);
		List<PollVoteJpaEntity> paraApagar = atuais.stream()
			.filter(voto -> !desejadas.contains(voto.getOptionPosition()))
			.toList();
		if (!paraApagar.isEmpty()) {
			votes.deleteAllInBatch(paraApagar);
		}
		List<PollVoteJpaEntity> paraGravar = new java.util.ArrayList<>();
		for (Integer posicao : desejadas) {
			if (atuais.stream().noneMatch(voto -> voto.getOptionPosition() == posicao)) {
				paraGravar.add(new PollVoteJpaEntity(newId("poll_vote"), postIdValue, user.value(), posicao,
					clock.instant()));
			}
		}
		if (!paraGravar.isEmpty()) {
			votes.saveAll(paraGravar);
			votes.flush();
		}
	}

	@Override
	public void saveConfig(PostId postId, PollConfig config) {
		configs.save(new PollConfigJpaEntity(postId.value(), config.multipleChoice(), config.endsAt()));
	}

	@Override
	public Map<String, PollConfig> configsByPostIds(Collection<String> postIds) {
		if (postIds == null || postIds.isEmpty()) {
			return Map.of();
		}
		Map<String, PollConfig> byPost = new HashMap<>();
		for (PollConfigJpaEntity config : configs.findByPostIdIn(postIds)) {
			byPost.put(config.getPostId(), new PollConfig(config.isMultipleChoice(), config.getEndsAt()));
		}
		return byPost;
	}

	private String newId(String prefix) {
		return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
	}
}
