package com.socialcoisa.rede.moderation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.moderation.application.port.out.ReportRepository;
import com.socialcoisa.rede.moderation.application.port.out.ReportedContentAuthorReader;
import com.socialcoisa.rede.moderation.application.port.out.ReportedContentReader;
import com.socialcoisa.rede.moderation.domain.Report;
import com.socialcoisa.rede.moderation.domain.ReportReason;
import com.socialcoisa.rede.moderation.domain.TargetType;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;

class ReportPreTriageServiceTests {

	private final Instant now = Instant.parse("2026-10-21T12:00:00Z");
	private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

	@Test
	void highRiskReportWithRepeatedTargetAndLinkPassesTheAiGate() {
		ReportRepository reports = mock(ReportRepository.class);
		ReportedContentReader content = (type, id) -> Optional.of("promoção em http://spam.example/oferta");
		ReportedContentAuthorReader authors = (type, id) -> Optional.of("author_1");
		UserProfileRepository profiles = mock(UserProfileRepository.class);
		when(profiles.findById("author_1")).thenReturn(Optional.of(
			UserProfile.create("author_1", "novato", "novato@test.com", "Novato", null,
				now.minus(Duration.ofDays(1)))));
		when(reports.countAgainstTargetSince(anyString(), anyString(), any())).thenReturn(3L);

		ReportPreTriageService service = service(reports, content, authors, profiles);
		Report report = Report.create("rep_1", "user_rep", TargetType.POST, "post_1", ReportReason.VIOLENCE,
			"", now);

		var score = service.score(report, "promoção em http://spam.example/oferta",
			UserProfile.create("author_1", "novato", "novato@test.com", "Novato", null,
				now.minus(Duration.ofDays(1))));
		assertThat(score.signals()).contains(ModerationSignalCatalog.REASON_VIOLENCE,
			ModerationSignalCatalog.REPEATED_TARGET, ModerationSignalCatalog.NEW_ACCOUNT,
			ModerationSignalCatalog.CONTENT_LINK);
		assertThat(score.score()).isGreaterThanOrEqualTo(30);

		report.attachPreTriage(service.toJson(score), score.score(), now);
		assertThat(service.shouldCallAi(report)).isTrue();
		assertThat(service.toJson(score)).contains("\"signals\"");
	}

	@Test
	void aSexualContentReportAlwaysReachesTheModel() {
		// O incidente de 2026-10-29: denúncia de conteúdo sexual com post curto, autor antigo, sem
		// link e sem reincidência. Com o peso de REASON_OTHER (5) ela nunca chegava ao modelo.
		ReportRepository reports = mock(ReportRepository.class);
		when(reports.countAgainstTargetSince(anyString(), anyString(), any())).thenReturn(1L);
		ReportPreTriageService service = service(reports, (type, id) -> Optional.of("oi"),
			(type, id) -> Optional.of("author_1"), mock(UserProfileRepository.class));

		Report report = Report.create("rep_sexual", "user_rep", TargetType.POST, "post_x",
			ReportReason.SEXUAL_CONTENT, "foto explícita", now);

		var score = service.score(report, "oi", null);

		assertThat(score.signals()).contains(ModerationSignalCatalog.REASON_SEXUAL);
		assertThat(score.score()).isGreaterThanOrEqualTo(30);

		report.attachPreTriage(service.toJson(score), score.score(), now);
		assertThat(service.shouldCallAi(report)).isTrue();
	}

	@Test
	void weakReportStaysOutOfTheLlm() {
		ReportRepository reports = mock(ReportRepository.class);
		ReportPreTriageService service = service(reports, (type, id) -> Optional.empty(),
			(type, id) -> Optional.empty(), mock(UserProfileRepository.class));
		when(reports.countAgainstTargetSince(anyString(), anyString(), any())).thenReturn(1L);

		Report report = Report.create("rep_2", "user_rep", TargetType.COMMENT, "comment_1",
			ReportReason.OTHER, "não gostei", now);
		report.attachPreTriage("{\"signals\":[\"REASON_OTHER\"],\"score\":5}", 5, now);

		assertThat(service.shouldCallAi(report)).isFalse();
	}

	@Test
	void anAutomationReportAlwaysReachesTheModel() {
		// Texto de robô costuma ser longo, bem formatado e sem link — justamente o perfil que a
		// pré-triagem não pontua. Sem peso próprio, a denúncia nunca chegava ao modelo.
		ReportRepository reports = mock(ReportRepository.class);
		when(reports.countAgainstTargetSince(anyString(), anyString(), any())).thenReturn(1L);
		ReportPreTriageService service = service(reports, (type, id) -> Optional.of("texto limpo"),
			(type, id) -> Optional.of("author_1"), mock(UserProfileRepository.class));

		Report report = Report.create("rep_ia", "user_rep", TargetType.POST, "post_ia",
			ReportReason.AI_GENERATED, "parece saida de robo", now);

		var score = service.score(report, "texto limpo", null);

		assertThat(score.signals()).contains(ModerationSignalCatalog.REASON_AUTOMATION);
		assertThat(score.score()).isGreaterThanOrEqualTo(30);
		report.attachPreTriage(service.toJson(score), score.score(), now);
		assertThat(service.shouldCallAi(report)).isTrue();
	}

	private ReportPreTriageService service(ReportRepository reports, ReportedContentReader content,
			ReportedContentAuthorReader authors, UserProfileRepository profiles) {
		ModerationLearningRepositoryStub learning = new ModerationLearningRepositoryStub();
		return new ReportPreTriageService(reports, content, authors, profiles,
			new ModerationSignalWeights(learning, clock), clock, true, 30, 24);
	}

	/** Sem pesos aprendidos: o serviço cai no base do catálogo. */
	private static final class ModerationLearningRepositoryStub
			implements com.socialcoisa.rede.moderation.application.port.out.ModerationLearningRepository {
		@Override public void save(Feedback feedback) { }
		@Override public java.util.List<Feedback> recent(int limit) { return java.util.List.of(); }
		@Override public java.util.Map<String, Integer> weights() { return java.util.Map.of(); }
		@Override public void upsertWeight(String signal, int weight, int samples, Instant now) { }
	}
}
