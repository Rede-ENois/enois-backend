package com.socialcoisa.rede.eroleplay.adapters.in.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.eroleplay.application.SheetService;
import com.socialcoisa.rede.eroleplay.domain.DerivedAttribute;
import com.socialcoisa.rede.eroleplay.domain.SheetEntry;
import com.socialcoisa.rede.eroleplay.domain.SheetField;
import com.socialcoisa.rede.eroleplay.domain.SheetTemplate;
import com.socialcoisa.rede.eroleplay.domain.SheetVisibility;
import com.socialcoisa.rede.idempotency.application.CommandResponse;
import com.socialcoisa.rede.idempotency.application.IdempotencyApplicationService;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * Endpoints da ficha de personagem.
 *
 * <p>Nenhuma rota aqui é pública. O que se lê é a ficha de alguém, e ficha é dado pessoal de jogo:
 * a leitura exige sessão e participação na campanha, e o recorte de cada campo é aplicado no
 * serviço — a resposta já sai filtrada.</p>
 */
@RestController
@RequestMapping("/roleplay/campaigns/{campaignId}/sheets")
class CharacterSheetController {

	private final SheetService sheets;
	private final CurrentUserResolver currentUserResolver;
	private final IdempotencyApplicationService idempotency;

	CharacterSheetController(SheetService sheets, CurrentUserResolver currentUserResolver,
			IdempotencyApplicationService idempotency) {
		this.sheets = sheets;
		this.currentUserResolver = currentUserResolver;
		this.idempotency = idempotency;
	}

	/** O modelo da campanha: campos, tipos, obrigatoriedade e até onde cada um pode abrir. */
	@GetMapping("/template")
	TemplateView template(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String campaignId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return templateView(sheets.template(campaignId, user.id().value()));
	}

	/** Define o modelo. Só o mestre, e só uma vez. */
	@PostMapping("/template")
	ResponseEntity<String> defineTemplate(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String campaignId, @RequestBody TemplateRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		Map<String, SheetVisibility> visibilities = visibilitiesOf(request);
		String hash = idempotency.hash(Map.of("campaignId", campaignId, "action", "define-template",
			"visibilities", visibilities));
		return idempotency.execute(user.id(), "POST",
			"/api/roleplay/campaigns/{campaignId}/sheets/template", idempotencyKey, hash, () -> {
				SheetTemplate template = sheets.defineTemplate(campaignId, user.id().value(), visibilities);
				return new CommandResponse(201, templateView(template));
			});
	}

	/** Ajusta a abertura máxima dos campos. Fechar um campo fecha o que já está preenchido. */
	@PutMapping("/template")
	ResponseEntity<String> updateTemplate(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String campaignId, @RequestBody TemplateRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		Map<String, SheetVisibility> visibilities = visibilitiesOf(request);
		String hash = idempotency.hash(Map.of("campaignId", campaignId, "action", "update-template",
			"visibilities", visibilities));
		return idempotency.execute(user.id(), "PUT",
			"/api/roleplay/campaigns/{campaignId}/sheets/template", idempotencyKey, hash, () -> {
				SheetTemplate template = sheets.updateTemplate(campaignId, user.id().value(), visibilities);
				return new CommandResponse(200, templateView(template));
			});
	}

