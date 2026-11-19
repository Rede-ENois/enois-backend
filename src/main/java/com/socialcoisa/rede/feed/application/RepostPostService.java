package com.socialcoisa.rede.feed.application;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.feed.application.port.out.PostCommunityReader;
import com.socialcoisa.rede.feed.application.port.out.PostRepository;
import com.socialcoisa.rede.feed.application.port.out.PostRepostRepository;
import com.socialcoisa.rede.feed.domain.AuthorIdentity;
import com.socialcoisa.rede.feed.domain.Post;
import com.socialcoisa.rede.feed.domain.PostCounter;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.PostRepost;
import com.socialcoisa.rede.feed.domain.PostStatus;
import com.socialcoisa.rede.feed.domain.PostType;
import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.notifications.application.NotificationService;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.shared.age.AgeGate;
import com.socialcoisa.rede.shared.sanctions.SanctionGate;
import com.socialcoisa.rede.shared.security.ActionThrottle;
import com.socialcoisa.rede.shared.text.PlainText;
import com.socialcoisa.rede.shared.web.ApiException;

@Service
public class RepostPostService {

	private static final Set<PostType> NOT_REPOSTABLE = Set.of(PostType.BUSINESS,
			PostType.MOMENT, PostType.INSTANTE);
	private static final Set<PostType> OPINION_ONLY = Set.of(PostType.ARTICLE,
			PostType.BLOG, PostType.PAGE);
	private static final int PER_HOUR = 30;

	private final PostRepository posts;
	private final PostRepostRepository reposts;
	private final UserProfileRepository profiles;
	private final NotificationService notifications;
	private final ActionThrottle throttle;
	private final AgeGate ageGate;
	private final SanctionGate sanctions;
	private final PostCommunityReader postCommunityReader;
	private final Clock clock;

	public RepostPostService(PostRepository posts, PostRepostRepository reposts,
			UserProfileRepository profiles, NotificationService notifications,
			ActionThrottle throttle,
			AgeGate ageGate, SanctionGate sanctions, PostCommunityReader postCommunityReader, Clock clock) {
		this.posts = posts;
		this.reposts = reposts;
		this.profiles = profiles;
		this.notifications = notifications;
		this.throttle = throttle;
		this.ageGate = ageGate;
		this.sanctions = sanctions;
		this.postCommunityReader = postCommunityReader;
		this.clock = clock;
	}

	@Transactional
	public Post repostPure(String userId, String postId) {
		ageGate.requireCanWrite(userId);
		sanctions.requireNotRestricted(userId);
		throttle.exigePermitido("repost", userId, PER_HOUR, Duration.ofHours(1));

		Post root = resolveRoot(postId);
		validateRepostable(root);
		if (posts.lockForUpdate(root.id()).filter(status -> status == PostStatus.ACTIVE).isEmpty()) {
			throw new ApiException(404, "POST_NOT_FOUND", "Post nao encontrado.");
		}

		if (reposts.exists(userId, root.id().value())) {
			throw new ApiException(409, "ALREADY_REPOSTED", "Voce ja republicou este post.");
		}
		if (posts.findActiveRepostByAuthorAndSource(new UserId(userId), root.id().value()).isPresent()) {
			throw new ApiException(409, "ALREADY_REPOSTED", "Voce ja republicou este post.");
		}
		// E com o repost com opinião: ele é outro post, mas o ponteiro do feed mostra o original — a
		// citação teria um "Repostar" extra ao lado do repost puro, e o banco aceita um segundo
		// ponteiro.
		if (posts.findActiveRepostContentByAuthorAndSource(new UserId(userId), root.id().value()).isPresent()) {
			throw new ApiException(409, "ALREADY_REPOSTED", "Voce ja republicou este post.");
		}

		reposts.save(PostRepost.create(userId, root.id().value(), clock.instant()));
		posts.adjustCounter(root.id(), PostCounter.REPOSTS, 1);
		root.refreshCounters(posts.counters(root.id()));
		notifyOriginalAuthor(root, userId, "/api/feed/posts/" + root.id().value());
		return root;
	}

	@Transactional
	public void undoRepost(String userId, String postId) {
		PostId id = new PostId(postId);
		posts.lockForUpdate(id);
		if (reposts.exists(userId, postId)) {
			reposts.delete(userId, postId);
			posts.adjustCounter(id, PostCounter.REPOSTS, -1);
		}
	}

	/**
	 * Repost com opinião: cria post novo do repostador citando a publicação que a pessoa repostou.
	 *
	 * A citação é a própria publicação escolhida, não o original raiz: quem reposta o repost com
	 * opinião de outra pessoa está concordando ou discordando dela, então o card precisa mostrar
	 * essa opinião. O dedupe também é por ela — o mesmo texto sobre o mesmo post já existe; outro
	 * texto é outra opinião e pode entrar. Antes o dedupe olhava o original raiz para todo mundo, e
	 * quem já tinha repostado o post deixava de conseguir comentar as opiniões dos outros.
	 */
	@Transactional
	public Post repostWithOpinion(String userId, String postId, String opinion) {
		return repostWithOpinion(userId, postId, opinion, null);
	}

