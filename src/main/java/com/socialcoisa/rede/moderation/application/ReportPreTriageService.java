package com.socialcoisa.rede.moderation.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.moderation.application.port.out.ReportRepository;
import com.socialcoisa.rede.moderation.application.port.out.ReportedContentAuthorReader;
import com.socialcoisa.rede.moderation.application.port.out.ReportedContentReader;
import com.socialcoisa.rede.moderation.domain.Report;
import com.socialcoisa.rede.moderation.domain.ReportReason;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;

/**
 * Pré-triagem determinística: calcula sinais objetivos e um score ANTES de chamar o LLM.
 *
 * Objetivo é custo e latência, não substituir a IA: denúncia sem sinal nenhum nunca chega ao
 * modelo (continua PENDING para uma pessoa); com sinal suficiente, a IA recebe só o que importa.
 * O score é auditável e pode ser reajustado pelos pesos aprendidos do feedback humano.
 */
@Service
public class ReportPreTriageService {

	private final ReportRepository reports;
	private final ReportedContentReader contentReader;
	private final ReportedContentAuthorReader contentAuthors;
	private final UserProfileRepository profiles;
	private final ModerationSignalWeights weights;
	private final Clock clock;
	private final boolean enabled;
	private final int minScoreForAi;
	private final int duplicateTargetWindowHours;

	public ReportPreTriageService(ReportRepository reports, ReportedContentReader contentReader,
			ReportedContentAuthorReader contentAuthors, UserProfileRepository profiles,
			ModerationSignalWeights weights, Clock clock,
			@Value("${rede.moderation.pretriage.enabled:true}") boolean enabled,
			@Value("${rede.moderation.pretriage.min-score:30}") int minScoreForAi,
			@Value("${rede.moderation.pretriage.duplicate-target-window-hours:24}") int duplicateTargetWindowHours) {
		this.reports = reports;
		this.contentReader = contentReader;
		this.contentAuthors = contentAuthors;
		this.profiles = profiles;
		this.weights = weights;
		this.clock = clock;
		this.enabled = enabled;
		this.minScoreForAi = Math.max(0, minScoreForAi);
		this.duplicateTargetWindowHours = Math.max(1, duplicateTargetWindowHours);
	}

	@Transactional
	public int preTriagePending(int limit) {
		if (!enabled) {
			return 0;
		}
		int scored = 0;
		for (Report report : reports.findPendingWithoutPreTriage(limit)) {
			String content = contentReader.contentOf(report.targetType().name(), report.targetId()).orElse("");
			String authorId = contentAuthors.authorOf(report.targetType().name(), report.targetId()).orElse(null);
			UserProfile author = authorId == null ? null : profiles.findById(authorId).orElse(null);
			Score score = score(report, content, author);
			Instant now = clock.instant();
			report.attachPreTriage(toJson(score), score.score(), now);
			if (score.score() < minScoreForAi) {
				// Sai da fila da IA sem chamada: fica resolvida para a revisão humana.
				report.attachAiTriage("{\"action\":\"HUMAN_REVIEW\",\"source\":\"pre-triage\"}", now);
			}
			reports.save(report);
			scored++;
		}
		return scored;
	}

	/** Pode chamar o LLM? Sem pré-triagem (legado/desligada) mantém o comportamento antigo. */
	public boolean shouldCallAi(Report report) {
		if (!enabled || report.preTriageScore() == null) {
			return true;
		}
		return report.preTriageScore() >= minScoreForAi;
	}

	Score score(Report report, String content, UserProfile author) {
		Instant now = clock.instant();
		List<String> signals = new ArrayList<>();
		int score = 0;

		String reasonSignal = switch (report.reason()) {
			case VIOLENCE, HARASSMENT, DISCRIMINATION, PREJUDICE, SCAM -> ModerationSignalCatalog.REASON_VIOLENCE;
			case SEXUAL_CONTENT -> ModerationSignalCatalog.REASON_SEXUAL;
			case AI_GENERATED -> ModerationSignalCatalog.REASON_AUTOMATION;
			case SPAM, FAKE_CONTENT, COPYRIGHT -> ModerationSignalCatalog.REASON_SPAM;
			default -> ModerationSignalCatalog.REASON_OTHER;
		};
		signals.add(reasonSignal);
		score += weights.weightOf(reasonSignal);

		long sameTarget = reports.countAgainstTargetSince(report.targetType().name(), report.targetId(),
			now.minus(Duration.ofHours(duplicateTargetWindowHours)));
		if (sameTarget >= 2) {
			signals.add(ModerationSignalCatalog.REPEATED_TARGET);
			score += weights.weightOf(ModerationSignalCatalog.REPEATED_TARGET);
		}
		if (author != null && author.createdAt().isAfter(now.minus(Duration.ofDays(7)))) {
			signals.add(ModerationSignalCatalog.NEW_ACCOUNT);
			score += weights.weightOf(ModerationSignalCatalog.NEW_ACCOUNT);
		}
		if (report.description() != null && report.description().trim().length() >= 80) {
			signals.add(ModerationSignalCatalog.DETAILED_REPORT);
			score += weights.weightOf(ModerationSignalCatalog.DETAILED_REPORT);
		}
		String normalized = content == null ? "" : content.toLowerCase(Locale.ROOT);
		if (normalized.contains("http://") || normalized.contains("https://")) {
			signals.add(ModerationSignalCatalog.CONTENT_LINK);
			score += weights.weightOf(ModerationSignalCatalog.CONTENT_LINK);
		}
		if (report.reason() == ReportReason.SPAM && !normalized.isBlank() && normalized.trim().length() < 20) {
			signals.add(ModerationSignalCatalog.SHORT_CONTENT);
			score += weights.weightOf(ModerationSignalCatalog.SHORT_CONTENT);
		}
		if ("system".equals(report.reporterId())) {
			signals.add(ModerationSignalCatalog.SYSTEM_REPORT);
			score += weights.weightOf(ModerationSignalCatalog.SYSTEM_REPORT);
		}
		return new Score(score, List.copyOf(signals));
	}

	/** JSON fixo (só códigos conhecidos): não há texto livre para escapar. */
	String toJson(Score score) {
		String signals = score.signals().stream().map(s -> "\"" + s + "\"").reduce((a, b) -> a + "," + b)
			.orElse("");
		return "{\"signals\":[" + signals + "],\"score\":" + score.score() + "}";
	}

	record Score(int score, List<String> signals) {
	}
}
