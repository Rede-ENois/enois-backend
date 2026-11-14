package com.socialcoisa.rede.eroleplay.adapters.in.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.eroleplay.application.DiceService;
import com.socialcoisa.rede.eroleplay.domain.DiceRoll;
import com.socialcoisa.rede.eroleplay.domain.RollRequest;
import com.socialcoisa.rede.eroleplay.domain.SheetVisibility;
import com.socialcoisa.rede.idempotency.application.CommandResponse;
import com.socialcoisa.rede.idempotency.application.IdempotencyApplicationService;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * Endpoints do motor de dados.
 *
 * <p>Nenhuma rota aqui é pública: rolagem é registro do que aconteceu na mesa, e o histórico é
 * visível só para quem participa. Toda escrita responde sob {@code Idempotency-Key} — clicar duas
 * vezes no botão de rolar não rola duas vezes, e responder um pedido duas vezes não rola de novo.</p>
 */
@RestController
@RequestMapping("/roleplay/campaigns/{campaignId}/rolls")
class DiceRollController {

	private final DiceService dice;
	private final CurrentUserResolver currentUserResolver;
	private final IdempotencyApplicationService idempotency;

	DiceRollController(DiceService dice, CurrentUserResolver currentUserResolver,
			IdempotencyApplicationService idempotency) {
		this.dice = dice;
		this.currentUserResolver = currentUserResolver;
		this.idempotency = idempotency;
	}

	/** Rola e registra. O resultado sai do servidor, nunca do corpo. */
	@PostMapping
	ResponseEntity<String> roll(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String campaignId, @RequestBody RollInput request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		requireExpression(request);
		String hash = idempotency.hash(Map.of("campaignId", campaignId, "action", "roll",
			"expression", request.expression(), "label", nullSafe(request.label()),
			"visibility", nullSafe(request.visibility())));
		return idempotency.execute(user.id(), "POST", "/api/roleplay/campaigns/{campaignId}/rolls",
			idempotencyKey, hash, () -> {
				DiceRoll roll = dice.roll(campaignId, user.id().value(), request.expression(), request.label(),
					visibilityOrNull(request.visibility()));
				return new CommandResponse(201, rollView(roll));
			});
	}

	/** O histórico da campanha, já no recorte de quem pede. */
	@GetMapping
	List<RollView> history(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String campaignId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return dice.history(campaignId, user.id().value()).stream().map(DiceRollController::rollView).toList();
	}

	/** Uma rolagem, no recorte de quem pede. */
	@GetMapping("/{rollId}")
	RollView one(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String campaignId, @PathVariable String rollId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return rollView(dice.read(campaignId, rollId, user.id().value()));
	}

	/** O mestre pede uma rolagem a um jogador. */
	@PostMapping("/requests")
	ResponseEntity<String> request(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String campaignId, @RequestBody MasterRequestInput request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		if (request == null || request.playerId() == null || request.playerId().isBlank()) {
			throw new ApiException(400, "ROLL_PLAYER_REQUIRED", "Informe a pessoa a quem o pedido vai.");
		}
		requireExpression(request);
		String hash = idempotency.hash(Map.of("campaignId", campaignId, "action", "request-roll",
			"playerId", request.playerId(), "expression", request.expression(),
			"label", nullSafe(request.label()), "reason", nullSafe(request.reason()),
			"visibility", nullSafe(request.visibility())));
		return idempotency.execute(user.id(), "POST", "/api/roleplay/campaigns/{campaignId}/rolls/requests",
			idempotencyKey, hash, () -> {
				RollRequest created = dice.requestRoll(campaignId, user.id().value(), request.playerId(),
					request.expression(), request.label(), visibilityOrNull(request.visibility()),
					request.reason());
				return new CommandResponse(201, requestView(created));
			});
	}

	/** Os pedidos que quem pede enxerga: o mestre vê os que fez, o jogador os que recebeu. */
	@GetMapping("/requests")
	List<RollRequestView> requests(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String campaignId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return dice.requests(campaignId, user.id().value()).stream()
			.map(DiceRollController::requestView).toList();
	}

	/** Um pedido, só para os dois lados da conversa. */
	@GetMapping("/requests/{requestId}")
	RollRequestView requestOne(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String campaignId, @PathVariable String requestId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return requestView(dice.readRequest(campaignId, requestId, user.id().value()));
	}

	/** O jogador responde o pedido: o teste é o pedido, a resposta é a rolagem. */
	@PostMapping("/requests/{requestId}/answer")
	ResponseEntity<String> answer(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String campaignId, @PathVariable String requestId,
			@RequestBody AnswerInput request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String label = request == null ? null : request.label();
		String visibility = request == null ? null : request.visibility();
		String hash = idempotency.hash(Map.of("campaignId", campaignId, "action", "answer-roll",
			"requestId", requestId, "label", nullSafe(label), "visibility", nullSafe(visibility)));
		return idempotency.execute(user.id(), "POST",
			"/api/roleplay/campaigns/{campaignId}/rolls/requests/{requestId}/answer", idempotencyKey, hash,
			() -> {
				DiceRoll roll = dice.answerRoll(campaignId, requestId, user.id().value(), label,
					visibilityOrNull(visibility));
				return new CommandResponse(201, rollView(roll));
			});
	}

	private static void requireExpression(RollInput request) {
		if (request == null || request.expression() == null || request.expression().isBlank()) {
			throw new ApiException(400, "DICE_EXPRESSION_REQUIRED", "Informe o que rolar.");
		}
	}

	private static void requireExpression(MasterRequestInput request) {
		if (request == null || request.expression() == null || request.expression().isBlank()) {
			throw new ApiException(400, "DICE_EXPRESSION_REQUIRED", "Informe o que rolar.");
		}
	}

	private static SheetVisibility visibilityOrNull(String name) {
		if (name == null || name.isBlank()) {
			return null;
		}
		return SheetVisibility.of(name);
	}

	private static String nullSafe(String value) {
		return value == null ? "" : value.trim();
	}

	private static RollView rollView(DiceRoll roll) {
		return new RollView(roll.id(), roll.campaignId(), roll.rollerId(), roll.expression(), roll.dice(),
			roll.total(), roll.label(), roll.visibility().name(), roll.requestId(), roll.createdAt());
	}

	private static RollRequestView requestView(RollRequest request) {
		return new RollRequestView(request.id(), request.campaignId(), request.masterId(), request.playerId(),
			request.expression(), request.label(), request.maxVisibility().name(), request.reason(),
			request.status().name(), request.answeredRollId(), request.createdAt(), request.answeredAt());
	}

	record RollInput(String expression, String label, String visibility) {
	}

	record MasterRequestInput(String playerId, String expression, String label, String visibility,
			String reason) {
	}

	record AnswerInput(String label, String visibility) {
	}

	record RollView(String id, String campaignId, String rollerId, String expression, List<Integer> dice,
			long total, String label, String visibility, String requestId, Instant createdAt) {
	}

	record RollRequestView(String id, String campaignId, String masterId, String playerId, String expression,
			String label, String maxVisibility, String reason, String status, String answeredRollId,
			Instant createdAt, Instant answeredAt) {
	}
}
