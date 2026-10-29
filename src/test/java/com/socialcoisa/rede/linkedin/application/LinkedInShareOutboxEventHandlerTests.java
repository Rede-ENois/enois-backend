package com.socialcoisa.rede.linkedin.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.feed.application.port.out.PostRepository;
import com.socialcoisa.rede.feed.domain.Post;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.PostType;
import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.linkedin.application.port.out.LinkedInConnectionRepository;
import com.socialcoisa.rede.linkedin.application.port.out.LinkedInShareClient;
import com.socialcoisa.rede.linkedin.domain.LinkedInConnection;
import com.socialcoisa.rede.shared.security.SecretCipher;

/**
 * O handler do outbox: so PostPublished vira share, MOMENT fica de fora (e efemero no Enois) e
 * evento de outro tipo nao publica nada.
 */
class LinkedInShareOutboxEventHandlerTests {

	private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");
	private static final String URL = "https://enois.ia.br/post/";

	private final SecretCipher cipher = new SecretCipher(SecretCipher.generateKeyBase64());
	private final InMemoryConnections connections = new InMemoryConnections();
	private final RecordingClient client = new RecordingClient();
	private final FakePosts posts = new FakePosts();
	private final LinkedInIntegrationService service = new LinkedInIntegrationService(connections, client, cipher,
		Clock.fixed(NOW, ZoneOffset.UTC), "https://enois.ia.br");
	private final LinkedInShareOutboxEventHandler handler = new LinkedInShareOutboxEventHandler(posts, service);

	@Test
	void postPublicadoViraShare() {
		connections.save(new LinkedInConnection("u1", "sub-1", cipher.encrypt("token"), NOW.plusSeconds(3600),
			true, NOW, NOW, null, null));
		posts.post = Post.publish(new PostId("post_1"), new UserId("u1"), PostType.INSIGHT, "meu texto", null,
			List.of(), List.of(), null, NOW);

		handler.handle("PostPublished", "post_1", "{}");

		assertThat(client.calls).hasSize(1);
		assertThat(client.calls.get(0)).startsWith("token|sub-1|").contains(URL + "post_1");
	}

	@Test
	void momentoNaoVaiParaOLinkedIn() {
		connections.save(new LinkedInConnection("u1", "sub-1", cipher.encrypt("token"), NOW.plusSeconds(3600),
			true, NOW, NOW, null, null));
		posts.post = Post.publish(new PostId("post_2"), new UserId("u1"), PostType.MOMENT, "some em 24h", null,
			List.of(), List.of(), null, NOW);

		handler.handle("PostPublished", "post_2", "{}");

		assertThat(client.calls).isEmpty();
	}

	@Test
	void outroEventoNaoPublica() {
		posts.post = Post.publish(new PostId("post_3"), new UserId("u1"), PostType.INSIGHT, "texto", null,
			List.of(), List.of(), null, NOW);

		handler.handle("PostCommented", "post_3", "{}");

		assertThat(client.calls).isEmpty();
	}

	private static final class RecordingClient implements LinkedInShareClient {
		private final List<String> calls = new ArrayList<>();

		@Override
		public com.socialcoisa.rede.linkedin.application.port.out.LinkedInShareClient.ShareOutcome share(
				String accessToken, String memberSub, String commentary, String url, String title) {
			calls.add(accessToken + "|" + memberSub + "|" + commentary);
			return com.socialcoisa.rede.linkedin.application.port.out.LinkedInShareClient.ShareOutcome.ok();
		}
	}

	private static final class InMemoryConnections implements LinkedInConnectionRepository {
		private final Map<String, LinkedInConnection> byUser = new HashMap<>();

		@Override
		public Optional<LinkedInConnection> findByUserId(String userId) {
			return Optional.ofNullable(byUser.get(userId));
		}

		@Override
		public void save(LinkedInConnection connection) {
			byUser.put(connection.userId(), connection);
		}

		@Override
		public void deleteByUserId(String userId) {
			byUser.remove(userId);
		}
	}

	private static final class FakePosts implements PostRepository {
		private Post post;

		@Override
		public List<Post> findAllByAuthor(com.socialcoisa.rede.feed.domain.UserId authorId) {
			return List.of();
		}

		@Override
		public List<Post> findByMediaId(String mediaId) {
			return List.of();
		}

		@Override
		public Optional<Post> findById(PostId id) {
			return post != null && post.id().equals(id) ? Optional.of(post) : Optional.empty();
		}

		@Override
		public Post save(Post post) {
			throw new UnsupportedOperationException();
		}

		@Override
		public List<Post> findExpiredMoments(Instant now, int limit) {
			throw new UnsupportedOperationException();
		}

		@Override
		public List<Post> findByIds(Collection<PostId> ids) {
			throw new UnsupportedOperationException();
		}

		@Override
		public List<Post> findActiveByAuthors(Set<UserId> authorIds, Instant before, int limit) {
			throw new UnsupportedOperationException();
		}

		@Override
		public List<Post> findActiveByAuthor(UserId authorId, Instant before, int limit) {
			throw new UnsupportedOperationException();
		}

		@Override
		public int countActiveByAuthor(UserId authorId) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Optional<Post> findActiveRepostByAuthorAndSource(UserId authorId, String sourcePostId) {
			return Optional.empty();
		}

		@Override
		public List<Post> findActiveRepostsBySource(String sourcePostId) {
			return List.of();
		}

		@Override
		public List<Post> findActiveByCommunity(String communityId, Instant before, int limit) {
			throw new UnsupportedOperationException();
		}

		@Override
		public List<Post> findActiveByChannel(String channelId, Instant before, int limit) {
			return List.of();
		}

		@Override
		public List<Post> findActiveCorporateByAuthor(UserId authorId, Instant before, int limit) {
			return List.of();
		}

		public List<Post> findActiveCorporate(Instant before, int limit) {
			return List.of();
		}

		public List<Post> findActiveAllExcludingTopics(Collection<String> blockedTopics, Instant before, int limit) {
			return List.of();
		}

		public List<Post> findActiveByAuthorsExcludingTopics(Set<UserId> authorIds, Collection<String> blockedTopics,
				Instant before, int limit) {
			return List.of();
		}

		public List<Post> findActiveAll(Instant before, int limit) {
			throw new UnsupportedOperationException();
		}

		@Override
		public List<Post> findActiveByType(PostType type, Instant before, int limit) {
			throw new UnsupportedOperationException();
		}

		@Override
		public List<Post> findTrending(Instant since, int limit) {
			return List.of();
		}

		@Override
		public List<Post> findActiveByTypes(Set<PostType> types, Instant before, int limit) {
			return List.of();
		}
	}
}
