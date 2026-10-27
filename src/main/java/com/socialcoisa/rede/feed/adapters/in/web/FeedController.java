package com.socialcoisa.rede.feed.adapters.in.web;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.feed.application.BulkDeletePostsCommand;
import com.socialcoisa.rede.feed.application.CommentOnPostCommand;
import com.socialcoisa.rede.feed.application.DeletePostCommand;
import com.socialcoisa.rede.feed.application.EditPostCommand;
import com.socialcoisa.rede.feed.application.GetFeedQuery;
import com.socialcoisa.rede.feed.application.GetCommentsQuery;
import com.socialcoisa.rede.feed.application.GetProfilePostsQuery;
import com.socialcoisa.rede.feed.application.PostActionCommand;
import com.socialcoisa.rede.feed.application.PublishPostCommand;
import com.socialcoisa.rede.feed.application.ReactToPostCommand;
import com.socialcoisa.rede.feed.domain.FeedTab;
import com.socialcoisa.rede.feed.application.port.in.BulkDeletePostsUseCase;
import com.socialcoisa.rede.feed.application.port.in.CommentOnPostUseCase;
import com.socialcoisa.rede.feed.application.port.in.DeletePostUseCase;
import com.socialcoisa.rede.feed.application.port.in.EditPostUseCase;
import com.socialcoisa.rede.feed.application.port.in.RestorePostUseCase;
import com.socialcoisa.rede.feed.application.port.in.GetCommentsUseCase;
import com.socialcoisa.rede.feed.application.port.in.GetFeedUseCase;
import com.socialcoisa.rede.feed.application.port.in.GetPostUseCase;
import com.socialcoisa.rede.feed.application.port.in.GetProfilePostsUseCase;
import com.socialcoisa.rede.feed.application.port.out.CommentRepository;
import com.socialcoisa.rede.feed.application.port.in.PublishPostUseCase;
import com.socialcoisa.rede.feed.application.port.in.ReactToPostUseCase;
import com.socialcoisa.rede.feed.application.port.in.SavePostUseCase;
import com.socialcoisa.rede.feed.application.port.in.SharePostUseCase;
import com.socialcoisa.rede.feed.application.PostView;
import com.socialcoisa.rede.feed.application.RepostPostService;
import com.socialcoisa.rede.feed.application.port.out.PostInteractionRepository;
import com.socialcoisa.rede.feed.domain.Post;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.shared.blocks.BlockedUsersReader;
import com.socialcoisa.rede.idempotency.application.CommandResponse;
import com.socialcoisa.rede.idempotency.application.IdempotencyApplicationService;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;
import com.socialcoisa.rede.moderation.application.HoneypotGuard;
import com.socialcoisa.rede.shared.web.CursorCodec;

@RestController
class FeedController {

	private static final int MAX_COMMENT_LENGTH = 1024;

	private final PublishPostUseCase publishPostUseCase;
	private final GetFeedUseCase getFeedUseCase;
	private final GetPostUseCase getPostUseCase;
	private final GetProfilePostsUseCase getProfilePostsUseCase;
	private final DeletePostUseCase deletePostUseCase;
	private final EditPostUseCase editPostUseCase;
	private final RestorePostUseCase restorePostUseCase;
	private final BulkDeletePostsUseCase bulkDeletePostsUseCase;
	private final CommentOnPostUseCase commentOnPostUseCase;
	private final GetCommentsUseCase getCommentsUseCase;
	private final ReactToPostUseCase reactToPostUseCase;
	private final SavePostUseCase savePostUseCase;
	private final SharePostUseCase sharePostUseCase;
	private final RepostPostService repostPostService;
	private final IdempotencyApplicationService idempotency;
	private final CurrentUserResolver currentUserResolver;
	private final PostInteractionRepository postInteractionRepository;
	private final CommentRepository commentRepository;
	private final com.socialcoisa.rede.feed.application.port.out.PostRepository postRepository;
	private final CursorCodec cursorCodec;
	private final BlockedUsersReader blockedUsersReader;
	private final Clock clock;
	private final com.socialcoisa.rede.media.application.port.in.ValidateMediaOwnershipUseCase mediaOwnershipValidator;
	private final HoneypotGuard honeypots;
	private final com.socialcoisa.rede.feed.application.port.out.OutboxEventRepository outboxEventRepository;

