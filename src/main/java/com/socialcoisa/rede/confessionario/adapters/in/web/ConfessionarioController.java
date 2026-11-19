package com.socialcoisa.rede.confessionario.adapters.in.web;

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

import com.socialcoisa.rede.confessionario.application.ConfessionarioService;
import com.socialcoisa.rede.confessionario.application.ConfessionarioViews;
import com.socialcoisa.rede.idempotency.application.CommandResponse;
import com.socialcoisa.rede.idempotency.application.IdempotencyApplicationService;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;
import com.socialcoisa.rede.shared.web.CursorCodec;

/**
 * Endpoints do Confessionário, o espaço de histórias do Explorar (nada aqui entra em /feed, no
 * perfil ou na busca — as tabelas são próprias e fora de `posts`). Leitura exige sessão, como
 * os outros espaços de convivência. Toda escrita passa por idempotência e pelos mesmos gates
 * do resto da plataforma; reação devolve contagem, sem lista de quem reagiu.
 */
@RestController
public class ConfessionarioController {

	private static final String ROUTE_POSTS = "/api/confessionario/posts";
	private static final String ROUTE_COMMENTS = "/api/confessionario/posts/{postId}/comments";
	private static final String ROUTE_REACTIONS = "/api/confessionario/posts/{postId}/reactions";

	private final ConfessionarioService confessionario;
	private final IdempotencyApplicationService idempotency;
	private final CurrentUserResolver currentUserResolver;
	private final CursorCodec cursorCodec;

	public ConfessionarioController(ConfessionarioService confessionario,
			IdempotencyApplicationService idempotency, CurrentUserResolver currentUserResolver,
			CursorCodec cursorCodec) {
		this.confessionario = confessionario;
		this.idempotency = idempotency;
		this.currentUserResolver = currentUserResolver;
		this.cursorCodec = cursorCodec;
	}

	@GetMapping("/confessionario")
	ConfessionarioViews.ConfessionarioPage feed(@RequestHeader("X-Session-Token") String sessionToken,
			@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "24") int limit) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return confessionario.getFeed(user.id(), cursorCodec.decode(cursor), limit);
	}

	@GetMapping("/confessionario/posts/{postId}")
	ConfessionarioViews.ConfissaoPostView post(@RequestHeader("X-Session-Token") String sessionToken,
			@PathVariable String postId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return confessionario.getPost(user.id(), postId);
	}

	@PostMapping("/confessionario/posts")
	ResponseEntity<String> publish(@RequestHeader("X-Session-Token") String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody PublishConfissaoRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(request);
		return idempotency.execute(user.id(), "POST", ROUTE_POSTS, idempotencyKey, hash,
			() -> new CommandResponse(201, confessionario.publish(user.id(), request.content())));
	}

	@DeleteMapping("/confessionario/posts/{postId}")
	ResponseEntity<String> removePost(@RequestHeader("X-Session-Token") String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String postId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("postId", postId));
		return idempotency.execute(user.id(), "DELETE", ROUTE_POSTS, idempotencyKey, hash, () -> {
			confessionario.removeOwnPost(user.id(), postId);
			return new CommandResponse(200, Map.of("removed", true));
		});
	}

	@GetMapping("/confessionario/posts/{postId}/comments")
	ConfessionarioViews.ConfessionarioCommentsPage comments(@RequestHeader("X-Session-Token") String sessionToken,
			@PathVariable String postId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return confessionario.getComments(user.id(), postId);
	}

	@PostMapping("/confessionario/posts/{postId}/comments")
	ResponseEntity<String> comment(@RequestHeader("X-Session-Token") String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String postId, @RequestBody CommentConfissaoRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(request);
		return idempotency.execute(user.id(), "POST", ROUTE_COMMENTS, idempotencyKey, hash,
			() -> new CommandResponse(201, confessionario.comment(user.id(), postId, request.content())));
	}

	@DeleteMapping("/confessionario/posts/{postId}/comments/{commentId}")
	ResponseEntity<String> removeComment(@RequestHeader("X-Session-Token") String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String postId, @PathVariable String commentId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("postId", postId, "commentId", commentId));
		return idempotency.execute(user.id(), "DELETE", ROUTE_COMMENTS + "/" + commentId, idempotencyKey, hash,
			() -> {
				confessionario.removeOwnComment(user.id(), postId, commentId);
				return new CommandResponse(200, Map.of("removed", true));
			});
	}

	@PostMapping("/confessionario/posts/{postId}/reactions")
	ResponseEntity<String> react(@RequestHeader("X-Session-Token") String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String postId, @RequestBody ReactConfissaoRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		if (!StringUtils.hasText(request.reaction())) {
			throw new ApiException(400, "INVALID_REACTION", "Reação desconhecida.");
		}
		String hash = idempotency.hash(request);
		return idempotency.execute(user.id(), "POST", ROUTE_REACTIONS, idempotencyKey, hash,
			() -> new CommandResponse(200, confessionario.react(user.id(), postId, request.reaction().trim()
				.toUpperCase())));
	}

	@DeleteMapping("/confessionario/posts/{postId}/reactions")
	ResponseEntity<String> unreact(@RequestHeader("X-Session-Token") String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String postId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("postId", postId));
		return idempotency.execute(user.id(), "DELETE", ROUTE_REACTIONS, idempotencyKey, hash, () -> {
			confessionario.unreact(user.id(), postId);
			return new CommandResponse(200, Map.of("removed", true));
		});
	}
}
