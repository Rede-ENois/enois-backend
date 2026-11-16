package com.socialcoisa.rede.eroleplay.adapters.in.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.eroleplay.application.WorldService;
import com.socialcoisa.rede.eroleplay.domain.CharacterSheet;
import com.socialcoisa.rede.eroleplay.domain.WorldEntry;
import com.socialcoisa.rede.eroleplay.domain.WorldKind;
import com.socialcoisa.rede.idempotency.application.CommandResponse;
import com.socialcoisa.rede.idempotency.application.IdempotencyApplicationService;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * Endpoints do World Builder: o atlas do mundo da campanha.
 *
 * <p>Nenhuma rota aqui é pública — o atlas é da mesa. Escritas respondem sob {@code
 * Idempotency-Key} e avisam pelo evento {@code campaign} do stream (o cliente recarrega por
 * aqui, onde o recorte é aplicado). O id da ficha vinculada nunca sai na resposta: sai o nome
 * do personagem, que é o que a mesa lê.</p>
 */
@RestController
@RequestMapping("/roleplay/campaigns/{campaignId}/world")
class CampaignWorldController {

	private final WorldService world;
	private final CurrentUserResolver currentUserResolver;
	private final IdempotencyApplicationService idempotency;

	CampaignWorldController(WorldService world, CurrentUserResolver currentUserResolver,
			IdempotencyApplicationService idempotency) {
		this.world = world;
		this.currentUserResolver = currentUserResolver;
		this.idempotency = idempotency;
	}

	/** Registra uma entrada no atlas. Qualquer participante pode. */
	@PostMapping
	ResponseEntity<String> create(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String campaignId, @RequestBody EntryInput request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		WorldKind kind = requireKind(request.kind());
		String hash = idempotency.hash(Map.of("campaignId", campaignId, "action", "world-create",
			"kind", kind.name(), "title", nullSafe(request.title()), "body", nullSafe(request.body()),
			"tags", request.tags() == null ? "" : String.join(",", request.tags()),
			"sheetId", nullSafe(request.sheetId())));
		return idempotency.execute(user.id(), "POST", "/api/roleplay/campaigns/{campaignId}/world",
			idempotencyKey, hash, () -> new CommandResponse(201, view(world.create(campaignId, kind,
				request.title(), request.body(), request.tags(), request.sheetId(), user.id().value()),
				campaignId, user.id().value())));
	}

	/** O atlas da campanha, filtrável por tipo e texto. */
	@GetMapping
	List<EntryView> atlas(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String campaignId,
			@RequestParam(name = "kind", required = false) String kind,
			@RequestParam(name = "q", required = false) String query) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return world.atlas(campaignId, parseKind(kind), query, user.id().value()).stream()
			.map(entry -> view(entry, campaignId, user.id().value()))
			.toList();
	}

	/** Uma entrada, para um participante. */
	@GetMapping("/{entryId}")
	EntryView one(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String campaignId, @PathVariable String entryId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return view(world.read(campaignId, entryId, user.id().value()), campaignId, user.id().value());
	}

	/** Edita uma entrada: o autor no que é dele, o mestre em tudo. */
	@PutMapping("/{entryId}")
	ResponseEntity<String> edit(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String campaignId, @PathVariable String entryId, @RequestBody EntryInput request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		WorldKind kind = requireKind(request.kind());
		String hash = idempotency.hash(Map.of("campaignId", campaignId, "action", "world-edit", "entry", entryId,
			"kind", kind.name(), "title", nullSafe(request.title()), "body", nullSafe(request.body()),
			"tags", request.tags() == null ? "" : String.join(",", request.tags()),
			"sheetId", nullSafe(request.sheetId())));
		return idempotency.execute(user.id(), "PUT", "/api/roleplay/campaigns/{campaignId}/world/{entryId}",
			idempotencyKey, hash, () -> new CommandResponse(200, view(world.edit(campaignId, entryId, kind,
				request.title(), request.body(), request.tags(), request.sheetId(), user.id().value()),
				campaignId, user.id().value())));
	}

	/** Remove uma entrada: o autor no que é dele, o mestre em tudo. */
	@DeleteMapping("/{entryId}")
	ResponseEntity<String> remove(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String campaignId, @PathVariable String entryId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("campaignId", campaignId, "action", "world-remove", "entry", entryId));
		return idempotency.execute(user.id(), "DELETE", "/api/roleplay/campaigns/{campaignId}/world/{entryId}",
			idempotencyKey, hash, () -> {
				world.remove(campaignId, entryId, user.id().value());
				return new CommandResponse(200, Map.of("removed", entryId));
			});
	}

	// ------------------------------------------------------------------ auxiliares

	private WorldKind requireKind(String value) {
		if (value == null || value.isBlank()) {
			throw new ApiException(400, "WORLD_KIND_REQUIRED", "Escolha o tipo da entrada.");
		}
		return parseKind(value);
	}

	private WorldKind parseKind(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return WorldKind.of(value);
		}
		catch (IllegalArgumentException rejected) {
			throw new ApiException(400, "WORLD_KIND_INVALID", "O tipo é LOCALE, CHARACTER, ITEM, PLOT ou LORE.");
		}
	}

	private static String nullSafe(String value) {
		return value == null ? "" : value;
	}

	/** A projeção da entrada: o id da ficha vinculada NUNCA sai — sai o nome do personagem. */
	private EntryView view(WorldEntry entry, String campaignId, String readerId) {
		String sheetName = entry.linkedSheetId() == null ? null
			: world.sheetNameOf(campaignId, entry.linkedSheetId(), readerId);
		return new EntryView(entry.id(), entry.campaignId(), entry.kind().name(), entry.title(), entry.body(),
			entry.tags(), entry.linkedSheetId() != null, sheetName, entry.authorId(), entry.createdAt(),
			entry.updatedAt());
	}

	record EntryInput(String kind, String title, String body, List<String> tags, String sheetId) {
	}

	record EntryView(String id, String campaignId, String kind, String title, String body, List<String> tags,
			boolean linkedToSheet, String linkedSheetName, String authorId, Instant createdAt, Instant updatedAt) {
	}
}
