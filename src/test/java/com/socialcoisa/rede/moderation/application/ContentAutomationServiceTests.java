package com.socialcoisa.rede.moderation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.feed.application.port.in.ModerationQuarantinePostUseCase;
import com.socialcoisa.rede.feed.application.port.out.PostRepository;
import com.socialcoisa.rede.feed.domain.Post;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.PostType;
import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.moderation.ai.ContentAutomationAnalyzer;
import com.socialcoisa.rede.moderation.application.port.out.ContentAutomationCheckRepository;
import com.socialcoisa.rede.moderation.domain.ContentAutomationCheck;
import com.socialcoisa.rede.moderation.domain.ContentAutomationCheck.ReviewStatus;
import com.socialcoisa.rede.moderation.domain.ContentAutomationVerdict;
import com.socialcoisa.rede.notifications.application.NotificationService;
import com.socialcoisa.rede.shared.web.AdminGuard;
import com.socialcoisa.rede.shared.web.CurrentUser;

/**
 * A regra pedida: nada de banir sozinho. Post suspeito vai para QUARENTENA (some da rede, continua
 * no banco), é marcado e entra na fila de revisão humana — e nenhum caminho aqui aplica sanção.
 */
class ContentAutomationServiceTests {

	private static final String AUTOR = "user_bot_tests";
	private static final String POST = "post_bot_1";
	private static final Instant AGORA = Instant.parse("2026-10-29T12:00:00Z");
	private static final String TEXTO_SUSPEITO = "Claro! Aqui está o texto que você pediu. Espero ter ajudado.";

	private ContentAutomationCheckRepository checks;
	private PostRepository posts;
	private ContentAutomationAnalyzer analyzer;
	private ModerationQuarantinePostUseCase quarantinePost;
	private NotificationService notifications;
	private AdminGuard adminGuard;
	private ModeratorService moderators;

	private ContentAutomationService service;

	@BeforeEach
	void preparar() {
		checks = mock(ContentAutomationCheckRepository.class);
		posts = mock(PostRepository.class);
		analyzer = mock(ContentAutomationAnalyzer.class);
		quarantinePost = mock(ModerationQuarantinePostUseCase.class);
		notifications = mock(NotificationService.class);
		adminGuard = mock(AdminGuard.class);
		moderators = mock(ModeratorService.class);
		service = new ContentAutomationService(checks, posts, analyzer, quarantinePost, notifications, adminGuard,
			moderators, Clock.fixed(AGORA, ZoneOffset.UTC), true);

		when(checks.findByPost(POST)).thenReturn(Optional.empty());
		when(posts.findById(new PostId(POST))).thenReturn(Optional.of(post(TEXTO_SUSPEITO)));
		when(analyzer.modelName()).thenReturn("qwen3.8-27b");
		when(adminGuard.isAdmin(any(CurrentUser.class))).thenReturn(false);
		when(moderators.isModerator(AUTOR)).thenReturn(false);
	}

	@Test
	void automatedWithConfidenceGoesToQuarantineAndToReviewWithoutPunishing() {
		when(analyzer.analyze(TEXTO_SUSPEITO))
			.thenReturn(Optional.of(veredito(ContentAutomationVerdict.AUTOMATED, 0.93)));
		when(quarantinePost.quarantine(anyString(), eq(POST), anyString())).thenReturn(true);

		var outcome = service.evaluate(POST);

		assertThat(outcome.status()).isEqualTo("QUARANTINED");
		assertThat(outcome.quarantined()).isTrue();
		verify(quarantinePost).quarantine(eq(ContentAutomationService.ACTOR), eq(POST),
			org.mockito.ArgumentMatchers.contains("Automod de conteudo automatizado"));
		verify(notifications).create(eq(AUTOR), eq("MODERACAO"), eq(ContentAutomationService.ACTOR),
			anyString(), anyString(), anyString(), anyString());
		verify(checks).save(checagemCom(ReviewStatus.PENDING, ContentAutomationVerdict.AUTOMATED));
	}

	@Test
	void theAuthorIsToldItIsTemporaryAndNothingElse() {
		when(analyzer.analyze(TEXTO_SUSPEITO))
			.thenReturn(Optional.of(veredito(ContentAutomationVerdict.AUTOMATED, 0.95)));
		when(quarantinePost.quarantine(anyString(), anyString(), anyString())).thenReturn(true);

		service.evaluate(POST);

		// Precaução, não punição: o aviso fala de revisão, nunca de banimento.
		var corpo = org.mockito.ArgumentCaptor.forClass(String.class);
		verify(notifications).create(eq(AUTOR), eq("MODERACAO"), eq(ContentAutomationService.ACTOR), anyString(),
			corpo.capture(), anyString(), anyString());
		assertThat(corpo.getValue()).contains("revis").doesNotContainIgnoringCase("banid");
	}

