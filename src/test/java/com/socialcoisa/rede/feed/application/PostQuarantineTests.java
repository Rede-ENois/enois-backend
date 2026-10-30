package com.socialcoisa.rede.feed.application;

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

import com.socialcoisa.rede.feed.application.port.out.AuditLogRepository;
import com.socialcoisa.rede.feed.application.port.out.FeedCacheRepository;
import com.socialcoisa.rede.feed.application.port.out.OutboxEventRepository;
import com.socialcoisa.rede.feed.application.port.out.PostRepository;
import com.socialcoisa.rede.feed.domain.Post;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.PostStatus;
import com.socialcoisa.rede.feed.domain.PostType;
import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.media.application.port.in.RequestPostMediaDeletionUseCase;

/**
 * Quarentena no serviço do feed: o post sai do ar SEM ser apagado e volta se a revisão liberar.
 *
 * <p>É o contrário da remoção — e a diferença precisa ser visível no código: quarentenar não gera
 * evento de exclusão, não pede remoção de mídia e não apaga republicação.</p>
 */
class PostQuarantineTests {

	private static final String POST = "post_quarentena";
	private static final String AUTOR = "user_autora";
	private static final Instant AGORA = Instant.parse("2026-10-29T12:00:00Z");

	private PostRepository posts;
	private FeedCacheRepository cache;
	private OutboxEventRepository outbox;
	private AuditLogRepository audit;
	private RequestPostMediaDeletionUseCase mediaDeletion;

	private DeletePostService service;
	private Post post;

	@BeforeEach
	void preparar() {
		posts = mock(PostRepository.class);
		cache = mock(FeedCacheRepository.class);
		outbox = mock(OutboxEventRepository.class);
		audit = mock(AuditLogRepository.class);
		mediaDeletion = mock(RequestPostMediaDeletionUseCase.class);
		service = new DeletePostService(posts, cache, outbox, audit, mediaDeletion,
			Clock.fixed(AGORA, ZoneOffset.UTC));
		post = Post.publish(new PostId(POST), new UserId(AUTOR), PostType.INSIGHT, "texto do post", null,
			List.of(), List.of("media_1"), null, AGORA);
		when(posts.findById(new PostId(POST))).thenReturn(Optional.of(post));
	}

	@Test
	void quarantineTakesThePostOffTheAirWithoutDeletingIt() {
		assertThat(service.quarantine("automod:conteudo", POST, "revisao automatica")).isTrue();

		assertThat(post.status()).isEqualTo(PostStatus.QUARANTINED);
		assertThat(post.deletedAt()).isNull();
		verify(posts).save(post);
		// Some do feed na hora, mas NADA é apagado: sem evento de exclusão e sem fila de mídia.
		verify(cache).removeEverywhere(post.id());
		verifyNoInteractions(outbox, mediaDeletion);
		verify(audit).record(any(UserId.class), eq("POST_QUARANTINED"), eq(POST), eq("revisao automatica"));
	}

	@Test
	void releasePutsItBackExactlyAsItWas() {
		service.quarantine("automod:conteudo", POST, "revisao automatica");

		assertThat(service.release("mod_1", POST)).isTrue();

		assertThat(post.status()).isEqualTo(PostStatus.ACTIVE);
		verify(audit).record(any(UserId.class), eq("POST_QUARANTINE_RELEASED"), eq(POST), eq(null));
	}

	@Test
	void aQuarantinedPostCannotBeQuarantinedTwice() {
		post.quarantine();

		assertThat(service.quarantine("automod:conteudo", POST, "repetido")).isFalse();
		verify(posts, never()).save(any());
	}

	@Test
	void releasingAPostThatIsNotQuarantinedIsANoOp() {
		assertThat(service.release("mod_1", POST)).isFalse();
		assertThat(post.status()).isEqualTo(PostStatus.ACTIVE);
		verify(posts, never()).save(any());
	}

	@Test
	void aQuarantinedPostCanStillBeRemovedByAModerator() {
		// "Violação confirmada" na revisão humana: o desfecho do caso é remoção, não ban.
		post.quarantine();
		when(mediaDeletion.requestPostMediaDeletion(any(), any(), any())).thenReturn(List.of());
		when(posts.findActiveRepostsBySource(POST)).thenReturn(List.of());

		service.removeAsModerator("mod_1", POST, "REVIEW: conteudo automatizado");

		assertThat(post.status()).isEqualTo(PostStatus.DELETED);
		verify(outbox).append(eq("PostDeleted"), eq(POST), anyString());
		verify(audit).record(any(UserId.class), eq("POST_DELETED_BY_MODERATION"), eq(POST), anyString());
	}

	@Test
	void theAuthorCanStillDeleteTheirOwnQuarantinedPost() {
		// Quarentena não aprisiona o conteúdo: o autor continua podendo apagar o que é dele.
		post.quarantine();
		when(mediaDeletion.requestPostMediaDeletion(any(), any(), any())).thenReturn(List.of());
		when(posts.findActiveRepostsBySource(POST)).thenReturn(List.of());

		var resultado = service.delete(new DeletePostCommand(new UserId(AUTOR), new PostId(POST), "quero apagar"));

		assertThat(resultado.status()).isEqualTo("DELETED");
		assertThat(post.status()).isEqualTo(PostStatus.DELETED);
	}
}