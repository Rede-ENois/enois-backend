package com.socialcoisa.rede.moderation.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.moderation.application.port.out.ModerationLearningRepository;

import tools.jackson.databind.ObjectMapper;

@Tag("critico")
class ModerationLearningServiceTests {

	@Test
	void weightRisesWhenHumanReviewPunishesTheSignal() {
		StubRepository repo = new StubRepository();
		for (int i = 0; i < 5; i++) {
			repo.feedback.add(new ModerationLearningRepository.Feedback("f" + i, "r" + i, "PUNISH", "HUMAN",
				"admin", ModerationSignalCatalog.REASON_SPAM, 25, Instant.now()));
		}
		repo.feedback.add(new ModerationLearningRepository.Feedback("f9", "r9", "DISMISS", "HUMAN",
			"admin", ModerationSignalCatalog.REASON_SPAM, 25, Instant.now()));

		service(repo).learnWeights();

		assertThat(repo.weights.get(ModerationSignalCatalog.REASON_SPAM))
			.isGreaterThan(ModerationSignalCatalog.BASE_WEIGHTS.get(ModerationSignalCatalog.REASON_SPAM));
		assertThat(repo.samples.get(ModerationSignalCatalog.REASON_SPAM)).isEqualTo(6);
	}

	@Test
	void weightFallsWhenMostReviewsDismissTheSignal() {
		StubRepository repo = new StubRepository();
		for (int i = 0; i < 6; i++) {
			repo.feedback.add(new ModerationLearningRepository.Feedback("d" + i, "rd" + i, "DISMISS", "HUMAN",
				"admin", ModerationSignalCatalog.CONTENT_LINK, 10, Instant.now()));
		}

		service(repo).learnWeights();

		assertThat(repo.weights.get(ModerationSignalCatalog.CONTENT_LINK))
			.isLessThan(ModerationSignalCatalog.BASE_WEIGHTS.get(ModerationSignalCatalog.CONTENT_LINK));
	}

	private ModerationLearningService service(StubRepository repo) {
		return new ModerationLearningService(repo, new ObjectMapper(),
			Clock.fixed(Instant.parse("2026-10-21T12:00:00Z"), ZoneOffset.UTC));
	}

	private static final class StubRepository implements ModerationLearningRepository {
		private final List<Feedback> feedback = new ArrayList<>();
		private final Map<String, Integer> weights = new HashMap<>();
		private final Map<String, Integer> samples = new HashMap<>();

		@Override public void save(Feedback item) { feedback.add(item); }
		@Override public List<Feedback> recent(int limit) { return List.copyOf(feedback); }
		@Override public Map<String, Integer> weights() { return Map.copyOf(weights); }
		@Override public void upsertWeight(String signal, int weight, int sampleCount, Instant now) {
			weights.put(signal, weight);
			samples.put(signal, sampleCount);
		}
	}
}