	FeedController(PublishPostUseCase publishPostUseCase, GetFeedUseCase getFeedUseCase, GetPostUseCase getPostUseCase,
			GetProfilePostsUseCase getProfilePostsUseCase, DeletePostUseCase deletePostUseCase,
			EditPostUseCase editPostUseCase, RestorePostUseCase restorePostUseCase,
			BulkDeletePostsUseCase bulkDeletePostsUseCase, CommentOnPostUseCase commentOnPostUseCase,
			GetCommentsUseCase getCommentsUseCase, ReactToPostUseCase reactToPostUseCase,
			SavePostUseCase savePostUseCase, SharePostUseCase sharePostUseCase,
			RepostPostService repostPostService,
			IdempotencyApplicationService idempotency, CurrentUserResolver currentUserResolver,
			PostInteractionRepository postInteractionRepository, CommentRepository commentRepository,
			com.socialcoisa.rede.feed.application.port.out.PostRepository postRepository,
			CursorCodec cursorCodec, BlockedUsersReader blockedUsersReader, Clock clock,
			com.socialcoisa.rede.media.application.port.in.ValidateMediaOwnershipUseCase mediaOwnershipValidator,
			HoneypotGuard honeypots,
			com.socialcoisa.rede.feed.application.port.out.OutboxEventRepository outboxEventRepository) {
		this.publishPostUseCase = publishPostUseCase;
		this.getFeedUseCase = getFeedUseCase;
		this.getPostUseCase = getPostUseCase;
		this.getProfilePostsUseCase = getProfilePostsUseCase;
		this.deletePostUseCase = deletePostUseCase;
		this.editPostUseCase = editPostUseCase;
		this.restorePostUseCase = restorePostUseCase;
		this.bulkDeletePostsUseCase = bulkDeletePostsUseCase;
		this.commentOnPostUseCase = commentOnPostUseCase;
		this.getCommentsUseCase = getCommentsUseCase;
		this.reactToPostUseCase = reactToPostUseCase;
		this.savePostUseCase = savePostUseCase;
		this.sharePostUseCase = sharePostUseCase;
		this.repostPostService = repostPostService;
		this.idempotency = idempotency;
		this.currentUserResolver = currentUserResolver;
		this.postInteractionRepository = postInteractionRepository;
		this.commentRepository = commentRepository;
		this.postRepository = postRepository;
		this.cursorCodec = cursorCodec;
		this.blockedUsersReader = blockedUsersReader;
		this.clock = clock;
		this.mediaOwnershipValidator = mediaOwnershipValidator;
		this.honeypots = honeypots;
		this.outboxEventRepository = outboxEventRepository;
	}

	@PostMapping("/feed/posts")
	ResponseEntity<String> publish(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody PublishPostRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		if (honeypots.triggered(user.id().value(), request.website(), "post")) {
			return ResponseEntity.status(201)
				.body("{\"id\":\"post_" + java.util.UUID.randomUUID().toString().replace("-", "") + "\"}");
		}
		// Imagem no meio do texto: todo marcador {{media:id}} precisa referenciar anexo do autor.
		com.socialcoisa.rede.shared.text.InlineMedia.validate(request.content(), request.mediaIds());
		String hash = idempotency.hash(request);
		return idempotency.execute(user.id(), "POST", "/api/feed/posts", idempotencyKey, hash, () -> new CommandResponse(201,
			publishPostUseCase.publish(new PublishPostCommand(user.id(), request.type(), request.content(),
				request.pageHtml(), request.articleBody(), request.tags(), request.mediaIds(), request.communityId(),
				request.mentionedUserIds(), request.topic(), request.channelId(), Boolean.TRUE.equals(request.mediaSpoiler())))));
	}

