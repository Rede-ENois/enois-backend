package com.socialcoisa.rede.notifications.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.socialcoisa.rede.feed.application.port.out.CommentRepository;
import com.socialcoisa.rede.feed.application.port.out.PostRepository;
import com.socialcoisa.rede.feed.domain.AuthorIdentity;
import com.socialcoisa.rede.feed.domain.Comment;
import com.socialcoisa.rede.feed.domain.CommentStatus;
import com.socialcoisa.rede.feed.domain.Post;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.PostType;
import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;

/**
 * Issue #392: a notificação do feed leva o trecho do conteúdo de origem, não só a frase
 * "Fulana comentou no seu post.".
 */
class NotificationOutboxPreviewTests {

	private static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");
	private static final String AUTOR = "autor";
	private static final String OUTRA = "outra";

	private final NotificationService notifications = mock(NotificationService.class);
	private final PostRepository posts = mock(PostRepository.class);
	private final CommentRepository comments = mock(CommentRepository.class);
	private final UserProfileRepository profiles = mock(UserProfileRepository.class);
	private final NotificationPreferenceService preferences = mock(NotificationPreferenceService.class);
	private final NotificationOutboxEventHandler handler = new NotificationOutboxEventHandler(notifications, posts,
		comments, profiles, preferences);

	/** Sem silêncio ligado, os testes de caminho feliz valem como hoje. */
	private void semSilencio() {
		when(preferences.silences(org.mockito.ArgumentMatchers.anyString(),
			org.mockito.ArgumentMatchers.anyString())).thenReturn(false);
	}

	private Post post(String content) {
		return Post.publish(new PostId("post_1"), new UserId(AUTOR), PostType.INSIGHT, content, null, List.of(), List.of(),
			null, NOW, AuthorIdentity.SOCIAL);
	}

	@Test
	void commentNotificationCarriesTheCommentText() {
		semSilencio();
		when(posts.findById(new PostId("post_1"))).thenReturn(Optional.of(post("post do autor")));
		when(comments.findById("comment_1")).thenReturn(Optional.of(new Comment("comment_1", new PostId("post_1"),
			new UserId(OUTRA), null, "  **muito bom**, parabéns!  ", List.of(), NOW, CommentStatus.ACTIVE, null, false,
			false)));

		handler.handle("PostCommented", "post_1",
			"{\"postId\":\"post_1\",\"commentId\":\"comment_1\",\"authorId\":\"outra\"}");

		ArgumentCaptor<String> preview = ArgumentCaptor.forClass(String.class);
		verify(notifications).create(eq(AUTOR), eq("NEW_COMMENT"), eq(OUTRA), eq("Novo comentário"), anyString(),
			eq("/api/feed/posts/post_1?comentario=comment_1"), eq("Ver"), preview.capture());
		assertThat(preview.getValue()).isEqualTo("muito bom, parabéns!");
	}

	/** O clique abre O COMENTÁRIO, não o post inteiro (issue #1022). */
	@Test
	void commentNotificationLinksToTheComment() {
		semSilencio();
		when(posts.findById(new PostId("post_1"))).thenReturn(Optional.of(post("texto do post")));
		when(comments.findById("comment_1")).thenReturn(Optional.of(new Comment("comment_1", new PostId("post_1"),
			new UserId(OUTRA), null, "achei útil", List.of(), NOW, CommentStatus.ACTIVE, null, false, false)));

		handler.handle("PostCommented", "post_1",
			"{\"postId\":\"post_1\",\"commentId\":\"comment_1\",\"authorId\":\"outra\"}");

		verify(notifications).create(eq(AUTOR), eq("NEW_COMMENT"), eq(OUTRA), anyString(), anyString(),
			eq("/api/feed/posts/post_1?comentario=comment_1"), anyString(), anyString());
	}

	@Test
	void commentOnlyWithMediaFallsBackToThePostText() {
		semSilencio();
		when(posts.findById(new PostId("post_1"))).thenReturn(Optional.of(post("como fiz meu café")));
		when(comments.findById("comment_1")).thenReturn(Optional.of(new Comment("comment_1", new PostId("post_1"),
			new UserId(OUTRA), null, "![foto](https://cdn/foto.png)", List.of(), NOW, CommentStatus.ACTIVE, null, false,
			false)));

		handler.handle("PostCommented", "post_1",
			"{\"postId\":\"post_1\",\"commentId\":\"comment_1\",\"authorId\":\"outra\"}");

		ArgumentCaptor<String> preview = ArgumentCaptor.forClass(String.class);
		verify(notifications).create(eq(AUTOR), eq("NEW_COMMENT"), eq(OUTRA), anyString(), anyString(), anyString(),
			anyString(), preview.capture());
		assertThat(preview.getValue()).isEqualTo("como fiz meu café");
	}

