package com.socialcoisa.rede.feed.application;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.feed.domain.AuthorIdentity;
import com.socialcoisa.rede.feed.domain.Post;
import com.socialcoisa.rede.feed.domain.PostCounter;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.PostRepost;
import com.socialcoisa.rede.feed.domain.PostStatus;
import com.socialcoisa.rede.feed.domain.PostType;
import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.feed.application.port.out.PostRepository;
import com.socialcoisa.rede.feed.application.port.out.PostRepostRepository;
import com.socialcoisa.rede.notifications.application.NotificationService;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.shared.age.AgeGate;
import com.socialcoisa.rede.shared.security.ActionThrottle;
import com.socialcoisa.rede.shared.text.PlainText;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Republicar em duas portas:
 *
 * 1. Repost puro (sem opinião): registra na tabela `post_reposts`. O post original aparece no
 *    feed de quem repostou com "Repostado por X". Likes e comentários ficam no original.
 *    Não cria post novo — é um ponteiro.
 *
 * 2. Repost com opinião: cria um post novo do repostador, com o original citado embaixo.
 *    Likes e comentários são do repostador. Funciona como antes.
 */
@Service
public class RepostPostService {

	private static final Set<PostType> NOT_REPOSTABLE = Set.of(PostType.BUSINESS, PostType.MOMENT);
	private static final Set<PostType> OPINION_ONLY = Set.of(PostType.ARTICLE, PostType.BLOG, PostType.PAGE);
	private static final int PER_HOUR = 30;

	private final PostRepository posts;
	private final PostRepostRepository reposts;
	private final UserProfileRepository profiles;
	private final NotificationService notifications;
	private final ActionThrottle throttle;
	private final AgeGate ageGate;
	private final Clock clock;

	public RepostPostService(PostRepository posts, PostRepostRepository reposts,
			UserProfileRepository profiles, NotificationService notifications, ActionThrottle throttle,
			AgeGate ageGate, Clock clock) {
		this.posts = posts;
		this.reposts = reposts;
		this.profiles = profiles;
		this.notifications = notifications;
		this.throttle = throttle;
		this.ageGate = ageGate;
		this.clock = clock;
	}

	/**
	 * Repost puro: registra ponteiro, não cria post. Retorna o post original (que é o que
	 * aparece no feed com o badge "Repostado por").
	 */
	@Transactional
	public Post repostPure(String userId, String postId) {
		ageGate.requireCanWrite(userId);
		throttle.exigePermitido("repost", userId, PER_HOUR, Duration.ofHours(1));

		Post root = resolveRoot(postId);
		validateRepostable(root);
		// Lock before reading "already reposted?" and before the insert (see PostRepository#lockForUpdate).
		if (posts.lockForUpdate(root.id()).filter(status -> status == PostStatus.ACTIVE).isEmpty()) {
			throw new ApiException(404, "POST_NOT_FOUND", "Post nao encontrado.");
		}

		if (reposts.exists(userId, root.id().value())) {
			throw new ApiException(409, "ALREADY_REPOSTED", "Voce ja republicou este post.");
		}
		// Impede duplicata com reposts antigos (post-cópia) também.
		if (posts.findActiveRepostByAuthorAndSource(new UserId(userId), root.id().value()).isPresent()) {
			throw new ApiException(409, "ALREADY_REPOSTED", "Voce ja republicou este post.");
		}

		reposts.save(PostRepost.create(userId, root.id().value(), clock.instant()));
		posts.adjustCounter(root.id(), PostCounter.REPOSTS, 1);
		root.refreshCounters(posts.counters(root.id()));
		notifyOriginalAuthor(root, userId, "/api/feed/posts/" + root.id().value());
		return root;
	}

	/** Remove o repost puro (desfazer). */
	@Transactional
	public void undoRepost(String userId, String postId) {
		PostId id = new PostId(postId);
		posts.lockForUpdate(id);
		if (reposts.exists(userId, postId)) {
			reposts.delete(userId, postId);
			posts.adjustCounter(id, PostCounter.REPOSTS, -1);
		}
	}

	/** Repost com opinião: cria post novo do repostador citando o original. */
	@Transactional
	public Post repostWithOpinion(String userId, String postId, String opinion) {
		ageGate.requireCanWrite(userId);
		throttle.exigePermitido("repost", userId, PER_HOUR, Duration.ofHours(1));

		Post root = resolveRoot(postId);
		validateRepostable(root);

		if (posts.findActiveRepostByAuthorAndSource(new UserId(userId), root.id().value()).isPresent()) {
			throw new ApiException(409, "ALREADY_REPOSTED", "Voce ja republicou este post.");
		}

		String cleanOpinion = opinion == null ? "" : PlainText.defuse(opinion.trim());
		if (cleanOpinion.isBlank()) {
			throw new ApiException(400, "OPINION_REQUIRED", "Escreva um comentario para republicar com opiniao.");
		}
		if (cleanOpinion.length() > 600) {
			throw new ApiException(400, "OPINION_TOO_LONG",
				"A opiniao do repost deve ter no maximo 600 caracteres.");
		}

		String username = profiles.findById(root.authorId().value())
			.map(profile -> profile.username())
			.orElse(null);
		boolean opinionOnly = OPINION_ONLY.contains(root.type());
		Post copy = Post.publish(new PostId("post_" + UUID.randomUUID().toString().replace("-", "")),
			new UserId(userId), opinionOnly ? PostType.INSIGHT : root.type(),
			cleanOpinion, null, List.of(), List.of(), null,
			null, clock.instant(), AuthorIdentity.SOCIAL)
			.markRepostOf(root.id().value(), username, true);
		Post saved = posts.save(copy);
		notifyOriginalAuthor(root, userId, "/api/feed/posts/" + saved.id().value());
		return saved;
	}

	/**
	 * Quem escreveu o original fica sabendo que ele circulou. Antes, republicar não avisava ninguém:
	 * o autor via o contador de reposts subir sem saber de quem nem onde. O próprio autor não é
	 * avisado de si mesmo, e o aviso aponta para o post que a pessoa vê no feed (o ponteiro, no
	 * repost puro; a citação, no repost com opinião).
	 */
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

	/** Compatibilidade: o endpoint antigo chama aqui. */
	@Transactional
	public Post repost(String userId, String postId, String opinion) {
		String cleanOpinion = opinion == null ? "" : opinion.trim();
		if (cleanOpinion.isBlank()) {
			repostPure(userId, postId);
			return resolveRoot(postId);
		}
		return repostWithOpinion(userId, postId, opinion);
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