	@GetMapping("/feed")
	Object feed(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit,
			@RequestParam(defaultValue = "FOR_YOU") FeedTab tab,
			@RequestParam(required = false) Set<com.socialcoisa.rede.feed.domain.PostType> type) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return getFeedUseCase.getFeed(new GetFeedQuery(user.id(), cursorCodec.decode(cursor), limit, tab,
			type == null ? Set.of() : type));
	}

	/**
	 * Leitura de um post unico. E PUBLICA de proposito: e para onde o link compartilhado aponta, e
	 * exigir sessao aqui fazia o destinatario cair no login (ou ver "post nao encontrado").
	 * A sessao, quando existe, so acrescenta o estado do proprio visitante (reacao) e respeita
	 * bloqueio; sem ela a leitura e anonima.
	 */
	@GetMapping("/feed/posts/{postId}")
	Object post(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String postId) {
		return getPostUseCase.getPost(postId, viewerIdOrNull(sessionToken));
	}

	/** Resolve o visitante quando ha sessao valida e verificada; sem isso devolve null (leitura anonima). */
	private UserId viewerIdOrNull(String sessionToken) {
		try {
			return currentUserResolver.resolve(sessionToken).id();
		}
		catch (RuntimeException exception) {
			return null;
		}
	}

	@GetMapping("/users/{username}/posts")
	Object profilePosts(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken, @PathVariable String username,
			@RequestParam(required = false) String cursor,
			@RequestParam(defaultValue = "20") int limit) {
		// Leitura publica: sem sessao valida o visitante e NINGUEM (nunca o username do perfil, que
		// virava um "leitor" inventado e desligava o filtro de bloqueio).
		return getProfilePostsUseCase.getProfilePosts(new GetProfilePostsQuery(
			viewerIdOrNull(sessionToken), username, cursorCodec.decode(cursor), limit));
	}

	@DeleteMapping("/feed/posts/{postId}")
	ResponseEntity<String> delete(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String postId, @RequestParam(required = false) String reason) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		Map<String, String> payload = new LinkedHashMap<>();
		payload.put("postId", postId);
		payload.put("reason", reason == null ? "" : reason);
		String hash = idempotency.hash(payload);
		return idempotency.execute(user.id(), "DELETE", "/api/feed/posts/{postId}", idempotencyKey, hash,
			() -> new CommandResponse(200, deletePostUseCase.delete(new DeletePostCommand(user.id(), new PostId(postId),
				reason))));
	}

	/** Edicao do proprio post (conteudo e tags). A autorizacao e do autor, validada no backend. */
	@PatchMapping("/feed/posts/{postId}")
	ResponseEntity<String> edit(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String postId, @RequestBody EditPostRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("postId", postId);
		payload.put("content", request.content() == null ? "" : request.content());
		// Sem a pagina no hash, duas edicoes diferentes do mesmo post (com a mesma chave de
		// idempotencia) seriam tratadas como a mesma chamada e a segunda nao chegaria a gravar.
		payload.put("pageHtml", request.pageHtml() == null ? "" : request.pageHtml());
		payload.put("articleBody", request.articleBody() == null ? "" : request.articleBody());
		payload.put("tags", request.tags() == null ? List.of() : request.tags());
		payload.put("type", request.type() == null ? "" : request.type());
		payload.put("topic", request.topic() == null ? "" : request.topic());
		// Imagem no meio do texto: a edição não mexe nos anexos (eles continuam os do post),
		// então o marcador precisa referenciar uma mídia que o post JÁ tem.
		com.socialcoisa.rede.shared.text.InlineMedia.validate(request.content(),
			postRepository.findById(new PostId(postId)).map(p -> p.mediaIds()).orElse(java.util.List.of()));
		String hash = idempotency.hash(payload);
		return idempotency.execute(user.id(), "PATCH", "/api/feed/posts/{postId}", idempotencyKey, hash,
			() -> new CommandResponse(200, editPostUseCase.edit(new EditPostCommand(user.id(), new PostId(postId),
				request.content(), request.pageHtml(), request.articleBody(), request.tags(),
				tipoDoPost(request.type()), request.topic()))));
	}

	/** Tipo informado na edicao (nulo = mantem); string desconhecida e 400, nunca 500. */
	private static com.socialcoisa.rede.feed.domain.PostType tipoDoPost(String tipo) {
		if (tipo == null || tipo.isBlank()) {
			return null;
		}
		try {
			return com.socialcoisa.rede.feed.domain.PostType.valueOf(tipo.trim().toUpperCase());
		}
		catch (IllegalArgumentException error) {
			throw new ApiException(400, "TIPO_INVALIDO", "Tipo de publicação desconhecido.");
		}
	}

	/** Desfaz a exclusao do proprio post (o "desfazer" do aviso de exclusao). */
	@PostMapping("/feed/posts/{postId}/restore")
	ResponseEntity<String> restore(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String postId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("postId", postId, "op", "restore"));
		return idempotency.execute(user.id(), "POST", "/api/feed/posts/{postId}/restore", idempotencyKey, hash,
			() -> new CommandResponse(200, restorePostUseCase.restore(user.id(), new PostId(postId))));
	}

	@PostMapping("/feed/posts/bulk-delete")
	ResponseEntity<String> bulkDelete(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody BulkDeletePostsRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		List<String> normalizedIds = request.postIds() == null ? List.of()
			: request.postIds().stream().filter(id -> id != null && !id.isBlank()).map(String::trim).distinct()
				.sorted(Comparator.naturalOrder()).toList();
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("postIds", normalizedIds);
		payload.put("reason", request.reason() == null ? "" : request.reason());
		String hash = idempotency.hash(payload);
		List<PostId> postIds = normalizedIds.stream().map(PostId::new).toList();
		return idempotency.execute(user.id(), "POST", "/api/feed/posts/bulk-delete", idempotencyKey, hash,
			() -> new CommandResponse(200, bulkDeletePostsUseCase.delete(new BulkDeletePostsCommand(user.id(), postIds,
				request.reason()))));
	}

	@PostMapping("/feed/posts/{postId}/comments")
	ResponseEntity<String> comment(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String postId, @RequestBody CommentOnPostRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		if (honeypots.triggered(user.id().value(), request.website(), "comment")) {
			return ResponseEntity.status(201)
				.body("{\"id\":\"comment_" + java.util.UUID.randomUUID().toString().replace("-", "") + "\"}");
		}
		// Imagem no meio do texto no comentário também (mesmo caminho do post).
		com.socialcoisa.rede.shared.text.InlineMedia.validate(request.content(), request.mediaIds());
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("postId", postId);
		payload.put("parentCommentId", request.parentCommentId() == null ? "" : request.parentCommentId());
		payload.put("content", request.content() == null ? "" : request.content());
		payload.put("mediaIds", request.mediaIds() == null ? List.of() : request.mediaIds());
		payload.put("mediaSpoiler", Boolean.TRUE.equals(request.mediaSpoiler()));
		String hash = idempotency.hash(payload);
		return idempotency.execute(user.id(), "POST", "/api/feed/posts/{postId}/comments", idempotencyKey, hash,
			() -> new CommandResponse(201, commentOnPostUseCase.comment(new CommentOnPostCommand(user.id(),
				new PostId(postId), request.parentCommentId(), request.content(), request.mediaIds(),
				request.mentionedUserIds(), Boolean.TRUE.equals(request.mediaSpoiler())))));
	}

	@GetMapping("/feed/posts/{postId}/comments")
	Object comments(@PathVariable String postId, @RequestParam(required = false) String cursor,
			@RequestParam(defaultValue = "20") int limit,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		// Sessao opcional: ela so serve para esconder comentarios de quem o visitante bloqueou.
		return getCommentsUseCase.getComments(new GetCommentsQuery(new PostId(postId), cursorCodec.decode(cursor), limit,
			Optional.ofNullable(viewerId(sessionToken))));
	}

	private com.socialcoisa.rede.feed.domain.UserId viewerId(String sessionToken) {
		try {
			return currentUserResolver.resolve(sessionToken).id();
		}
		catch (RuntimeException exception) {
			return null;
		}
	}

	@PostMapping("/feed/posts/{postId}/reactions")
	ResponseEntity<String> react(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String postId, @RequestBody ReactToPostRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("postId", postId);
		payload.put("type", request.type() == null ? "" : request.type());
		String hash = idempotency.hash(payload);
		return idempotency.execute(user.id(), "POST", "/api/feed/posts/{postId}/reactions", idempotencyKey, hash,
			() -> new CommandResponse(200, reactToPostUseCase.react(new ReactToPostCommand(user.id(),
				new PostId(postId), request.type()))));
	}

	@DeleteMapping("/feed/posts/{postId}/reactions")
	ResponseEntity<String> removeReaction(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String postId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		Map<String, String> payload = Map.of("postId", postId);
		String hash = idempotency.hash(payload);
		return idempotency.execute(user.id(), "DELETE", "/api/feed/posts/{postId}/reactions", idempotencyKey, hash,
			() -> new CommandResponse(200, reactToPostUseCase.removeReaction(new ReactToPostCommand(user.id(),
				new PostId(postId), null))));
	}

	@PostMapping("/feed/posts/{postId}/saves")
	ResponseEntity<String> save(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String postId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		Map<String, String> payload = Map.of("postId", postId);
		String hash = idempotency.hash(payload);
		return idempotency.execute(user.id(), "POST", "/api/feed/posts/{postId}/saves", idempotencyKey, hash,
			() -> new CommandResponse(200, savePostUseCase.save(new PostActionCommand(user.id(), new PostId(postId)))));
	}

	@DeleteMapping("/feed/posts/{postId}/saves")
	ResponseEntity<String> removeSave(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String postId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		Map<String, String> payload = Map.of("postId", postId);
		String hash = idempotency.hash(payload);
		return idempotency.execute(user.id(), "DELETE", "/api/feed/posts/{postId}/saves", idempotencyKey, hash,
			() -> new CommandResponse(200, savePostUseCase.removeSave(new PostActionCommand(user.id(),
				new PostId(postId)))));
	}

	/** Quem reagiu: leitura publica (a pagina do post tambem e). O visitante so esconde bloqueados. */
	@GetMapping("/feed/posts/{postId}/reactions")
	Object listReactions(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String postId) {
		UserId viewer = viewerIdOrNull(sessionToken);
		Set<String> hidden = blockedUsersReader.hiddenIdsFor(viewer == null ? null : viewer.value());
		var reactions = postInteractionRepository.findReactionsByPostId(new PostId(postId), null, 50).stream()
			.filter(reaction -> !hidden.contains(reaction.userId()))
			.toList();
		// The list is the latest 50; the counts are exact for the whole post.
		var summary = postInteractionRepository.countReactionsByPostIds(List.of(new PostId(postId)), hidden)
			.getOrDefault(new PostId(postId), Map.of());
		return Map.of("postId", postId, "summary", summary, "reactions", reactions);
	}

	@DeleteMapping("/feed/posts/{postId}/comments/{commentId}")
	ResponseEntity<String> deleteComment(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String postId, @PathVariable String commentId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("commentId", commentId, "postId", postId));
		return idempotency.execute(user.id(), "DELETE", "/api/feed/posts/{postId}/comments/{commentId}", idempotencyKey, hash,
			() -> {
				var comment = commentRepository.findById(commentId)
					.orElseThrow(() -> new ApiException(404, "COMMENT_NOT_FOUND", "Comentario nao encontrado."));
				if (!comment.authorId().equals(user.id())) {
					throw new ApiException(403, "NOT_AUTHORIZED", "Voce nao pode deletar este comentario.");
				}
				boolean eraAtivo = comment.status() == com.socialcoisa.rede.feed.domain.CommentStatus.ACTIVE;
				commentRepository.deleteById(commentId);
				// O contador do post precisa DEVOLVER o comentário apagado — antes ele só crescia,
				// e "comentar/apagar" inflava a interação recebida (e a credibilidade) sem limite.
				if (eraAtivo) {
					postRepository.findById(comment.postId()).ifPresent(post -> {
						post.removeComment();
						postRepository.save(post);
					});
				}
				return new CommandResponse(200, Map.of("status", "COMMENT_DELETED"));
			});
	}

	/** Edicao de comentario proprio: so o autor, com as mesmas regras do comentario novo. */
	/** Fixa um comentário no topo da lista: autor do post ou do próprio comentário. */
	@PostMapping("/feed/posts/{postId}/comments/{commentId}/pin")
	ResponseEntity<String> pinComment(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String postId, @PathVariable String commentId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("commentId", commentId, "postId", postId, "pinned", true));
		return idempotency.execute(user.id(), "POST", "/api/feed/posts/{postId}/comments/{commentId}/pin",
			idempotencyKey, hash, () -> {
				pinCommentFor(user.id(), postId, commentId, true);
				return new CommandResponse(200, Map.of("status", "COMMENT_PINNED", "commentId", commentId));
			});
	}

	/** Desafixa (o comentário volta à ordem normal). */
	@DeleteMapping("/feed/posts/{postId}/comments/{commentId}/pin")
	ResponseEntity<String> unpinComment(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String postId, @PathVariable String commentId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("commentId", commentId, "postId", postId, "pinned", false));
		return idempotency.execute(user.id(), "DELETE", "/api/feed/posts/{postId}/comments/{commentId}/pin",
			idempotencyKey, hash, () -> {
				pinCommentFor(user.id(), postId, commentId, false);
				return new CommandResponse(200, Map.of("status", "COMMENT_UNPINNED", "commentId", commentId));
			});
	}

	private void pinCommentFor(com.socialcoisa.rede.feed.domain.UserId userId, String postId, String commentId,
			boolean pinned) {
		postRepository.findById(new PostId(postId))
			.filter(com.socialcoisa.rede.feed.domain.Post::isActive)
			.orElseThrow(() -> new ApiException(404, "POST_NOT_FOUND", "Post nao encontrado."));
		var comment = commentRepository.findById(commentId)
			.filter(c -> c.postId().value().equals(postId))
			.filter(c -> c.status() == com.socialcoisa.rede.feed.domain.CommentStatus.ACTIVE)
			// Só comentário raiz: fixar uma resposta a esconderia do pai no topo.
			.filter(c -> c.parentCommentId() == null)
			.orElseThrow(() -> new ApiException(404, "COMMENT_NOT_FOUND", "Comentario nao encontrado."));
		boolean postOwner = postRepository.findById(new PostId(postId))
			.map(post -> post.authorId().equals(userId))
			.orElse(false);
		if (!postOwner && !comment.authorId().equals(userId)) {
			throw new ApiException(403, "NOT_AUTHORIZED",
				"So o autor do post ou do comentario pode fixar este comentario.");
		}
		if (pinned) {
			commentRepository.unpinAllByPost(new PostId(postId));
			commentRepository.save(comment.withPinned(true));
		}
		else if (comment.pinned()) {
			commentRepository.save(comment.withPinned(false));
		}
	}

	@PatchMapping("/feed/posts/{postId}/comments/{commentId}")
	ResponseEntity<String> editComment(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String postId, @PathVariable String commentId,
			@RequestBody EditCommentRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("commentId", commentId);
		payload.put("content", request.content() == null ? "" : request.content());
		payload.put("mediaIds", request.mediaIds() == null ? List.of() : request.mediaIds());
		String hash = idempotency.hash(payload);
		return idempotency.execute(user.id(), "PATCH", "/api/feed/posts/{postId}/comments/{commentId}",
			idempotencyKey, hash,
			() -> {
				var comment = commentRepository.findById(commentId)
					.orElseThrow(() -> new ApiException(404, "COMMENT_NOT_FOUND", "Comentario nao encontrado."));
				if (!comment.authorId().equals(user.id())) {
					throw new ApiException(403, "NOT_AUTHORIZED", "Voce nao pode editar este comentario.");
				}
				// mediaIds null = "nao mexi na midia"; lista (mesmo vazia) = substitui.
				List<String> mediaIds = request.mediaIds() == null ? comment.mediaIds()
					: normalizeEditMedia(request.mediaIds(), user.id());
				String content = request.content() == null ? "" : request.content().trim();
				if ((content.isEmpty() && mediaIds.isEmpty()) || content.length() > MAX_COMMENT_LENGTH) {
					throw new ApiException(422, "INVALID_COMMENT",
						"O comentario precisa ter de 1 a " + MAX_COMMENT_LENGTH + " caracteres.");
				}
				var saved = commentRepository.save(comment.edited(content, mediaIds, clock.instant()));
				return new CommandResponse(200, Map.of("status", "COMMENT_EDITED", "content", saved.content(),
					"mediaIds", saved.mediaIds(), "editedAt", saved.editedAt().toString()));
			});
	}

	@PostMapping("/feed/posts/{postId}/comments/{commentId}/reactions")
	ResponseEntity<String> reactToComment(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String postId, @PathVariable String commentId, @RequestBody ReactToPostRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("commentId", commentId);
		payload.put("type", request.type() == null ? "" : request.type());
		String hash = idempotency.hash(payload);
		return idempotency.execute(user.id(), "POST", "/api/feed/posts/{postId}/comments/{commentId}/reactions", idempotencyKey, hash,
			() -> {
				boolean added = postInteractionRepository.addCommentReaction(
					"creact_" + UUID.randomUUID().toString().replace("-", ""),
					commentId, user.id(), request.type(), Instant.now());
				if (added) {
					outboxEventRepository.append("CommentReacted", commentId,
						"{\"commentId\":\"" + commentId + "\",\"postId\":\"" + postId
						+ "\",\"authorId\":\"" + user.id().value() + "\"}");
				}
				return new CommandResponse(200, Map.of("status", added ? "REACTED" : "REMOVED"));
			});
	}

	@DeleteMapping("/feed/posts/{postId}/comments/{commentId}/reactions")
	ResponseEntity<String> removeCommentReaction(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String postId, @PathVariable String commentId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("commentId", commentId));
		return idempotency.execute(user.id(), "DELETE", "/api/feed/posts/{postId}/comments/{commentId}/reactions",
			idempotencyKey, hash, () -> new CommandResponse(200, Map.of(
				"status", postInteractionRepository.removeCommentReaction(commentId, user.id()) ? "REMOVED" : "NO_REACTION")));
	}

	@GetMapping("/feed/posts/{postId}/comments/{commentId}/reactions")
	Object listCommentReactions(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String postId, @PathVariable String commentId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		var reactions = postInteractionRepository.findReactionsByCommentId(commentId);
		var summary = reactions.stream()
			.collect(Collectors.groupingBy(PostInteractionRepository.ReactionSummary::type, Collectors.counting()));
		return Map.of("commentId", commentId, "summary", summary, "reactions", reactions);
	}

	@GetMapping("/feed/saved")
	Object savedPosts(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return getFeedUseCase.getSavedFeed(user.id());
	}

	@PostMapping("/feed/posts/{postId}/shares")
	ResponseEntity<String> share(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String postId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		Map<String, String> payload = Map.of("postId", postId);
		String hash = idempotency.hash(payload);
		return idempotency.execute(user.id(), "POST", "/api/feed/posts/{postId}/shares", idempotencyKey, hash,
			() -> new CommandResponse(200, sharePostUseCase.share(new PostActionCommand(user.id(), new PostId(postId)))));
	}

	/** Republica um post no feed de quem clicou (cópia com crédito ao autor original). */
	@PostMapping("/feed/posts/{postId}/repost")
	@org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.CREATED)
	PostView repost(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String postId, @RequestBody(required = false) RepostRequest body) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		Post copy = repostPostService.repost(user.id().value(), postId, body == null ? null : body.opinion());
		return PostView.from(copy);
	}

	record RepostRequest(String opinion) {
	}

	/** Ate 4 midias na edicao, do proprio autor (mesma regra do comentario novo). */
	private List<String> normalizeEditMedia(List<String> mediaIds,
			com.socialcoisa.rede.feed.domain.UserId ownerId) {
		List<String> normalized = mediaIds.stream()
			.filter(org.springframework.util.StringUtils::hasText)
			.map(String::trim)
			.distinct()
			.toList();
		if (normalized.size() > 4) {
			throw new ApiException(400, "COMMENT_TOO_MANY_MEDIA", "Comentario aceita no maximo 4 midias.");
		}
		mediaOwnershipValidator.validateOwnedBy(ownerId, normalized);
		return normalized;
	}
}