	@Test
	void reactionNotificationCarriesThePostText() {
		semSilencio();
		when(posts.findById(new PostId("post_1"))).thenReturn(Optional.of(post("<p>dica de <b>Kotlin</b></p>")));

		handler.handle("PostReacted", "post_1", "{\"postId\":\"post_1\",\"authorId\":\"outra\"}");

		ArgumentCaptor<String> preview = ArgumentCaptor.forClass(String.class);
		verify(notifications).createGrouped(eq(AUTOR), eq("NEW_REACTION"), anyString(), eq(OUTRA), anyString(),
			anyString(), any(), anyString(), anyString(), eq("Ver"), preview.capture());
		assertThat(preview.getValue()).isEqualTo("dica de Kotlin");
	}

	@Test
	void commentReactionLeavesPreviewEmptyWhenTheCommentHasNoText() {
		semSilencio();
		when(comments.findById("comment_1")).thenReturn(Optional.of(new Comment("comment_1", new PostId("post_1"),
			new UserId(AUTOR), null, "<video src=\"x.mp4\"></video>", List.of(), NOW, CommentStatus.ACTIVE, null, false,
			false)));

		handler.handle("CommentReacted", "comment_1", "{\"commentId\":\"comment_1\",\"authorId\":\"outra\"}");

		verify(notifications).createGrouped(eq(AUTOR), eq("COMMENT_REACTION"), anyString(), eq(OUTRA), anyString(),
			anyString(), any(), isNull(), isNull(), eq("Ver"), isNull());
	}

	/** O silêncio ligado para no handler: o aviso nem nasce (nada na caixa nem push). */
	@Test
	void reactionSilenceStopsTheNotificationBeforeItExists() {
		semSilencio();
		when(preferences.silences(AUTOR, "NEW_REACTION")).thenReturn(true);
		when(preferences.silences(AUTOR, "COMMENT_REACTION")).thenReturn(true);
		when(posts.findById(new PostId("post_1"))).thenReturn(Optional.of(post("texto")));
		when(comments.findById("comment_1")).thenReturn(Optional.of(new Comment("comment_1", new PostId("post_1"),
			new UserId(AUTOR), null, "conteúdo", List.of(), NOW, CommentStatus.ACTIVE, null, false, false)));

		handler.handle("PostReacted", "post_1", "{\"postId\":\"post_1\",\"authorId\":\"outra\"}");
		handler.handle("CommentReacted", "comment_1", "{\"commentId\":\"comment_1\",\"authorId\":\"outra\"}");

		verify(notifications, never()).create(anyString(), anyString(), anyString(), anyString(), anyString(),
			anyString(), anyString(), any());
		verify(notifications, never()).createGrouped(anyString(), anyString(), anyString(), anyString(), anyString(),
			anyString(), any(), any(), any(), anyString(), any());
		verify(profiles, never()).findById(anyString());
	}

	@Test
	void postCommentSilenceStopsTheNotificationBeforeItExists() {
		semSilencio();
		when(preferences.silences(AUTOR, "NEW_COMMENT")).thenReturn(true);
		when(posts.findById(new PostId("post_1"))).thenReturn(Optional.of(post("texto")));

		handler.handle("PostCommented", "post_1",
			"{\"postId\":\"post_1\",\"commentId\":\"comment_1\",\"authorId\":\"outra\"}");

		verify(notifications, never()).create(anyString(), anyString(), anyString(), anyString(), anyString(),
			anyString(), anyString(), any());
		verify(profiles, never()).findById(anyString());
	}

	/** O silêncio é por destinatário: quem não silenciou continua recebendo. */
	@Test
	void silenceOfOneAuthorDoesNotAffectAnotherAuthor() {
		semSilencio();
		when(preferences.silences(OUTRA, "NEW_REACTION")).thenReturn(true);
		when(posts.findById(new PostId("post_1"))).thenReturn(Optional.of(post("texto")));

		handler.handle("PostReacted", "post_1", "{\"postId\":\"post_1\",\"authorId\":\"outra\"}");

		verify(notifications).createGrouped(eq(AUTOR), eq("NEW_REACTION"), anyString(), eq(OUTRA), anyString(),
			anyString(), any(), anyString(), anyString(), eq("Ver"), anyString());
	}
}
