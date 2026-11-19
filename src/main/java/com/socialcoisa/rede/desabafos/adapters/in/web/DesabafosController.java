package com.socialcoisa.rede.desabafos.adapters.in.web;

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

import com.socialcoisa.rede.desabafos.application.DesabafosService;
import com.socialcoisa.rede.desabafos.application.DesabafosViews;
import com.socialcoisa.rede.idempotency.application.CommandResponse;
import com.socialcoisa.rede.idempotency.application.IdempotencyApplicationService;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;
import com.socialcoisa.rede.shared.web.CursorCodec;

/**
 * Endpoints dos Desabafos, o feed de texto que vive no Explorar (nada aqui entra em /feed, no
 * perfil ou no diário — as tabelas são próprias e fora de `posts`). Leitura exige sessão, como
 * os outros espaços de convivência. Toda escrita passa por idempotência e pelos mesmos gates do
 * resto da plataforma; reação devolve contagem, sem lista de quem apoiou.
 */
@RestController
public class DesabafosController {

	private static final String ROUTE_POSTS = "/api/desabafos/posts";
	private static final String ROUTE_COMMENTS = "/api/desabafos/posts/{postId}/comments";
	private static final String ROUTE_REACTIONS = "/api/desabafos/posts/{postId}/reactions";

	private final DesabafosService desabafos;
	private final IdempotencyApplicationService idempotency;
	private final CurrentUserResolver currentUserResolver;
	private final CursorCodec cursorCodec;

	public DesabafosController(DesabafosService desabafos, IdempotencyApplicationService idempotency,
			CurrentUserResolver currentUserResolver, CursorCodec cursorCodec) {
		this.desabafos = desabafos;
		this.idempotency = idempotency;
		this.currentUserResolver = currentUserResolver;
		this.cursorCodec = cursorCodec;
	}

	@GetMapping("/desabafos")
	DesabafosViews.DesabafosPage feed(@RequestHeader("X-Session-Token") String sessionToken,
			@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "24") int limit) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return desabafos.getFeed(user.id(), cursorCodec.decode(cursor), limit);
	}

	@GetMapping("/desabafos/posts/{postId}")
	DesabafosViews.DesabafoPostView post(@RequestHeader("X-Session-Token") String sessionToken,
			@PathVariable String postId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return desabafos.getPost(user.id(), postId);
	}

	@PostMapping("/desabafos/posts")
	ResponseEntity<String> publish(@RequestHeader("X-Session-Token") String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody PublishDesabafoRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(request);
		return idempotency.execute(user.id(), "POST", ROUTE_POSTS, idempotencyKey, hash,
			() -> new CommandResponse(201, desabafos.publish(user.id(), request.content())));
	}

	@DeleteMapping("/desabafos/posts/{postId}")
	ResponseEntity<String> removePost(@RequestHeader("X-Session-Token") String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String postId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("postId", postId));
		return idempotency.execute(user.id(), "DELETE", ROUTE_POSTS, idempotencyKey, hash, () -> {
			desabafos.removeOwnPost(user.id(), postId);
			return new CommandResponse(200, Map.of("removed", true));
		});
	}

	@GetMapping("/desabafos/posts/{postId}/comments")
	DesabafosViews.DesabafosCommentsPage comments(@RequestHeader("X-Session-Token") String sessionToken,
			@PathVariable String postId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return desabafos.getComments(user.id(), postId);
	}

	@PostMapping("/desabafos/posts/{postId}/comments")
	ResponseEntity<String> comment(@RequestHeader("X-Session-Token") String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String postId, @RequestBody CommentDesabafoRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(request);
		return idempotency.execute(user.id(), "POST", ROUTE_COMMENTS, idempotencyKey, hash,
			() -> new CommandResponse(201, desabafos.comment(user.id(), postId, request.content())));
	}

	@DeleteMapping("/desabafos/posts/{postId}/comments/{commentId}")
	ResponseEntity<String> removeComment(@RequestHeader("X-Session-Token") String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String postId, @PathVariable String commentId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("postId", postId, "commentId", commentId));
		return idempotency.execute(user.id(), "DELETE", ROUTE_COMMENTS + "/" + commentId, idempotencyKey, hash, () -> {
			desabafos.removeOwnComment(user.id(), postId, commentId);
			return new CommandResponse(200, Map.of("removed", true));
		});
	}

	@PostMapping("/desabafos/posts/{postId}/reactions")
	ResponseEntity<String> react(@RequestHeader("X-Session-Token") String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String postId, @RequestBody ReactDesabafoRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		if (!StringUtils.hasText(request.reaction())) {
			throw new ApiException(400, "INVALID_REACTION", "Reação desconhecida.");
		}
		String hash = idempotency.hash(request);
		return idempotency.execute(user.id(), "POST", ROUTE_REACTIONS, idempotencyKey, hash,
			() -> new CommandResponse(200, desabafos.react(user.id(), postId, request.reaction().trim()
				.toUpperCase())));
	}

	@DeleteMapping("/desabafos/posts/{postId}/reactions")
	ResponseEntity<String> unreact(@RequestHeader("X-Session-Token") String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String postId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("postId", postId));
		return idempotency.execute(user.id(), "DELETE", ROUTE_REACTIONS, idempotencyKey, hash, () -> {
			desabafos.unreact(user.id(), postId);
			return new CommandResponse(200, Map.of("removed", true));
		});
	}
}
