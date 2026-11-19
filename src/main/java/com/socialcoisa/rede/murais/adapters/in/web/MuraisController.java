package com.socialcoisa.rede.murais.adapters.in.web;

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

import com.socialcoisa.rede.idempotency.application.CommandResponse;
import com.socialcoisa.rede.idempotency.application.IdempotencyApplicationService;
import com.socialcoisa.rede.murais.application.MuraisService;
import com.socialcoisa.rede.murais.application.MuraisViews;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;
import com.socialcoisa.rede.shared.web.CursorCodec;

/**
 * Endpoints dos Murais, coleções colaborativas no Explorar. Leitura exige sessão, como o feed
 * geral. Toda escrita passa por idempotência e pelos mesmos gates do resto da plataforma — e
 * nada do que um mural coleciona muda o feed: as publicações continuam onde sempre estiveram.
 */
@RestController
public class MuraisController {

	private static final String ROUTE_MURAIS = "/api/murais";
	private static final String ROUTE_MURAL = "/api/murais/{muralId}";
	private static final String ROUTE_CONTRIBUICOES = "/api/murais/{muralId}/contribuicoes";

	private final MuraisService murais;
	private final IdempotencyApplicationService idempotency;
	private final CurrentUserResolver currentUserResolver;
	private final CursorCodec cursorCodec;

	public MuraisController(MuraisService murais, IdempotencyApplicationService idempotency,
			CurrentUserResolver currentUserResolver, CursorCodec cursorCodec) {
		this.murais = murais;
		this.idempotency = idempotency;
		this.currentUserResolver = currentUserResolver;
		this.cursorCodec = cursorCodec;
	}

	@GetMapping("/murais")
	MuraisViews.MuraisPage list(@RequestHeader("X-Session-Token") String sessionToken,
			@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "24") int limit) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return murais.list(user.id(), cursorCodec.decode(cursor), limit);
	}

	/** Os meus murais na ótica de um post: alimenta o seletor "adicionar ao mural" do card. */
	@GetMapping("/murais/meus")
	MuraisViews.MeusMuraisPage meus(@RequestHeader("X-Session-Token") String sessionToken,
			@RequestParam String postId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return murais.meus(user.id(), postId);
	}

	@GetMapping("/murais/{muralId}")
	MuraisViews.MuralResumo mural(@RequestHeader("X-Session-Token") String sessionToken,
			@PathVariable String muralId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return murais.get(user.id(), muralId);
	}

	@GetMapping("/murais/{muralId}/contribuicoes")
	MuraisViews.ContribuicoesPage contribuicoes(@RequestHeader("X-Session-Token") String sessionToken,
			@PathVariable String muralId, @RequestParam(required = false) String cursor,
			@RequestParam(defaultValue = "24") int limit) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return murais.contributions(user.id(), muralId, cursorCodec.decode(cursor), limit);
	}

	@PostMapping("/murais")
	ResponseEntity<String> create(@RequestHeader("X-Session-Token") String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody CreateMuralRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(request);
		return idempotency.execute(user.id(), "POST", ROUTE_MURAIS, idempotencyKey, hash,
			() -> new CommandResponse(201, murais.create(user.id(), request.title(), request.description())));
	}

	@PatchMapping("/murais/{muralId}")
	MuraisViews.MuralResumo edit(@RequestHeader("X-Session-Token") String sessionToken,
			@PathVariable String muralId, @RequestBody EditMuralRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return murais.edit(user.id(), muralId, request.title(), request.description());
	}

	@DeleteMapping("/murais/{muralId}")
	ResponseEntity<String> removeMural(@RequestHeader("X-Session-Token") String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String muralId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("muralId", muralId));
		return idempotency.execute(user.id(), "DELETE", ROUTE_MURAL, idempotencyKey, hash,
			() -> {
				murais.removeOwnMural(user.id(), muralId);
				return new CommandResponse(200, Map.of("removed", true));
			});
	}

	@PostMapping("/murais/{muralId}/contribuicoes")
	ResponseEntity<String> contribute(@RequestHeader("X-Session-Token") String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String muralId, @RequestBody ContributeMuralRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(request);
		return idempotency.execute(user.id(), "POST", ROUTE_CONTRIBUICOES, idempotencyKey, hash,
			() -> new CommandResponse(201, murais.contribute(user.id(), muralId, request.postId())));
	}

	@DeleteMapping("/murais/{muralId}/contribuicoes/{postId}")
	ResponseEntity<String> removeContribution(@RequestHeader("X-Session-Token") String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String muralId, @PathVariable String postId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("muralId", muralId, "postId", postId));
		return idempotency.execute(user.id(), "DELETE", ROUTE_CONTRIBUICOES + "/" + postId, idempotencyKey, hash,
			() -> {
				murais.removeContribution(user.id(), muralId, postId);
				return new CommandResponse(200, Map.of("removed", true));
			});
	}
}
