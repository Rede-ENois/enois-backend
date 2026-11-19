package com.socialcoisa.rede.colecoes.adapters.in.web;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.colecoes.application.ColecoesService;
import com.socialcoisa.rede.colecoes.application.ColecoesViews;
import com.socialcoisa.rede.idempotency.application.CommandResponse;
import com.socialcoisa.rede.idempotency.application.IdempotencyApplicationService;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;
import com.socialcoisa.rede.shared.web.CursorCodec;

/**
 * Endpoints das Coleções pessoais. Tudo é do dono: a leitura exige sessão e devolve só as
 * coleções de quem pergunta; coleção alheia responde 404, como se não existisse. Toda escrita
 * passa por idempotência e pelos mesmos gates do resto da plataforma — e nada aqui muda o feed:
 * os posts continuam salvos no perfil, a coleção só organiza os elos.
 */
@RestController
public class ColecoesController {

	private static final String ROUTE_COLECOES = "/api/colecoes";
	private static final String ROUTE_COLECAO = "/api/colecoes/{colecaoId}";
	private static final String ROUTE_POSTS = "/api/colecoes/{colecaoId}/posts";

	private final ColecoesService colecoes;
	private final IdempotencyApplicationService idempotency;
	private final CurrentUserResolver currentUserResolver;
	private final CursorCodec cursorCodec;

	public ColecoesController(ColecoesService colecoes, IdempotencyApplicationService idempotency,
			CurrentUserResolver currentUserResolver, CursorCodec cursorCodec) {
		this.colecoes = colecoes;
		this.idempotency = idempotency;
		this.currentUserResolver = currentUserResolver;
		this.cursorCodec = cursorCodec;
	}

	@GetMapping("/colecoes")
	ColecoesViews.ColecoesPage list(@RequestHeader("X-Session-Token") String sessionToken,
			@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "24") int limit) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return colecoes.list(user.id(), cursorCodec.decode(cursor), limit);
	}

	/** As minhas coleções na ótica de um post: alimenta o seletor "guardar em coleção" do card. */
	@GetMapping("/colecoes/minhas")
	ColecoesViews.MinhasColecoesPage minhas(@RequestHeader("X-Session-Token") String sessionToken,
			@RequestParam String postId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return colecoes.minhas(user.id(), postId);
	}

	@GetMapping("/colecoes/{colecaoId}")
	ColecoesViews.ColecaoResumo colecao(@RequestHeader("X-Session-Token") String sessionToken,
			@PathVariable String colecaoId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return colecoes.get(user.id(), colecaoId);
	}

	@GetMapping("/colecoes/{colecaoId}/posts")
	ColecoesViews.ColecaoPostsPage posts(@RequestHeader("X-Session-Token") String sessionToken,
			@PathVariable String colecaoId, @RequestParam(required = false) String cursor,
			@RequestParam(defaultValue = "24") int limit) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return colecoes.posts(user.id(), colecaoId, cursorCodec.decode(cursor), limit);
	}

	@PostMapping("/colecoes")
	ResponseEntity<String> create(@RequestHeader("X-Session-Token") String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody CreateColecaoRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(request);
		return idempotency.execute(user.id(), "POST", ROUTE_COLECOES, idempotencyKey, hash,
			() -> new CommandResponse(201, colecoes.create(user.id(), request.title(), request.description())));
	}

	@PatchMapping("/colecoes/{colecaoId}")
	ColecoesViews.ColecaoResumo edit(@RequestHeader("X-Session-Token") String sessionToken,
			@PathVariable String colecaoId, @RequestBody EditColecaoRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return colecoes.edit(user.id(), colecaoId, request.title(), request.description());
	}

	@DeleteMapping("/colecoes/{colecaoId}")
	ResponseEntity<String> removeColecao(@RequestHeader("X-Session-Token") String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String colecaoId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("colecaoId", colecaoId));
		return idempotency.execute(user.id(), "DELETE", ROUTE_COLECAO, idempotencyKey, hash, () -> {
			colecoes.removeOwn(user.id(), colecaoId);
			return new CommandResponse(200, Map.of("removed", true));
		});
	}

	@PostMapping("/colecoes/{colecaoId}/posts")
	ResponseEntity<String> addPost(@RequestHeader("X-Session-Token") String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String colecaoId, @RequestBody AddColecaoPostRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(request);
		return idempotency.execute(user.id(), "POST", ROUTE_POSTS, idempotencyKey, hash,
			() -> new CommandResponse(201, colecoes.addPost(user.id(), colecaoId, request.postId())));
	}

	@DeleteMapping("/colecoes/{colecaoId}/posts/{postId}")
	ResponseEntity<String> removePost(@RequestHeader("X-Session-Token") String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String colecaoId, @PathVariable String postId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("colecaoId", colecaoId, "postId", postId));
		return idempotency.execute(user.id(), "DELETE", ROUTE_POSTS + "/" + postId, idempotencyKey, hash, () -> {
			colecoes.removePost(user.id(), colecaoId, postId);
			return new CommandResponse(200, Map.of("removed", true));
		});
	}

	record CreateColecaoRequest(String title, String description) {
	}

	record EditColecaoRequest(String title, String description) {
	}

	record AddColecaoPostRequest(String postId) {
	}
}
