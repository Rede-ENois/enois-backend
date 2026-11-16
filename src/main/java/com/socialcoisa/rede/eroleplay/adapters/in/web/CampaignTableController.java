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

import com.socialcoisa.rede.calls.application.CallService;
import com.socialcoisa.rede.eroleplay.application.CampaignService;
import com.socialcoisa.rede.eroleplay.application.TableStateService;
import com.socialcoisa.rede.eroleplay.domain.GameTable;
import com.socialcoisa.rede.eroleplay.domain.TableState;
import com.socialcoisa.rede.eroleplay.domain.TableToken;
import com.socialcoisa.rede.idempotency.application.CommandResponse;
import com.socialcoisa.rede.idempotency.application.IdempotencyApplicationService;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * Endpoints da mesa: estado vivo (ordem de fala, vez, cena), tokens posicionados e voz do Épapo.
 *
 * <p>A voz NÃO tem sala própria: abrir a voz chama o contrato existente de chamadas e a mesa passa
 * a referenciar a sessão dele; o token de entrada continua saindo só pelo {@code join} das
 * chamadas. Toda escrita responde sob {@code Idempotency-Key} — clicar duas vezes não grava duas.
 * O aviso "a mesa mudou" sai pelo stream de mensagens (evento {@code campaign}); o estado nunca
 * viaja no stream, o cliente recarrega por aqui.</p>
 */
@RestController
@RequestMapping("/roleplay/campaigns/{campaignId}/tables/{tableId}")
class CampaignTableController {

	private final TableStateService tables;
	private final CallService calls;
	private final CampaignService campaigns;
	private final CurrentUserResolver currentUserResolver;
	private final IdempotencyApplicationService idempotency;

	CampaignTableController(TableStateService tables, CallService calls, CampaignService campaigns,
			CurrentUserResolver currentUserResolver, IdempotencyApplicationService idempotency) {
		this.tables = tables;
		this.calls = calls;
		this.campaigns = campaigns;
		this.currentUserResolver = currentUserResolver;
		this.idempotency = idempotency;
	}

	// ------------------------------------------------------------------ estado

	/** O estado vivo da mesa (ordem de fala, vez, cena); nasce vazio na primeira leitura. */
	@GetMapping("/state")
	StateView state(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String campaignId, @PathVariable String tableId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return view(tables.state(tableId, user.id().value()));
	}

	/** Grava a ordem de fala. Só o mestre. */
	@PostMapping("/state/turn-order")
	ResponseEntity<String> turnOrder(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String campaignId, @PathVariable String tableId, @RequestBody TurnOrderInput request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return write(user, "POST", "/api/roleplay/campaigns/{campaignId}/tables/{tableId}/state/turn-order",
			idempotencyKey, Map.of("action", "turn-order", "order",
				request.order() == null ? "" : String.join(",", request.order())),
			() -> view(tables.setTurnOrder(tableId, user.id().value(), request.order())));
	}

	/** Passa a vez. O mestre passa para qualquer um; jogador passa para si mesmo. */
	@PostMapping("/state/turn")
	ResponseEntity<String> turn(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String campaignId, @PathVariable String tableId, @RequestBody TurnInput request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return write(user, "POST", "/api/roleplay/campaigns/{campaignId}/tables/{tableId}/state/turn",
			idempotencyKey, Map.of("action", "turn", "target", nullSafe(request.userId())),
			() -> view(tables.setTurn(tableId, user.id().value(), request.userId())));
	}

	/** Encerra a vez sem próximo definido. */
	@PostMapping("/state/turn/clear")
	ResponseEntity<String> clearTurn(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String campaignId, @PathVariable String tableId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return write(user, "POST", "/api/roleplay/campaigns/{campaignId}/tables/{tableId}/state/turn/clear",
			idempotencyKey, Map.of("action", "turn-clear"),
			() -> view(tables.clearTurn(tableId, user.id().value())));
	}

	/** Grava a cena visível por todos. Só o mestre. */
	@PostMapping("/state/scene")
	ResponseEntity<String> scene(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String campaignId, @PathVariable String tableId, @RequestBody SceneInput request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return write(user, "POST", "/api/roleplay/campaigns/{campaignId}/tables/{tableId}/state/scene",
			idempotencyKey, Map.of("action", "scene", "scene", nullSafe(request.scene())),
			() -> view(tables.setScene(tableId, user.id().value(), request.scene())));
	}

	// ------------------------------------------------------------------ tokens

	/** Os tokens posicionados na mesa. */
	@GetMapping("/tokens")
	List<TokenView> tokens(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String campaignId, @PathVariable String tableId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return tables.tokens(tableId, user.id().value()).stream().map(CampaignTableController::tokenView).toList();
	}

	/** Cria um token. Sem ownerId é peça do cenário (mestre); com ownerId igual ao actor, é do jogador. */
	@PostMapping("/tokens")
	ResponseEntity<String> createToken(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String campaignId, @PathVariable String tableId, @RequestBody TokenInput request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String owner = request.ownerId() == null || request.ownerId().isBlank() ? null : request.ownerId();
		return write(user, "POST", "/api/roleplay/campaigns/{campaignId}/tables/{tableId}/tokens",
			idempotencyKey, Map.of("action", "token-create", "owner", nullSafe(owner), "label",
				nullSafe(request.label()), "color", nullSafe(request.color()), "x", request.posX(), "y",
				request.posY()),
			() -> tokenView(tables.createToken(tableId, user.id().value(), owner, request.label(), request.color(),
				request.posX(), request.posY())));
	}

