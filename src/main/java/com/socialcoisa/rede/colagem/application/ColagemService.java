package com.socialcoisa.rede.colagem.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.colagem.application.ColagemViews.ColagemCommentView;
import com.socialcoisa.rede.colagem.application.ColagemViews.ColagemPostView;
import com.socialcoisa.rede.colagem.application.port.in.ColagemModerationPort;
import com.socialcoisa.rede.colagem.application.port.out.ColagemRepository;
import com.socialcoisa.rede.colagem.domain.ColagemComment;
import com.socialcoisa.rede.colagem.domain.ColagemPost;
import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.media.application.port.in.GetMediaSummariesUseCase;
import com.socialcoisa.rede.media.application.port.in.ValidateMediaOwnershipUseCase;
import com.socialcoisa.rede.media.application.MediaSummaryView;
import com.socialcoisa.rede.notifications.application.NotificationService;
import com.socialcoisa.rede.profile.application.NameStyleView;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.age.AgeGate;
import com.socialcoisa.rede.shared.blocks.BlockedUsersReader;
import com.socialcoisa.rede.shared.security.UserRateLimiter;
import com.socialcoisa.rede.shared.sanctions.SanctionGate;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.CursorCodec;

/**
 * Regras da Colagem: foto única de autor conhecido, comentários com no máximo uma fotografia e
 * sem recursão de imagens, feed próprio fora dos feeds gerais. Moderação, bloqueios e gates de
 * publicação são os mesmos do resto da plataforma — nada de sistema paralelo.
 */
@Service
public class ColagemService implements ColagemModerationPort {

	public static final int MAX_DESCRIPTION = 500;
	public static final int MAX_COMMENT = 800;
	public static final int MAX_PAGE_SIZE = 48;
	public static final int DEFAULT_PAGE_SIZE = 24;

	private static final String ACTOR_MODERACAO = "automod-midia";

	private final ColagemRepository repository;
	private final ValidateMediaOwnershipUseCase mediaOwnership;
	private final GetMediaSummariesUseCase mediaSummaries;
	private final AgeGate agePolicy;
	private final SanctionGate sanctionGate;
	private final UserRateLimiter userRateLimiter;
	private final BlockedUsersReader blockedUsers;
	private final UserProfileRepository profiles;
	private final NotificationService notifications;
	private final CursorCodec cursorCodec;
	private final Clock clock;

	public ColagemService(ColagemRepository repository, ValidateMediaOwnershipUseCase mediaOwnership,
			GetMediaSummariesUseCase mediaSummaries, AgeGate agePolicy, SanctionGate sanctionGate,
			UserRateLimiter userRateLimiter, BlockedUsersReader blockedUsers, UserProfileRepository profiles,
			NotificationService notifications, CursorCodec cursorCodec, Clock clock) {
		this.repository = repository;
		this.mediaOwnership = mediaOwnership;
		this.mediaSummaries = mediaSummaries;
		this.agePolicy = agePolicy;
		this.sanctionGate = sanctionGate;
		this.userRateLimiter = userRateLimiter;
		this.blockedUsers = blockedUsers;
		this.profiles = profiles;
		this.notifications = notifications;
		this.cursorCodec = cursorCodec;
		this.clock = clock;
	}

	// ---- Publicação ----

	@Transactional
	public ColagemViews.ColagemPostView publish(UserId authorId, String mediaId, String description,
			boolean mediaSpoiler) {
		agePolicy.requireCanWrite(authorId.value());
		sanctionGate.requireNotRestricted(authorId.value());
		userRateLimiter.require(authorId.value(), UserRateLimiter.CREATE_POST);
		String media = requireSingleImage(authorId, mediaId);
		String legenda = normalizeDescription(description);
		ColagemPost post = repository.savePost(ColagemPost.publish("colagem_" + UUID.randomUUID(), authorId, media,
			legenda, mediaSpoiler, clock.instant()));
		return enrichPosts(List.of(post), authorId).get(0);
	}

	@Transactional
	public void removeOwnPost(UserId userId, String postId) {
		ColagemPost post = activePost(postId);
		if (!post.authorId().equals(userId)) {
			throw new ApiException(403, "NOT_AUTHORIZED", "Só o autor remove a própria foto.");
		}
		post.remove(clock.instant());
		repository.savePost(post);
		for (ColagemComment comment : repository.findActiveCommentsByPost(postId)) {
			comment.remove();
			repository.saveComment(comment);
		}
	}

	// ---- Comentários ----

