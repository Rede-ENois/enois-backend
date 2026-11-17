package com.socialcoisa.rede.desabafos.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.desabafos.application.DesabafosViews.DesabafoCommentView;
import com.socialcoisa.rede.desabafos.application.DesabafosViews.DesabafoPostView;
import com.socialcoisa.rede.desabafos.application.port.in.DesabafosModerationPort;
import com.socialcoisa.rede.desabafos.application.port.out.DesabafosRepository;
import com.socialcoisa.rede.desabafos.domain.DesabafoComment;
import com.socialcoisa.rede.desabafos.domain.DesabafoPost;
import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.notifications.application.NotificationService;
import com.socialcoisa.rede.shared.age.AgeGate;
import com.socialcoisa.rede.shared.blocks.BlockedUsersReader;
import com.socialcoisa.rede.shared.security.UserRateLimiter;
import com.socialcoisa.rede.shared.sanctions.SanctionGate;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.CursorCodec;

/**
 * Regras dos Desabafos: texto puro e curto, feed próprio fora dos feeds gerais, apoio contado
 * sem lista de quem apoiou. Os gates de publicação (idade, sanção, ritmo), os bloqueios, a
 * moderação e as denúncias são os mesmos do resto da plataforma — nada de sistema paralelo.
 * Sem gamificação: sem pontos, sem ranking, sem destaque — o feed é cronológico, do mais
 * recente ao mais antigo, e acolhe a todos igual.
 */
@Service
public class DesabafosService implements DesabafosModerationPort {

	public static final int MAX_CONTENT = 5000;
	public static final int MAX_COMMENT = 800;
	public static final int MAX_PAGE_SIZE = 48;
	public static final int DEFAULT_PAGE_SIZE = 24;

	private final DesabafosRepository repository;
	private final AgeGate agePolicy;
	private final SanctionGate sanctionGate;
	private final UserRateLimiter userRateLimiter;
	private final BlockedUsersReader blockedUsers;
	private final NotificationService notifications;
	private final CursorCodec cursorCodec;
	private final Clock clock;

	public DesabafosService(DesabafosRepository repository, AgeGate agePolicy, SanctionGate sanctionGate,
			UserRateLimiter userRateLimiter, BlockedUsersReader blockedUsers,
			NotificationService notifications, CursorCodec cursorCodec, Clock clock) {
		this.repository = repository;
		this.agePolicy = agePolicy;
		this.sanctionGate = sanctionGate;
		this.userRateLimiter = userRateLimiter;
		this.blockedUsers = blockedUsers;
		this.notifications = notifications;
		this.cursorCodec = cursorCodec;
		this.clock = clock;
	}

	// ---- Publicação ----

	@Transactional
	public DesabafosViews.DesabafoPostView publish(UserId authorId, String content) {
		agePolicy.requireCanWrite(authorId.value());
		sanctionGate.requireNotRestricted(authorId.value());
		userRateLimiter.require(authorId.value(), UserRateLimiter.CREATE_POST);
		String texto = normalizeContent(content);
		DesabafoPost post = repository
			.savePost(DesabafoPost.publish("desabafo_" + UUID.randomUUID().toString().replace("-", ""), authorId,
				texto, clock.instant()));
		return enrichPosts(List.of(post), authorId).get(0);
	}

	@Transactional
	public void removeOwnPost(UserId userId, String postId) {
		DesabafoPost post = activePost(postId);
		if (!post.authorId().equals(userId)) {
			throw new ApiException(403, "NOT_AUTHORIZED", "Só o autor remove o próprio desabafo.");
		}
		post.remove(clock.instant());
		repository.savePost(post);
		for (DesabafoComment comment : repository.findActiveCommentsByPost(postId)) {
			comment.remove();
			repository.saveComment(comment);
		}
	}

	// ---- Comentários ----

