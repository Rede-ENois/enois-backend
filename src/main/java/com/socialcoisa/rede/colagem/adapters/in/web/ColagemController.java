package com.socialcoisa.rede.colagem.adapters.in.web;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.colagem.application.ColagemService;
import com.socialcoisa.rede.colagem.application.ColagemViews;
import com.socialcoisa.rede.idempotency.application.CommandResponse;
import com.socialcoisa.rede.idempotency.application.IdempotencyApplicationService;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;
import com.socialcoisa.rede.shared.web.CursorCodec;

/**
 * Endpoints da Colagem, o feed fotográfico que vive no Explorar (nada aqui entra em /feed). Leitura
 * exige sessão, como o feed geral: é um espaço de convivência, não uma página pública. Toda escrita
 * passa por idempotência e pelos mesmos gates do resto da plataforma.
 */
@RestController
public class ColagemController {

	private static final String ROUTE_POSTS = "/api/colagem/posts";
	private static final String ROUTE_COMMENTS = "/api/colagem/posts/{postId}/comments";
	private static final String ROUTE_REACTIONS = "/api/colagem/posts/{postId}/reactions";

	private final ColagemService colagem;
	private final IdempotencyApplicationService idempotency;
	private final CurrentUserResolver currentUserResolver;
	private final CursorCodec cursorCodec;

	public ColagemController(ColagemService colagem, IdempotencyApplicationService idempotency,
			CurrentUserResolver currentUserResolver, CursorCodec cursorCodec) {
		this.colagem = colagem;
		this.idempotency = idempotency;
		this.currentUserResolver = currentUserResolver;
		this.cursorCodec = cursorCodec;
	}

	@GetMapping("/colagem")
	ColagemViews.ColagemPage feed(@RequestHeader("X-Session-Token") String sessionToken,
			@RequestParam(required = false) String cursor,
			@RequestParam(defaultValue = "24") int limit) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return colagem.getFeed(user.id(), cursorCodec.decode(cursor), limit);
	}

	@GetMapping("/colagem/posts/{postId}")
	ColagemViews.ColagemPostView post(@RequestHeader("X-Session-Token") String sessionToken,
			@PathVariable String postId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return colagem.getPost(user.id(), postId);
	}

	@PostMapping("/colagem/posts")
	ResponseEntity<String> publish(@RequestHeader("X-Session-Token") String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody PublishColagemPhotoRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(request);
		return idempotency.execute(user.id(), "POST", ROUTE_POSTS, idempotencyKey, hash,
			() -> new CommandResponse(201, colagem.publish(user.id(), request.mediaId(), request.description(),
				Boolean.TRUE.equals(request.mediaSpoiler()))));
	}

	@DeleteMapping("/colagem/posts/{postId}")
	ResponseEntity<String> removePost(@RequestHeader("X-Session-Token") String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String postId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("postId", postId));
		return idempotency.execute(user.id(), "DELETE", ROUTE_POSTS, idempotencyKey, hash,
			() -> {
				colagem.removeOwnPost(user.id(), postId);
				return new CommandResponse(200, Map.of("removed", true));
			});
	}

	@GetMapping("/colagem/posts/{postId}/comments")
	ColagemViews.ColagemCommentsPage comments(@RequestHeader("X-Session-Token") String sessionToken,
			@PathVariable String postId, @RequestParam(required = false) String after,
			@RequestParam(defaultValue = "24") int limit) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return colagem.getComments(user.id(), postId, after, limit);
	}

	@PostMapping("/colagem/posts/{postId}/comments")
	ResponseEntity<String> comment(@RequestHeader("X-Session-Token") String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String postId, @RequestBody CommentColagemPhotoRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(request);
		return idempotency.execute(user.id(), "POST", ROUTE_COMMENTS, idempotencyKey, hash,
			() -> new CommandResponse(201, colagem.comment(user.id(), postId, request.parentCommentId(),
				request.content(), request.mediaId(), Boolean.TRUE.equals(request.mediaSpoiler()))));
	}

	@DeleteMapping("/colagem/posts/{postId}/comments/{commentId}")
	ResponseEntity<String> removeComment(@RequestHeader("X-Session-Token") String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String postId, @PathVariable String commentId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("postId", postId, "commentId", commentId));
		return idempotency.execute(user.id(), "DELETE", ROUTE_COMMENTS + "/" + commentId, idempotencyKey, hash,
			() -> {
				colagem.removeOwnComment(user.id(), postId, commentId);
				return new CommandResponse(200, Map.of("removed", true));
			});
	}

	@PostMapping("/colagem/posts/{postId}/reactions")
	ResponseEntity<String> react(@RequestHeader("X-Session-Token") String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String postId, @RequestBody ReactColagemPhotoRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		if (!StringUtils.hasText(request.reaction())) {
			throw new ApiException(400, "INVALID_REACTION", "Reação desconhecida.");
		}
		String hash = idempotency.hash(request);
		return idempotency.execute(user.id(), "POST", ROUTE_REACTIONS, idempotencyKey, hash,
			() -> new CommandResponse(200, colagem.react(user.id(), postId, request.reaction().trim()
				.toUpperCase())));
	}

	@DeleteMapping("/colagem/posts/{postId}/reactions")
	ResponseEntity<String> unreact(@RequestHeader("X-Session-Token") String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String postId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("postId", postId));
		return idempotency.execute(user.id(), "DELETE", ROUTE_REACTIONS, idempotencyKey, hash,
			() -> {
				colagem.unreact(user.id(), postId);
				return new CommandResponse(200, Map.of("removed", true));
			});
	}
}
