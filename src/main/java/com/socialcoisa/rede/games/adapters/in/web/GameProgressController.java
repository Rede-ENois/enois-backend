package com.socialcoisa.rede.games.adapters.in.web;

import java.util.Map;
import java.util.Optional;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.games.application.GameProgressService;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * O progresso dos jogos vinculado à conta: ler e gravar o estado de um jogo para quem está
 * logado. O estado é OPACO — o jogo manda o que quiser (JSON do cliente) e o servidor guarda;
 * a leitura sem estado nenhum devolve `progresso: null` (o jogo começa do zero do lado dele).
 * Último estado vence no PUT; escrita de conta restrita passa pelo portão de moderação, como
 * toda escrita de conteúdo.
 */
@RestController
class GameProgressController {

	private final GameProgressService progress;
	private final CurrentUserResolver currentUserResolver;
	private final com.socialcoisa.rede.shared.sanctions.SanctionGate sanctionGate;

	GameProgressController(GameProgressService progress, CurrentUserResolver currentUserResolver,
			com.socialcoisa.rede.shared.sanctions.SanctionGate sanctionGate) {
		this.progress = progress;
		this.currentUserResolver = currentUserResolver;
		this.sanctionGate = sanctionGate;
	}

	@GetMapping("/games/{gameId}/progresso")
	Map<String, Object> find(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String gameId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		Optional<com.socialcoisa.rede.games.domain.GameProgress> existente = progress.find(gameId,
			user.id().value());
		// Map.of não aceita valor nulo, e "sem progresso" é exatamente um valor nulo na resposta.
		// O carimbo vai junto: o cliente confere "o estado do servidor é mais novo que o meu?".
		Map<String, Object> resposta = new java.util.HashMap<>();
		resposta.put("gameId", gameId);
		resposta.put("progresso", existente.map(GameProgressController::stateOf).orElse(null));
		resposta.put("updatedAt", existente.map(p -> p.updatedAt().toString()).orElse(null));
		return resposta;
	}

	@PutMapping("/games/{gameId}/progresso")
	Map<String, Object> save(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String gameId, @RequestBody SaveProgressRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		sanctionGate.requireNotRestricted(user.id().value());
		var salvo = progress.save(gameId, user.id().value(), request.state());
		return Map.of("gameId", gameId, "progresso", stateOf(salvo), "updatedAt", salvo.updatedAt().toString());
	}

	private static String stateOf(com.socialcoisa.rede.games.domain.GameProgress p) {
		return p.state();
	}

	record SaveProgressRequest(String state) {
	}
}