	@Transactional
	public DesabafosViews.DesabafoCommentView comment(UserId authorId, String postId, String content) {
		agePolicy.requireCanWrite(authorId.value());
		sanctionGate.requireNotRestricted(authorId.value());
		userRateLimiter.require(authorId.value(), UserRateLimiter.CREATE_COMMENT);
		DesabafoPost post = activePost(postId);

		String texto = normalizeComment(content);
		DesabafoComment salvo = repository.saveComment(new DesabafoComment(
			"desabafo_c_" + UUID.randomUUID().toString().replace("-", ""), postId, authorId, texto,
			clock.instant(), com.socialcoisa.rede.desabafos.domain.DesabafoStatus.ACTIVE));
		notifyPostAuthor(post, authorId);
		return enrichComments(List.of(salvo), authorId).get(0);
	}

	@Transactional
	public void removeOwnComment(UserId userId, String postId, String commentId) {
		DesabafoComment comment = repository.findCommentById(commentId)
			.filter(DesabafoComment::isActive)
			.filter(c -> c.postId().equals(postId))
			.orElseThrow(() -> new ApiException(404, "COMMENT_NOT_FOUND", "Comentário não encontrado."));
		if (!comment.authorId().equals(userId)) {
			throw new ApiException(403, "NOT_AUTHORIZED", "Só o autor remove o próprio comentário.");
		}
		comment.remove();
		repository.saveComment(comment);
	}

	// ---- Reações (apoio) ----

	@Transactional
	public Map<String, Long> react(UserId userId, String postId, String reaction) {
		DesabafoPost post = activePost(postId);
		com.socialcoisa.rede.feed.domain.ReactionType tipo = parseReaction(reaction);
		repository.react(post.id(), userId.value(), tipo.name());
		return repository.countReactionsByPostIds(List.of(post.id())).getOrDefault(post.id(), Map.of());
	}

	@Transactional
	public void unreact(UserId userId, String postId) {
		DesabafoPost post = activePost(postId);
		repository.removeReaction(post.id(), userId.value());
	}

	// ---- Leitura ----

	@Transactional(readOnly = true)
	public DesabafosViews.DesabafosPage getFeed(UserId viewer, Optional<Instant> before, int limit) {
		int tamanho = Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
		Instant marca = before.orElseGet(() -> clock.instant().plusSeconds(1));
		List<DesabafoPost> posts = repository.findFeedBefore(marca, tamanho);
		List<DesabafoPostView> items = enrichPosts(posts, viewer);
		// O cursor sai do último BRUTO da página: bloqueios encolhem a lista visível, mas a marca
		// de tempo continua valendo para a página seguinte.
		String nextCursor = posts.size() == tamanho ? cursorCodec.encode(posts.get(posts.size() - 1).createdAt())
			: null;
		return new DesabafosViews.DesabafosPage(items, nextCursor);
	}

	@Transactional(readOnly = true)
	public DesabafosViews.DesabafoPostView getPost(UserId viewer, String postId) {
		return enrichPosts(List.of(activePost(postId)), viewer).get(0);
	}

	@Transactional(readOnly = true)
	public DesabafosViews.DesabafosCommentsPage getComments(UserId viewer, String postId) {
		activePost(postId);
		List<DesabafoComment> todos = repository.findActiveCommentsByPost(postId);
		Set<String> hidden = hiddenFor(viewer);
		if (!hidden.isEmpty()) {
			todos = todos.stream().filter(c -> !hidden.contains(c.authorId().value())).toList();
		}
		return new DesabafosViews.DesabafosCommentsPage(enrichComments(todos, viewer));
	}

	// ---- Moderação (port DesabafosModerationPort) ----

	@Override
	@Transactional
	public boolean removePostAsModerator(String actorId, String postId, String reason) {
		return repository.findPostById(postId)
			.filter(DesabafoPost::isActive)
			.map(post -> {
				post.remove(clock.instant());
				repository.savePost(post);
				for (DesabafoComment comment : repository.findActiveCommentsByPost(postId)) {
					comment.remove();
					repository.saveComment(comment);
				}
				return true;
			})
			.orElse(false);
	}

	@Override
	@Transactional
	public boolean removeCommentAsModerator(String actorId, String commentId, String reason) {
		return repository.findCommentById(commentId)
			.filter(DesabafoComment::isActive)
			.map(comment -> {
				comment.remove();
				repository.saveComment(comment);
				return true;
			})
			.orElse(false);
	}