	/** Remove o modelo — recusado enquanto houver ficha preenchida. */
	@DeleteMapping("/template")
	ResponseEntity<String> deleteTemplate(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String campaignId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("campaignId", campaignId, "action", "delete-template"));
		return idempotency.execute(user.id(), "DELETE",
			"/api/roleplay/campaigns/{campaignId}/sheets/template", idempotencyKey, hash, () -> {
				sheets.deleteTemplate(campaignId, user.id().value());
				return new CommandResponse(200, new StatusView(campaignId, "DELETED"));
			});
	}

	/** A própria ficha, inteira — inclusive os campos que só o dono vê. */
	@GetMapping("/me")
	SheetView mine(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String campaignId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return sheetView(sheets.read(campaignId, user.id().value(), user.id().value()));
	}

	/** Preenche (ou reescreve) a própria ficha. Validação inteira no servidor. */
	@PutMapping("/me")
	ResponseEntity<String> fill(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String campaignId, @RequestBody SheetEntryRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("campaignId", campaignId, "action", "fill", "request", request));
		return idempotency.execute(user.id(), "PUT", "/api/roleplay/campaigns/{campaignId}/sheets/me",
			idempotencyKey, hash, () -> {
				SheetView view = sheetView(sheets.fill(campaignId, user.id().value(), request.name(),
					entriesOf(request)));
				return new CommandResponse(200, view);
			});
	}

	/** O quadro: o que o grupo enxerga de cada personagem, só com os campos abertos à campanha. */
	@GetMapping("/board")
	List<SheetView> board(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String campaignId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return readingsView(sheets.board(campaignId, user.id().value()));
	}

	/** As fichas que o mestre enxerga, cada uma no recorte dele. */
	@GetMapping
	List<SheetView> all(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String campaignId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return readingsView(sheets.listForMaster(campaignId, user.id().value()));
	}

	/** A ficha de outra pessoa, no recorte permitido: jogador recebe 403; mestre recebe só o aberto. */
	@GetMapping("/{userId}")
	SheetView one(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String campaignId, @PathVariable String userId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return sheetView(sheets.read(campaignId, userId, user.id().value()));
	}

	/** Uma lista de fichas: os derivados do sistema são calculados uma vez para todas. */
	private List<SheetView> readingsView(List<SheetService.SheetReading> readings) {
		List<DerivedAttribute> derived = readings.isEmpty() ? List.of()
			: sheets.derivedAttributes(readings.get(0).sheet().campaignId());
		return readings.stream().map(reading -> sheetView(reading, derived)).toList();
	}

	private SheetView sheetView(SheetService.SheetReading reading) {
		return sheetView(reading, sheets.derivedAttributes(reading.sheet().campaignId()));
	}

	private SheetView sheetView(SheetService.SheetReading reading, List<DerivedAttribute> derived) {
		var sheet = reading.sheet();
		Map<String, String> values = reading.derived(derived);
		Map<String, String> labels = new TreeMap<>();
		derived.forEach(attribute -> labels.put(attribute.key(), attribute.label()));
		List<DerivedView> derivedViews = values.entrySet().stream()
			.map(entry -> new DerivedView(entry.getKey(), labels.get(entry.getKey()), entry.getValue()))
			.toList();
		List<EntryView> entries = reading.entries().stream()
			.map(entry -> new EntryView(entry.key(), entry.value(), entry.visibility().name()))
			.toList();
		return new SheetView(sheet.id(), sheet.campaignId(), sheet.userId(), sheet.name(), entries, derivedViews,
			sheet.createdAt(), sheet.updatedAt());
	}

	private static TemplateView templateView(SheetTemplate template) {
		List<FieldView> fields = template.fields().stream()
			.map(field -> new FieldView(field.key(), field.label(), field.type().name(), field.required(),
				field.options(), field.maxVisibility().name()))
			.toList();
		return new TemplateView(template.id(), template.campaignId(), template.gameSystem(), fields,
			template.updatedAt());
	}

	private static Map<String, SheetVisibility> visibilitiesOf(TemplateRequest request) {
		if (request == null || request.fields() == null || request.fields().isEmpty()) {
			return Map.of();
		}
		Map<String, SheetVisibility> visibilities = new java.util.LinkedHashMap<>();
		for (FieldVisibility field : request.fields()) {
			if (field == null || field.key() == null || field.key().isBlank()) {
				throw new ApiException(400, "SHEET_FIELDS_REQUIRED", "Informe a chave do campo e a visibilidade.");
			}
			visibilities.put(field.key().trim(), visibilityOf(field.maxVisibility()));
		}
		return visibilities;
	}

	private static SheetVisibility visibilityOf(String name) {
		if (name == null || name.isBlank()) {
			throw new ApiException(400, "INVALID_SHEET_VISIBILITY", "Informe a visibilidade de cada campo.");
		}
		return SheetVisibility.of(name);
	}

	private static List<SheetEntry> entriesOf(SheetEntryRequest request) {
		if (request == null || request.entries() == null) {
			return List.of();
		}
		return request.entries().stream()
			.filter(entry -> entry != null && entry.key() != null && !entry.key().isBlank())
			// Visibilidade omitida fica nula de propósito: o campo nasce no recorte mais fechado, e
			// abrir é decisão explícita de quem preenche.
			.map(entry -> new SheetEntry(entry.key().trim(), entry.value(),
				entry.visibility() == null || entry.visibility().isBlank() ? null
					: SheetVisibility.of(entry.visibility())))
			.toList();
	}

	record TemplateRequest(List<FieldVisibility> fields) {
	}

	record FieldVisibility(String key, String maxVisibility) {
	}

	record SheetEntryRequest(String name, List<EntryInput> entries) {
	}

	record EntryInput(String key, String value, String visibility) {
	}

	record TemplateView(String id, String campaignId, String gameSystem, List<FieldView> fields,
			Instant updatedAt) {
	}

	record FieldView(String key, String label, String type, boolean required, List<String> options,
			String maxVisibility) {
	}

	record SheetView(String id, String campaignId, String userId, String name, List<EntryView> entries,
			List<DerivedView> derived, Instant createdAt, Instant updatedAt) {
	}

	record EntryView(String key, String value, String visibility) {
	}

	record DerivedView(String key, String label, String value) {
	}

	record StatusView(String id, String status) {
	}
}