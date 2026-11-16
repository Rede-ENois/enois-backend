package com.socialcoisa.rede.eroleplay.adapters.in.web;

import java.time.Instant;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.eroleplay.application.LibraryService;
import com.socialcoisa.rede.idempotency.application.CommandResponse;
import com.socialcoisa.rede.idempotency.application.IdempotencyApplicationService;
import com.socialcoisa.rede.shared.web.PageResponse;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;
import com.socialcoisa.rede.shared.web.CursorCodec;

import java.util.Map;

/**
 * A biblioteca do Éroleplay: a ficha publicada com opt-in do dono.
 *
 * <p>Rotas públicas (POST/DELETE exigem sessão e são do DONO da ficha; GET é leitura pública no
 * caminho tolerante — biblioteca é superfície de descoberta). O id da FICHA de origem não sai
 * aqui: a publicação tem id próprio e o corpo público só entrega o recorte que o dono abriu.</p>
 */
@RestController
public class LibraryController {

	private final LibraryService library;
	private final CurrentUserResolver currentUserResolver;
	private final IdempotencyApplicationService idempotency;
	private final CursorCodec cursorCodec;

	LibraryController(LibraryService library, CurrentUserResolver currentUserResolver,
			IdempotencyApplicationService idempotency, CursorCodec cursorCodec) {
		this.library = library;
		this.currentUserResolver = currentUserResolver;
		this.idempotency = idempotency;
		this.cursorCodec = cursorCodec;
	}

	/** A biblioteca: publicações recentes, busca opcional por personagem/sistema. Leitura pública. */
	@GetMapping("/roleplay/library")
	PageResponse<LibraryView> library(@RequestParam(required = false) String query,
			@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
		Instant before = cursorCodec.decode(cursor).orElse(null);
		List<LibraryView> items = library.list(query, before, limit).stream().map(LibraryView::from).toList();
		String next = items.size() == limit ? cursorCodec.encode(before(items)) : null;
		return new PageResponse<>(items, next);
	}

	/** Uma publicação. 404 unificado para inexistente — sem enumeração. */
	@GetMapping("/roleplay/library/{publicationId}")
	LibraryView publication(@PathVariable String publicationId) {
		return LibraryView.from(library.read(publicationId));
	}

	/** Publica (ou republica) a própria ficha. Opt-in do dono, sob idempotency key. */
	@PostMapping("/roleplay/campaigns/{campaignId}/sheets/{sheetId}/publish")
	ResponseEntity<String> publish(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String campaignId, @PathVariable String sheetId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(new PublishInput(campaignId, sheetId));
		return idempotency.execute(user.id(), "POST",
			"/api/roleplay/campaigns/" + campaignId + "/sheets/" + sheetId + "/publish", idempotencyKey, hash,
			() -> new CommandResponse(201, LibraryView.from(library.publish(campaignId, sheetId, user.id().value()))));
	}

	/** Retira a ficha da biblioteca (o dono). A ficha de mesa segue intata. */
	@DeleteMapping("/roleplay/campaigns/{campaignId}/sheets/{sheetId}/publish")
	ResponseEntity<String> unpublish(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String campaignId, @PathVariable String sheetId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(new PublishInput(campaignId, sheetId));
		return idempotency.execute(user.id(), "DELETE",
			"/api/roleplay/campaigns/" + campaignId + "/sheets/" + sheetId + "/publish", idempotencyKey, hash,
			() -> {
				library.unpublish(campaignId, sheetId, user.id().value());
				return new CommandResponse(200, "{\"status\":\"unpublished\"}");
			});
	}

	private Instant before(List<LibraryView> items) {
		return items.get(items.size() - 1).publishedAt();
	}

	record PublishInput(String campaignId, String sheetId) {
	}

	/**
	 * A projeção pública: o id da FICHA e da CAMPANHA não saem — a publicação é o endereço público
	 * (o autor do personagem fica no perfil do dono; a origem da ficha é interno de jogo).
	 */
	record LibraryView(String id, String characterName, String gameSystem, Map<String, String> fields,
			Instant publishedAt, Instant updatedAt) {

		static LibraryView from(com.socialcoisa.rede.eroleplay.domain.LibrarySheet sheet) {
			return new LibraryView(sheet.id(), sheet.characterName(), sheet.gameSystem(), sheet.fields(),
				sheet.publishedAt(), sheet.updatedAt());
		}
	}
}