	// ---- Internos ----

	private DesabafoPost activePost(String postId) {
		return repository.findPostById(postId)
			.filter(DesabafoPost::isActive)
			.orElseThrow(() -> new ApiException(404, "POST_NOT_FOUND", "Desabafo não encontrado."));
	}

	private String normalizeContent(String content) {
		String texto = content == null ? "" : content.trim();
		if (!StringUtils.hasText(texto)) {
			throw new ApiException(400, "CONTENT_REQUIRED", "Escreva o que está sentindo — o desabafo é o texto.");
		}
		if (texto.length() > MAX_CONTENT) {
			throw new ApiException(400, "CONTENT_TOO_LONG",
				"O desabafo aceita no máximo " + MAX_CONTENT + " caracteres.");
		}
		return texto;
	}

	private String normalizeComment(String content) {
		String texto = content == null ? "" : content.trim();
		if (!StringUtils.hasText(texto)) {
			throw new ApiException(400, "COMMENT_EMPTY", "Escreva uma acolhida para comentar.");
		}
		if (texto.length() > MAX_COMMENT) {
			throw new ApiException(400, "COMMENT_TOO_LONG", "O comentário aceita no máximo 800 caracteres.");
		}
		return texto;
	}

	private com.socialcoisa.rede.feed.domain.ReactionType parseReaction(String reaction) {
		try {
			return com.socialcoisa.rede.feed.domain.ReactionType.valueOf(reaction);
		}
		catch (IllegalArgumentException | NullPointerException semTipo) {
			throw new ApiException(400, "INVALID_REACTION", "Reação desconhecida.");
		}
	}

	private void notifyPostAuthor(DesabafoPost post, UserId commenterId) {
		String authorId = post.authorId().value();
		if (authorId.equals(commenterId.value())) {
			return;
		}
		// Sem nome do ator e sem actorId: acolhida é anônima como o desabafo — quem acolheu não
		// se revela nem pelo aviso (o vínculo interno segue para moderação e auditoria).
		notifications.create(authorId, "DESABAFOS_COMMENTED", null,
			"Alguém acolheu seu desabafo", "Alguém comentou no seu desabafo.",
			"/desabafos?post=" + post.id(), "Ver");
	}

	private Set<String> hiddenFor(UserId viewer) {
		return blockedUsers.hiddenIdsFor(viewer == null ? null : viewer.value());
	}

	private List<DesabafoPostView> enrichPosts(List<DesabafoPost> posts, UserId viewer) {
		if (posts.isEmpty()) {
			return List.of();
		}
		Set<String> hidden = hiddenFor(viewer);
		List<DesabafoPost> visiveis = posts.stream()
			.filter(DesabafoPost::isActive)
			.filter(post -> hidden.isEmpty() || !hidden.contains(post.authorId().value()))
			.toList();
		if (visiveis.isEmpty()) {
			return List.of();
		}
		List<String> ids = visiveis.stream().map(DesabafoPost::id).toList();
		Map<String, Map<String, Long>> reacoes = repository.countReactionsByPostIds(ids);
		Map<String, String> minhas = viewer == null ? Map.of() : repository.findUserReactions(viewer.value(),
			visiveis.stream().map(DesabafoPost::id).collect(Collectors.toSet()));
		Map<String, Long> comentarios = repository.countActiveCommentsByPostIds(ids);

		return visiveis.stream().map(post -> new DesabafoPostView(post.id(), post.content(),
			post.createdAt(), reacoes.getOrDefault(post.id(), Map.of()).values().stream()
				.mapToLong(Long::longValue).sum(),
			reacoes.getOrDefault(post.id(), Map.of()), minhas.get(post.id()),
			comentarios.getOrDefault(post.id(), 0L),
			viewer != null && post.authorId().equals(viewer))).toList();
	}

	private List<DesabafoCommentView> enrichComments(List<DesabafoComment> comments, UserId viewer) {
		return comments.stream().map(comment -> new DesabafoCommentView(comment.id(), comment.postId(),
			comment.content(), comment.createdAt(), viewer != null && comment.authorId().equals(viewer)))
			.toList();
	}

}
