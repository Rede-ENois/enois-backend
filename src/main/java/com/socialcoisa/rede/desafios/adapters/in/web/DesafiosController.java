package com.socialcoisa.rede.desafios.adapters.in.web;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.desafios.application.DesafiosService;
import com.socialcoisa.rede.desafios.application.DesafiosViews;
import com.socialcoisa.rede.idempotency.application.CommandResponse;
import com.socialcoisa.rede.idempotency.application.IdempotencyApplicationService;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;
import com.socialcoisa.rede.shared.web.CursorCodec;

/**
 * Endpoints de Desafios, a experiência de desafio comunitário do Explorar. Leitura exige
 * sessão, como os outros espaços de convivência; toda escrita passa por idempotência e pelos
 * mesmos gates do resto da plataforma. A torcida devolve contagem, sem lista de quem torceu.
 */
@RestController
public class DesafiosController {

	private static final String ROUTE_DESAFIOS = "/api/desafios";
	private static final String ROUTE_PARTICIPACOES = "/api/desafios/{desafioId}/participacoes";
	private static final String ROUTE_CHEERS = "/api/desafios/participacoes/{participacaoId}/torcidas";

	private final DesafiosService desafios;
	private final IdempotencyApplicationService idempotency;
	private final CurrentUserResolver currentUserResolver;
	private final CursorCodec cursorCodec;

	public DesafiosController(DesafiosService desafios, IdempotencyApplicationService idempotency,
			CurrentUserResolver currentUserResolver, CursorCodec cursorCodec) {
		this.desafios = desafios;
		this.idempotency = idempotency;
		this.currentUserResolver = currentUserResolver;
		this.cursorCodec = cursorCodec;
	}

	@GetMapping("/desafios")
	DesafiosViews.DesafiosPage feed(@RequestHeader("X-Session-Token") String sessionToken,
			@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "24") int limit) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return desafios.getFeed(user.id(), cursorCodec.decode(cursor), limit);
	}

	@GetMapping("/desafios/{desafioId}")
	DesafiosViews.DesafioView desafio(@RequestHeader("X-Session-Token") String sessionToken,
			@PathVariable String desafioId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return desafios.getDesafio(user.id(), desafioId);
	}

	@GetMapping("/desafios/{desafioId}/participacoes")
	DesafiosViews.ParticipacoesPage participacoes(@RequestHeader("X-Session-Token") String sessionToken,
			@PathVariable String desafioId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return desafios.getParticipacoes(user.id(), desafioId);
	}

	@PostMapping("/desafios")
	ResponseEntity<String> publish(@RequestHeader("X-Session-Token") String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody PublishDesafioRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(request);
		return idempotency.execute(user.id(), "POST", ROUTE_DESAFIOS, idempotencyKey, hash,
			() -> new CommandResponse(201, desafios.publish(user.id(), request.title(), request.description(),
				request.endsAt())));
	}

	@PostMapping("/desafios/{desafioId}/fechamento")
	ResponseEntity<String> close(@RequestHeader("X-Session-Token") String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String desafioId, @RequestBody CloseDesafioRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(request);
		return idempotency.execute(user.id(), "POST", ROUTE_DESAFIOS + "/" + desafioId + "/fechamento",
			idempotencyKey, hash,
			() -> new CommandResponse(200, desafios.close(user.id(), desafioId, request.destaqueId())));
	}

	@DeleteMapping("/desafios/{desafioId}")
	ResponseEntity<String> remove(@RequestHeader("X-Session-Token") String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String desafioId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("desafioId", desafioId));
		return idempotency.execute(user.id(), "DELETE", ROUTE_DESAFIOS + "/" + desafioId, idempotencyKey, hash,
			() -> {
				desafios.removeOwn(user.id(), desafioId);
				return new CommandResponse(200, Map.of("removed", true));
			});
	}

	@PostMapping("/desafios/{desafioId}/participacoes")
	ResponseEntity<String> participate(@RequestHeader("X-Session-Token") String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String desafioId, @RequestBody ParticipateRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(request);
		return idempotency.execute(user.id(), "POST", ROUTE_PARTICIPACOES, idempotencyKey, hash,
			() -> new CommandResponse(201, desafios.participate(user.id(), desafioId, request.content())));
	}

	@DeleteMapping("/desafios/{desafioId}/participacoes/{participacaoId}")
	ResponseEntity<String> removeParticipacao(@RequestHeader("X-Session-Token") String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String desafioId, @PathVariable String participacaoId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("desafioId", desafioId, "participacaoId", participacaoId));
		return idempotency.execute(user.id(), "DELETE",
			ROUTE_PARTICIPACOES + "/" + participacaoId, idempotencyKey, hash, () -> {
				desafios.removeOwnParticipacao(user.id(), desafioId, participacaoId);
				return new CommandResponse(200, Map.of("removed", true));
			});
	}

	@PostMapping("/desafios/participacoes/{participacaoId}/torcidas")
	ResponseEntity<String> cheer(@RequestHeader("X-Session-Token") String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String participacaoId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("participacaoId", participacaoId));
		return idempotency.execute(user.id(), "POST", ROUTE_CHEERS, idempotencyKey, hash,
			() -> new CommandResponse(200, desafios.cheer(user.id(), participacaoId)));
	}

	@DeleteMapping("/desafios/participacoes/{participacaoId}/torcidas")
	ResponseEntity<String> uncheer(@RequestHeader("X-Session-Token") String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String participacaoId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("participacaoId", participacaoId));
		return idempotency.execute(user.id(), "DELETE", ROUTE_CHEERS, idempotencyKey, hash, () -> {
			desafios.uncheer(user.id(), participacaoId);
			return new CommandResponse(200, Map.of("removed", true));
		});
	}
}
