package com.socialcoisa.rede.moderation.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.feed.application.PostPublishedEvent;
import com.socialcoisa.rede.feed.application.port.out.PostRepository;
import com.socialcoisa.rede.feed.domain.Post;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.PostType;
import com.socialcoisa.rede.feed.domain.UserId;

class MediaSafetyListenerTests {

	private final MediaSafetyService mediaSafety = mock();
	private final PostRepository posts = mock();

	@Test
	void queueRejectionDoesNotEscapeAfterThePostHasCommitted() {
		Executor rejected = task -> {
			throw new RejectedExecutionException("queue full");
		};
		MediaSafetyListener listener = new MediaSafetyListener(mediaSafety, posts, rejected);
		when(mediaSafety.enabled()).thenReturn(true);

		listener.onPostPublished(new PostPublishedEvent("post_1", "user_1"));

		verify(posts, never()).findById(any());
	}

	@Test
	void oneFailedMediaDoesNotPreventCheckingTheOthers() {
		Executor direct = Runnable::run;
		MediaSafetyListener listener = new MediaSafetyListener(mediaSafety, posts, direct);
		when(mediaSafety.enabled()).thenReturn(true);
		Post post = Post.publish(new PostId("post_1"), new UserId("user_1"), PostType.INSIGHT, "post", null,
			List.of(), List.of("media_1", "media_2"), null, Instant.parse("2026-10-30T12:00:00Z"));
		when(posts.findById(new PostId("post_1"))).thenReturn(Optional.of(post));
		when(mediaSafety.check("media_1")).thenThrow(new IllegalStateException("review unavailable"));

		listener.onPostPublished(new PostPublishedEvent("post_1", "user_1"));

		verify(mediaSafety).check("media_1");
		verify(mediaSafety).check("media_2");
	}
}