	@Transactional
	public Post repostWithOpinion(String userId, String postId, String opinion, String communityId) {
		ageGate.requireCanWrite(userId);
		sanctions.requireNotRestricted(userId);
		throttle.exigePermitido("repost", userId, PER_HOUR, Duration.ofHours(1));

		Post root = resolveRoot(postId);
		validateRepostable(root);

		String cleanOpinion = opinion == null ? "" : PlainText.defuse(opinion.trim());
		if (cleanOpinion.isBlank()) {
			throw new ApiException(400, "OPINION_REQUIRED", "Escreva um comentario para republicar com opiniao.");
		}
		if (cleanOpinion.length() > 600) {
			throw new ApiException(400, "OPINION_TOO_LONG", "A opiniao do repost deve ter no maximo 600 caracteres.");
		}

		// Mesmo texto sobre o mesmo post: repetir não acrescenta nada ao feed. Há mais de uma
		// publicação com o nome repostado (o caso da issue #755): não dá para dizer qual é a
		// repetida, e duas linhas com o mesmo par autor+posto quebram a leitura de dedupe. Recusa em
		// vez de gravar — o problema é de dados, não da pessoa.
		if (posts.countActiveRepostsByAuthorAndSource(new UserId(userId), postId) > 1) {
			throw new ApiException(409, "REPOST_AMBIGUOUS",
				"Ja existe mais de uma republicacao sua deste post. Edite a que quiser manter.");
		}
		boolean sameText = posts.findActiveRepostContentByAuthorAndSource(new UserId(userId), postId)
			.map(cleanOpinion::equals).orElse(false);
		if (sameText) {
			throw new ApiException(409, "ALREADY_REPOSTED", "Voce ja republicou este post.");
		}

		String username = profiles.findById(root.authorId().value())
				.map(profile -> profile.username())
				.orElse(null);
		boolean opinionOnly = OPINION_ONLY.contains(root.type());
		Post copy = Post.publish(new PostId("post_" + UUID.randomUUID().toString().replace("-", "")),
				new UserId(userId), opinionOnly ? PostType.INSIGHT : root.type(), cleanOpinion, null, List.of(),
				List.of(), communityId, null, clock.instant(), AuthorIdentity.SOCIAL)
				.markRepostOf(postId, username, true);
		Post saved = posts.save(copy);
		notifyOriginalAuthor(root, userId, "/api/feed/posts/" + saved.id().value());
		return saved;
	}

	private void notifyOriginalAuthor(Post root, String reposterId, String href) {
		String authorId = root.authorId().value();
		if (authorId.equals(reposterId)) {
			return;
		}
		String actorName = profiles.findById(reposterId)
				.map(profile -> profile.displayWithNickname() != null ? profile.displayWithNickname() : profile.name())
				.orElse("Alguém");
		notifications.create(authorId, "POST_REPOSTED", reposterId, "Seu post foi republicado",
				actorName + " republicou seu post.", href, "Ver");
	}

	@Transactional
	public Post repost(String userId, String postId, String opinion) {
		return repost(userId, postId, opinion, null);
	}

	@Transactional
	public Post repost(String userId, String postId, String opinion, String communityId) {
		String cleanOpinion = opinion == null ? "" : opinion.trim();
		if (communityId != null && !communityId.isBlank()) {
			String commId = communityId.trim();
			validateCommunityTarget(userId, commId);
			if (cleanOpinion.isBlank()) {
				throw new ApiException(400, "COMMUNITY_REQUIRES_OPINION",
						"Escreva um comentario para republicar na comunidade.");
			}
			return repostWithOpinion(userId, postId, cleanOpinion, commId);
		}

		if (cleanOpinion.isBlank()) {
			repostPure(userId, postId);
			return resolveRoot(postId);
		}
		return repostWithOpinion(userId, postId, cleanOpinion, null);
	}

	private void validateCommunityTarget(String userId, String communityId) {
		PostCommunityReader.CommunityRef ref = postCommunityReader.byIds(List.of(communityId), userId).get(communityId);
		if (ref == null) {
			throw new ApiException(404, "COMMUNITY_NOT_FOUND", "Comunidade nao encontrada.");
		}
		if (!ref.joined()) {
			throw new ApiException(403, "NOT_A_MEMBER", "Entre na comunidade para publicar nela.");
		}
	}

	private Post resolveRoot(String postId) {
		Post original = posts.findById(new PostId(postId))
				.filter(Post::isActive)
				.orElseThrow(() -> new ApiException(404, "POST_NOT_FOUND", "Post nao encontrado."));
		if (original.repostOf() != null) {
			return posts.findById(new PostId(original.repostOf()))
					.filter(Post::isActive)
					.orElseThrow(() -> new ApiException(404, "POST_NOT_FOUND", "Post original nao encontrado."));
		}
		return original;
	}

    private void validateRepostable(Post root) {
        if (root.communityId() != null || root.channelId() != null) {
            throw new ApiException(400, "PRIVATE_POST",
                    "Posts de comunidades e canais privados nao podem ser republicados no feed.");
        }
        if (NOT_REPOSTABLE.contains(root.type())) {
            throw new ApiException(400, "NOT_REPOSTABLE", "Este tipo de publicacao nao pode ser republicado.");
        }
    }
}