	@Transactional
	public ColagemViews.ColagemCommentView comment(UserId authorId, String postId, String parentCommentId,
			String content, String mediaId, boolean mediaSpoiler) {
		agePolicy.requireCanWrite(authorId.value());
		sanctionGate.requireNotRestricted(authorId.value());
		userRateLimiter.require(authorId.value(), UserRateLimiter.CREATE_COMMENT);
		ColagemPost post = activePost(postId);

		String texto = content == null ? null : content.trim();
		if (texto != null && texto.length() > MAX_COMMENT) {
			throw new ApiException(400, "COMMENT_TOO_LONG", "Comentario deve ter no maximo 800 caracteres.");
		}
		// Fotografia é opcional, mas algo tem que contribuir: texto, foto ou os dois.
		boolean temFoto = StringUtils.hasText(mediaId);
		if (!StringUtils.hasText(texto) && !temFoto) {
			throw new ApiException(400, "COMMENT_EMPTY", "Comente com texto, fotografia ou os dois.");
		}
		String foto = temFoto ? requireSingleImage(authorId, mediaId) : null;

		String parent = null;
		if (StringUtils.hasText(parentCommentId)) {
			ColagemComment pai = repository.findCommentById(parentCommentId.trim())
				.filter(ColagemComment::isActive)
				.filter(c -> c.postId().equals(postId))
				.orElseThrow(() -> new ApiException(404, "COMMENT_NOT_FOUND", "Comentario nao encontrado."));
			// Sem recursão de imagens: resposta de foto é texto (puro ou com a foto da conversa
			// já no pai). O vínculo continua na publicação original.
			if (pai.hasPhoto() && foto != null) {
				throw new ApiException(400, "PHOTO_REPLY_NOT_ALLOWED",
					"Resposta a comentário fotográfico não pode ter outra fotografia.");
			}
			parent = pai.id();
		}

		ColagemComment salvo = repository.saveComment(new ColagemComment("colagem_c_" + UUID.randomUUID(), postId,
			authorId, parent, StringUtils.hasText(texto) ? texto : null, foto, mediaSpoiler, clock.instant(),
			com.socialcoisa.rede.colagem.domain.ColagemStatus.ACTIVE));
		notifyPostAuthor(post, authorId);
		return enrichComments(List.of(salvo), authorId).get(0);
	}

	@Transactional
	public void removeOwnComment(UserId userId, String postId, String commentId) {
		ColagemComment comment = repository.findCommentById(commentId)
			.filter(ColagemComment::isActive)
			.filter(c -> c.postId().equals(postId))
			.orElseThrow(() -> new ApiException(404, "COMMENT_NOT_FOUND", "Comentario nao encontrado."));
		if (!comment.authorId().equals(userId)) {
			throw new ApiException(403, "NOT_AUTHORIZED", "Só o autor remove o próprio comentário.");
		}
		comment.remove();
		repository.saveComment(comment);
	}

	// ---- Reações ----

	@Transactional
	public Map<String, Long> react(UserId userId, String postId, String reaction) {
		ColagemPost post = activePost(postId);
		com.socialcoisa.rede.feed.domain.ReactionType tipo = parseReaction(reaction);
		repository.react(post.id(), userId.value(), tipo.name());
		return repository.countReactionsByPostIds(List.of(post.id())).getOrDefault(post.id(), Map.of());
	}

	@Transactional
	public void unreact(UserId userId, String postId) {
		ColagemPost post = activePost(postId);
		repository.removeReaction(post.id(), userId.value());
	}

	// ---- Leitura ----

	@Transactional(readOnly = true)
	public ColagemViews.ColagemPage getFeed(UserId viewer, Optional<Instant> before, int limit) {
		int tamanho = Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
		Instant marca = before.orElseGet(() -> clock.instant().plusSeconds(1));
		List<ColagemPost> posts = repository.findFeedBefore(marca, tamanho);
		List<ColagemPostView> items = enrichPosts(posts, viewer);
		// O cursor sai do último BRUTO da página: bloqueios encolhem a lista visível, mas a marca
		// de tempo continua valendo para a página seguinte.
		String nextCursor = posts.size() == tamanho ? cursorCodec.encode(posts.get(posts.size() - 1).createdAt())
			: null;
		return new ColagemViews.ColagemPage(items, nextCursor);
	}

	@Transactional(readOnly = true)
	public ColagemViews.ColagemPostView getPost(UserId viewer, String postId) {
		return enrichPosts(List.of(activePost(postId)), viewer).get(0);
	}