	@Test
	void aHumanVerdictIsOnlyRecorded() {
		when(analyzer.analyze(TEXTO_SUSPEITO))
			.thenReturn(Optional.of(veredito(ContentAutomationVerdict.HUMAN, 0.95)));

		var outcome = service.evaluate(POST);

		assertThat(outcome.status()).isEqualTo("RECORDED");
		assertThat(outcome.quarantined()).isFalse();
		verify(checks).save(checagemCom(ReviewStatus.NONE, ContentAutomationVerdict.HUMAN));
		verifyNoInteractions(quarantinePost, notifications);
	}

	@Test
	void suspicionBelowTheConfidenceFloorDoesNotHideAnything() {
		when(analyzer.analyze(TEXTO_SUSPEITO))
			.thenReturn(Optional.of(veredito(ContentAutomationVerdict.AUTOMATED, 0.61)));

		var outcome = service.evaluate(POST);

		assertThat(outcome.status()).isEqualTo("RECORDED");
		assertThat(outcome.quarantined()).isFalse();
		verify(checks).save(checagemCom(ReviewStatus.NONE, ContentAutomationVerdict.AUTOMATED));
		verifyNoInteractions(quarantinePost, notifications);
	}

	@Test
	void textWithoutAnyMachineTraceNeverReachesTheModel() {
		when(posts.findById(new PostId(POST)))
			.thenReturn(Optional.of(post("acordei tarde, perdi o onibus e cheguei atrasado de novo")));

		var outcome = service.evaluate(POST);

		assertThat(outcome.status()).isEqualTo("CLEAN_DETERMINISTIC");
		verify(analyzer, never()).analyze(anyString());
		verifyNoInteractions(quarantinePost, notifications);
		verify(checks).save(checagemCom(ReviewStatus.NONE, ContentAutomationVerdict.HUMAN));
	}

	@Test
	void withoutAVerdictNothingHappens() {
		when(analyzer.analyze(TEXTO_SUSPEITO)).thenReturn(Optional.empty());

		var outcome = service.evaluate(POST);

		assertThat(outcome.status()).isEqualTo("NO_VERDICT");
		verifyNoInteractions(quarantinePost, notifications);
	}

	@Test
	void anAlreadyCheckedPostIsNotSentToTheModelAgain() {
		when(checks.findByPost(POST)).thenReturn(Optional.of(ContentAutomationCheck.of(POST, AUTOR,
			ContentAutomationVerdict.AUTOMATED, 0.9, "postura", "modelo", AGORA, ReviewStatus.PENDING)));

		var outcome = service.evaluate(POST);

		assertThat(outcome.status()).isEqualTo("ALREADY_CHECKED");
		assertThat(outcome.quarantined()).isTrue();
		verifyNoInteractions(analyzer, quarantinePost);
	}

	@Test
	void aPostThatIsNotActiveIsSkipped() {
		Post apagado = post(TEXTO_SUSPEITO);
		apagado.delete(AGORA);
		when(posts.findById(new PostId(POST))).thenReturn(Optional.of(apagado));

		assertThat(service.evaluate(POST).status()).isEqualTo("SKIP");
		verifyNoInteractions(analyzer, quarantinePost);
	}

	@Test
	void staffIsOutOfTheBarrier() {
		when(moderators.isModerator(AUTOR)).thenReturn(true);

		assertThat(service.evaluate(POST).status()).isEqualTo("STAFF_SKIPPED");
		verifyNoInteractions(analyzer, quarantinePost, notifications);
	}

	@Test
	void theSweepHelperDoesNotOfferPostsThatLeftTheAir() {
		Post quarentenado = post(TEXTO_SUSPEITO);
		quarentenado.quarantine();
		when(posts.findById(new PostId(POST))).thenReturn(Optional.of(quarentenado));

		assertThat(service.isActive(POST)).isFalse();
	}

	private ContentAutomationAnalyzer.Verdict veredito(ContentAutomationVerdict veredito, double confianca) {
		return new ContentAutomationAnalyzer.Verdict(veredito, confianca, "postura de assistente");
	}

	private ContentAutomationCheck checagemCom(ReviewStatus status, ContentAutomationVerdict veredito) {
		return org.mockito.ArgumentMatchers.argThat((ContentAutomationCheck check) ->
			check != null && check.postId().equals(POST) && check.status() == status && check.verdict() == veredito);
	}

	private Post post(String content) {
		return Post.publish(new PostId(POST), new UserId(AUTOR), PostType.INSIGHT, content, null, List.of(), List.of(),
			null, AGORA);
	}
}