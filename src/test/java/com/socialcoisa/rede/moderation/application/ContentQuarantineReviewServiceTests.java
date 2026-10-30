package com.socialcoisa.rede.moderation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.feed.application.port.in.ModerationQuarantinePostUseCase;
import com.socialcoisa.rede.feed.application.port.in.ModerationRemovePostUseCase;
import com.socialcoisa.rede.feed.application.port.out.PostRepository;
import com.socialcoisa.rede.feed.domain.Post;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.PostType;
import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.moderation.application.port.out.ContentAutomationCheckRepository;
import com.socialcoisa.rede.moderation.application.port.out.ReportedContentAuthorReader;
import com.socialcoisa.rede.moderation.domain.ContentAutomationCheck;
import com.socialcoisa.rede.moderation.domain.ContentAutomationCheck.ReviewStatus;
import com.socialcoisa.rede.moderation.domain.ContentAutomationVerdict;
import com.socialcoisa.rede.notifications.application.NotificationService;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;

/**
 * A fila de revisão da quarentena: as duas únicas saídas são liberar ou remover — e nenhuma delas
 * pune a conta. Quem pune é uma pessoa, por um fluxo próprio.
 */
class ContentQuarantineReviewServiceTests {

	private static final String POST = "post_revisao_1";
	private static final String AUTOR = "user_quarentena";
	private static final Instant AGORA = Instant.parse("2026-10-29T12:00:00Z");

	private ContentAutomationCheckRepository checks;
	private PostRepository posts;
	private ModerationQuarantinePostUseCase quarantinePost;
	private ModerationRemovePostUseCase removePost;
	private ReportedContentAuthorReader contentAuthors;
	private UserProfileRepository profiles;
	private NotificationService notifications;

	private ContentQuarantineReviewService service;

	@BeforeEach
	void preparar() {
		checks = mock(ContentAutomationCheckRepository.class);
		posts = mock(PostRepository.class);
		quarantinePost = mock(ModerationQuarantinePostUseCase.class);
		removePost = mock(ModerationRemovePostUseCase.class);
		contentAuthors = mock(ReportedContentAuthorReader.class);
		profiles = mock(UserProfileRepository.class);
		notifications = mock(NotificationService.class);
		service = new ContentQuarantineReviewService(checks, posts, quarantinePost, removePost, contentAuthors,
			profiles, notifications);

		when(checks.findByPost(POST)).thenReturn(Optional.of(checagem(ReviewStatus.PENDING)));
		when(posts.findById(new PostId(POST))).thenReturn(Optional.of(post()));
	}

	@Test
	void releasingPutsThePostBackAndClosesTheCase() {
		when(quarantinePost.release("mod_1", POST)).thenReturn(true);

		assertThat(service.release("mod_1", POST)).isTrue();

		verify(quarantinePost).release("mod_1", POST);
		verify(checks).updateStatus(POST, ReviewStatus.RELEASED);
		verify(removePost, never()).removeAsModerator(anyString(), anyString(), anyString());
		verify(notifications).create(eq(AUTOR), eq("MODERACAO"), eq("mod_1"), eq("Post liberado"), anyString(),
			anyString(), anyString());
	}

	@Test
	void confirmingTheViolationRemovesThePostWithoutPunishingTheAccount() {
		assertThat(service.remove("mod_1", POST)).isTrue();

		verify(removePost).removeAsModerator(eq("mod_1"), eq(POST), anyString());
		verify(checks).updateStatus(POST, ReviewStatus.REMOVED);
		verify(quarantinePost, never()).release(anyString(), anyString());
		verify(notifications).create(eq(AUTOR), eq("MODERACAO"), eq("mod_1"), eq("Post removido"), anyString(),
			anyString(), anyString());
	}

	@Test
	void anAlreadyDecidedCaseIsNotDecidedAgain() {
		when(checks.findByPost(POST)).thenReturn(Optional.of(checagem(ReviewStatus.RELEASED)));

		assertThat(service.release("mod_1", POST)).isFalse();
		assertThat(service.remove("mod_1", POST)).isFalse();

		verifyNoInteractions(quarantinePost, removePost, notifications);
	}

	@Test
	void anEvaluationThatNeverHidThePostIsNotInTheQueue() {
		// Veredito humano fica registrado com NONE: moderador não tem nada para liberar ali.
		when(checks.findByPost(POST)).thenReturn(Optional.of(checagem(ReviewStatus.NONE)));

		assertThat(service.release("mod_1", POST)).isFalse();
		verifyNoInteractions(quarantinePost, removePost);
	}

	@Test
	void theQueueShowsThePostAndWhoWroteIt() {
		when(checks.findPending(20)).thenReturn(List.of(checagem(ReviewStatus.PENDING)));
		when(profiles.findById(AUTOR)).thenReturn(Optional.of(perfil()));

		var fila = service.pending(20);

		assertThat(fila).hasSize(1);
		assertThat(fila.getFirst().postId()).isEqualTo(POST);
		assertThat(fila.getFirst().authorUsername()).isEqualTo("mel");
		assertThat(fila.getFirst().content()).contains("Aqui esta o texto");
		assertThat(fila.getFirst().verdict()).isEqualTo("AUTOMATED");
	}

	@Test
	void aReleaseComingFromTheReportQueueAlsoClosesTheCase() {
		// A mesma publicação pode chegar aos dois caminhos. Quando a denúncia é arquivada, o post
		// volta ao ar e o caso sai da fila da quarentena — senão o moderador decidiria duas vezes.
		when(quarantinePost.release("mod_1", POST)).thenReturn(true);

		assertThat(service.releaseByReport("mod_1", POST)).isTrue();

		verify(checks).updateStatus(POST, ReviewStatus.RELEASED);
		// Quem avisa o autor é o fluxo da denúncia: aqui não se notifica de novo.
		verifyNoInteractions(notifications);
	}

	@Test
	void theReportQueueDoesNotTouchACaseThatWasNeverInQuarantine() {
		when(checks.findByPost(POST)).thenReturn(Optional.of(checagem(ReviewStatus.NONE)));

		service.releaseByReport("mod_1", POST);

		verify(checks, never()).updateStatus(anyString(), org.mockito.ArgumentMatchers.any());
	}

	private ContentAutomationCheck checagem(ReviewStatus status) {
		return ContentAutomationCheck.of(POST, AUTOR, ContentAutomationVerdict.AUTOMATED, 0.93,
			"postura de assistente", "qwen3.8-27b", AGORA, status);
	}

	private Post post() {
		return Post.publish(new PostId(POST), new UserId(AUTOR), PostType.INSIGHT,
			"Aqui esta o texto que voce pediu.", null, List.of(), List.of(), null, AGORA);
	}

	private UserProfile perfil() {
		return UserProfile.create("perfil_1", "mel", "mel@test.com", "Mel", null, AGORA);
	}
}