	/** Move um token para outra célula do grid. Dono move o dele; mestre move qualquer um. */
	@PostMapping("/tokens/{tokenId}/move")
	ResponseEntity<String> moveToken(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String campaignId, @PathVariable String tableId, @PathVariable String tokenId,
			@RequestBody MoveInput request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return write(user, "POST", "/api/roleplay/campaigns/{campaignId}/tables/{tableId}/tokens/{tokenId}/move",
			idempotencyKey, Map.of("action", "token-move", "token", tokenId, "x", request.posX(), "y",
				request.posY()),
			() -> tokenView(tables.moveToken(tableId, tokenId, user.id().value(), request.posX(), request.posY())));
	}

	/** Remove um token. O dono remove o dele; o mestre remove qualquer um. */
	@PostMapping("/tokens/{tokenId}/remove")
	ResponseEntity<String> removeToken(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String campaignId, @PathVariable String tableId, @PathVariable String tokenId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return write(user, "POST", "/api/roleplay/campaigns/{campaignId}/tables/{tableId}/tokens/{tokenId}/remove",
			idempotencyKey, Map.of("action", "token-remove", "token", tokenId),
			() -> {
				tables.removeToken(tableId, tokenId, user.id().value());
				return Map.of("removed", tokenId);
			});
	}

	// ------------------------------------------------------------------ voz

	/**
	 * Abre (ou devolve) a sala de voz da mesa, pelo contrato existente de chamadas. A resposta é a
	 * chamada do Épapo: o front embute com o {@code join} de sempre. A mesa fica com a referência.
	 */
	@PostMapping("/voice")
	ResponseEntity<String> openVoice(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String campaignId, @PathVariable String tableId, @RequestBody(required = false) VoiceInput request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String kind = request == null || request.kind() == null || request.kind().isBlank() ? "AUDIO"
			: request.kind().trim().toUpperCase();
		return write(user, "POST", "/api/roleplay/campaigns/{campaignId}/tables/{tableId}/voice",
			idempotencyKey, Map.of("action", "voice-open", "kind", kind),
			() -> view(tables.openVoice(tableId, user.id().value(),
				// A referência guardada na mesa é o id da chamada: o room id só sai pelo join,
				// que assina token — a mesa guarda o que pode ser público.
				actor -> calls.startTable(tableId, actor, parseKind(kind)).id())));
	}

	/** A chamada aberta da mesa, para o painel mostrar "ao vivo" (nulo quando ninguém está na voz). */
	@GetMapping("/voice/open")
	CallService.CallView openVoiceCall(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String campaignId, @PathVariable String tableId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		// O id da mesa veio do path de ESTA campanha: a mesa tem de pertencer a ela antes de o
		// serviço de chamadas decidir pelo tableId sozinho — a rota não confirma chamada de mesa
		// de outra campanha para quem tem as duas.
		tables.requireTableOfCampaign(campaignId, tableId);
		return calls.openOfTable(tableId, user.id().value()).orElse(null);
	}

	// ------------------------------------------------------------------ auxiliares

	private ResponseEntity<String> write(CurrentUser user, String method, String route, String idempotencyKey,
			Map<String, Object> parts, java.util.function.Supplier<Object> command) {
		String hash = idempotency.hash(parts);
		return idempotency.execute(user.id(), method, route, idempotencyKey, hash,
			() -> new CommandResponse(200, command.get()));
	}

	private com.socialcoisa.rede.calls.domain.CallKind parseKind(String kind) {
		try {
			return com.socialcoisa.rede.calls.domain.CallKind.parse(kind);
		}
		catch (RuntimeException e) {
			throw new ApiException(400, "CALL_KIND_INVALID", "Escolha AUDIO ou VIDEO.");
		}
	}

	private static TableView view(GameTable table) {
		return new TableView(table.id(), table.campaignId(), table.name(), table.ownerId(), table.hasVoiceRoom(),
			table.isOpen(), table.createdAt(), table.updatedAt());
	}

	private static StateView view(TableState state) {
		return new StateView(state.tableId(), state.turnOrder(), state.activeTurnUserId(), state.turnStartedAt(),
			state.scene(), state.updatedAt());
	}

	private static TokenView tokenView(TableToken token) {
		return new TokenView(token.id(), token.ownerId(), token.label(), token.color(), token.posX(), token.posY(),
			token.createdAt(), token.updatedAt());
	}

	private static String nullSafe(String value) {
		return value == null ? "" : value;
	}

	record TableView(String id, String campaignId, String name, String ownerId, boolean hasVoice, boolean open,
			Instant createdAt, Instant updatedAt) {
	}

	record StateView(String tableId, List<String> turnOrder, String activeTurnUserId, Instant turnStartedAt,
			String scene, Instant updatedAt) {
	}

	record TokenView(String id, String ownerId, String label, String color, int posX, int posY, Instant createdAt,
			Instant updatedAt) {
	}

	record TurnOrderInput(List<String> order) {
	}

	record TurnInput(String userId) {
	}

	record SceneInput(String scene) {
	}

	record TokenInput(String ownerId, String label, String color, int posX, int posY) {
	}

	record MoveInput(int posX, int posY) {
	}

	record VoiceInput(String kind) {
	}
}