	@Transactional(readOnly = true)
	public ColagemViews.ColagemCommentsPage getComments(UserId viewer, String postId, String after, int limit) {
		activePost(postId);
		int tamanho = Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
		List<ColagemComment> todos = repository.findActiveCommentsByPost(postId);
		Set<String> hidden = hiddenFor(viewer);
		if (!hidden.isEmpty()) {
			todos = todos.stream().filter(c -> !hidden.contains(c.authorId().value())).toList();
		}
		if (StringUtils.hasText(after)) {
			Instant marca = cursorCodec.decode(after)
				.orElseThrow(() -> new ApiException(400, "INVALID_CURSOR", "Cursor invalido."));
			todos = todos.stream().filter(c -> c.createdAt().isAfter(marca)).toList();
		}
		List<ColagemComment> pagina = todos.stream().limit(tamanho).toList();
		List<ColagemCommentView> items = enrichComments(pagina, viewer);
		String nextCursor = pagina.size() == tamanho && todos.size() > tamanho
			? cursorCodec.encode(pagina.get(pagina.size() - 1).createdAt())
			: null;
		return new ColagemViews.ColagemCommentsPage(items, nextCursor);
	}

	// ---- Moderação (port ColagemModerationPort) ----

	@Override
	@Transactional
	public boolean removePostAsModerator(String actorId, String postId, String reason) {
		return repository.findPostById(postId)
			.filter(ColagemPost::isActive)
			.map(post -> {
				post.remove(clock.instant());
				repository.savePost(post);
				for (ColagemComment comment : repository.findActiveCommentsByPost(postId)) {
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
			.filter(ColagemComment::isActive)
			.map(comment -> {
				comment.remove();
				repository.saveComment(comment);
				return true;
			})
			.orElse(false);
	}

	@Override
	@Transactional
	public int removeByMedia(String mediaId, String reason) {
		int removed = 0;
		for (ColagemPost post : repository.findActivePostsByMediaId(mediaId)) {
			post.remove(clock.instant());
			repository.savePost(post);
			for (ColagemComment comment : repository.findActiveCommentsByPost(post.id())) {
				comment.remove();
				repository.saveComment(comment);
			}
			removed++;
		}
		for (ColagemComment comment : repository.findActiveCommentsByMediaId(mediaId)) {
			comment.remove();
			repository.saveComment(comment);
			removed++;
		}
		return removed;
	}

	// ---- Internos ----

	private ColagemPost activePost(String postId) {
		return repository.findPostById(postId)
			.filter(ColagemPost::isActive)
			.orElseThrow(() -> new ApiException(404, "POST_NOT_FOUND", "Foto nao encontrada."));
	}

	/** A foto precisa existir, ser do autor, ser imagem e estar visível (não removida). */
	private String requireSingleImage(UserId authorId, String mediaId) {
		if (!StringUtils.hasText(mediaId)) {
			throw new ApiException(400, "MEDIA_REQUIRED", "A Colagem publica exatamente uma fotografia.");
		}
		String id = mediaId.trim();
		mediaOwnership.validateOwnedBy(authorId, List.of(id));
		MediaSummaryView media = mediaSummaries.visibleByIds(List.of(id)).get(id);
		if (media == null) {
			throw new ApiException(404, "MEDIA_NOT_FOUND", "Midia nao encontrada.");
		}
		if (media.mimeType() == null || !media.mimeType().startsWith("image/")) {
			throw new ApiException(400, "NOT_IMAGE", "A Colagem aceita apenas fotografia — nada de vídeo.");
		}
		return id;
	}

	private String normalizeDescription(String description) {
		String legenda = description == null ? null : description.trim();
		if (legenda != null && legenda.length() > MAX_DESCRIPTION) {
			throw new ApiException(400, "CONTENT_TOO_LONG", "A legenda aceita no maximo 500 caracteres.");
		}
		return StringUtils.hasText(legenda) ? legenda : null;
	}

	private com.socialcoisa.rede.feed.domain.ReactionType parseReaction(String reaction) {
		try {
			return com.socialcoisa.rede.feed.domain.ReactionType.valueOf(reaction);
		}
		catch (IllegalArgumentException | NullPointerException semTipo) {
			throw new ApiException(400, "INVALID_REACTION", "Reação desconhecida.");
		}
	}

	private void notifyPostAuthor(ColagemPost post, UserId commenterId) {
		String authorId = post.authorId().value();
		if (authorId.equals(commenterId.value())) {
			return;
		}
		String actorName = profiles.findById(commenterId.value())
			.map(profile -> profile.displayWithNickname() != null ? profile.displayWithNickname() : profile.name())
			.orElse("Alguém");
		notifications.create(authorId, "COLLAGEM_COMMENTED", commenterId.value(), "Nova contribuição na sua foto",
			actorName + " contribuiu na sua fotografia na Colagem.", "/colagem?foto=" + post.id(), "Ver");
	}

	private Set<String> hiddenFor(UserId viewer) {
		return blockedUsers.hiddenIdsFor(viewer == null ? null : viewer.value());
	}

	private List<ColagemPostView> enrichPosts(List<ColagemPost> posts, UserId viewer) {
		if (posts.isEmpty()) {
			return List.of();
		}
		Set<String> hidden = hiddenFor(viewer);
		List<ColagemPost> visiveis = posts.stream()
			.filter(post -> post.isActive())
			.filter(post -> hidden.isEmpty() || !hidden.contains(post.authorId().value()))
			.toList();
		if (visiveis.isEmpty()) {
			return List.of();
		}
		Map<String, UserProfile> autores = profiles.findAllByIds(
			visiveis.stream().map(p -> p.authorId().value()).collect(Collectors.toSet())).stream()
			.collect(Collectors.toMap(UserProfile::id, Function.identity()));
		// Mídia sumida (removida pelo takedown, por exemplo) tira a foto do feed: sem imagem não
		// há Colagem.
		Map<String, MediaSummaryView> midias = mediaSummaries.visibleByIds(
			visiveis.stream().map(ColagemPost::mediaId).collect(Collectors.toSet()));
		List<ColagemPost> noAr = visiveis.stream().filter(post -> midias.containsKey(post.mediaId())).toList();
		List<String> ids = noAr.stream().map(ColagemPost::id).toList();
		Map<String, Map<String, Long>> reacoes = repository.countReactionsByPostIds(ids);
		Map<String, String> minhas = viewer == null ? Map.of()
			: repository.findUserReactions(viewer.value(), ids);
		Map<String, Long> comentarios = repository.countActiveCommentsByPostIds(ids);

		return noAr.stream().map(post -> {
			UserProfile autor = autores.get(post.authorId().value());
			MediaSummaryView midia = midias.get(post.mediaId());
			return new ColagemViews.ColagemPostView(post.id(),
				toAuthor(post.authorId().value(), autor),
				post.mediaId(), midia.cdnUrl(), midia.mimeType(), midia.width(), midia.height(),
				post.description(), post.mediaSpoiler(), post.createdAt(),
				reacoes.getOrDefault(post.id(), Map.of()).values().stream().mapToLong(Long::longValue).sum(),
				reacoes.getOrDefault(post.id(), Map.of()),
				minhas.get(post.id()),
				comentarios.getOrDefault(post.id(), 0L));
		}).toList();
	}

	private List<ColagemViews.ColagemCommentView> enrichComments(List<ColagemComment> comments, UserId viewer) {
		if (comments.isEmpty()) {
			return List.of();
		}
		Map<String, UserProfile> autores = profiles.findAllByIds(
			comments.stream().map(c -> c.authorId().value()).collect(Collectors.toSet())).stream()
			.collect(Collectors.toMap(UserProfile::id, Function.identity()));
		Map<String, MediaSummaryView> midias = mediaSummaries.visibleByIds(
			comments.stream().filter(ColagemComment::hasPhoto).map(ColagemComment::mediaId)
				.collect(Collectors.toSet()));
		return comments.stream().map(comment -> {
			MediaSummaryView midia = comment.hasPhoto() ? midias.get(comment.mediaId()) : null;
			return new ColagemViews.ColagemCommentView(comment.id(), comment.postId(),
				toAuthor(comment.authorId().value(), autores.get(comment.authorId().value())),
				comment.parentCommentId(),
				comment.content(),
				comment.mediaId(),
				midia == null ? null : midia.cdnUrl(),
				midia == null ? null : midia.width(),
				midia == null ? null : midia.height(),
				comment.mediaSpoiler(), comment.createdAt());
		}).toList();
	}

	private ColagemViews.ColagemAuthor toAuthor(String id, UserProfile profile) {
		if (profile == null) {
			return new ColagemViews.ColagemAuthor(id, null, null, null, null, null);
		}
		return new ColagemViews.ColagemAuthor(profile.id(), profile.name(), profile.username(), profile.avatar(),
			profile.avatarFrame(), NameStyleView.from(profile));
	}
}
