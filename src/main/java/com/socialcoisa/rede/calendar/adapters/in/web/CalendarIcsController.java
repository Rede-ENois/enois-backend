package com.socialcoisa.rede.calendar.adapters.in.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.socialcoisa.rede.calendar.application.CalendarIcsService;
import com.socialcoisa.rede.calendar.domain.CalendarScope;
import com.socialcoisa.rede.idempotency.application.CommandResponse;
import com.socialcoisa.rede.idempotency.application.IdempotencyApplicationService;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * iCalendar da agenda de quem está lendo (fase 8, D6/D3).
 *
 * <p>O export responde `text/calendar` com um nome de arquivo próprio para o navegador baixar sem
 * abrir o texto na tela. O import recebe o arquivo como multipart, no mesmo formato do upload de
 * currículo: o import é uma escrita e passa pelo `Idempotency-Key` (D11), então apertar duas vezes
 * no botão não duplica a agenda.
 */
@RestController
@RequestMapping("/calendar/entries")
class CalendarIcsController {

	private final CurrentUserResolver currentUserResolver;
	private final CalendarIcsService ics;
	private final IdempotencyApplicationService idempotency;

	CalendarIcsController(CurrentUserResolver currentUserResolver, CalendarIcsService ics,
			IdempotencyApplicationService idempotency) {
		this.currentUserResolver = currentUserResolver;
		this.ics = ics;
		this.idempotency = idempotency;
	}

	/** O `.ics` da janela pedida, com o mesmo recorte (`scope`) que a tela está mostrando. */
	@GetMapping(value = "/export.ics", produces = "text/calendar;charset=UTF-8")
	ResponseEntity<byte[]> export(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(required = false) String from, @RequestParam(required = false) String to,
			@RequestParam(required = false) String scope) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String body = ics.export(user.id().value(), CalendarEntryController.instant(from, "from"),
			CalendarEntryController.instant(to, "to"), scope(scope));
		return ResponseEntity.ok()
			.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"agenda-enois.ics\"")
			.contentType(MediaType.parseMediaType("text/calendar;charset=UTF-8"))
			.body(body.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Importa o `.ics` enviado como arquivo. Multipart é o formato que o app já usa para subir
	 * arquivo (`/me/import/resume`), e o `Idempotency-Key` continua obrigatório porque importar é
	 * escrita (D11): apertar duas vezes no botão não duplica a agenda.
	 *
	 * <p>O `scope` da query escolhe a agenda de destino; sem ele, SOCIAL, o mesmo padrão da criação.
	 */
	@PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<String> importCalendar(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestParam(required = false) String scope,
			@RequestParam(value = "file", required = false) MultipartFile file) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		if (file == null || file.isEmpty()) {
			throw new ApiException(400, "INVALID_ICS", "Envie o arquivo iCalendar no campo file.");
		}
		// O hash da chave é do arquivo, não do resultado: repetir o mesmo upload devolve a resposta
		// guardada. Ele é lido em fluxo (até o teto do serviço) e o import lê o arquivo de novo, do
		// disco, em vez de guardar o corpo inteiro num array.
		String hash = idempotency.hash(file.getResource());
		return idempotency.execute(user.id().value(), "POST", "/api/calendar/entries/import", idempotencyKey,
			hash, () -> new CommandResponse(200,
				ics.importCalendar(user.id().value(), open(file), scope(scope))));
	}

	private static InputStream open(MultipartFile file) {
		try {
			return file.getInputStream();
		}
		catch (IOException error) {
			throw new ApiException(400, "INVALID_ICS", "Não foi possível ler o arquivo enviado.");
		}
	}

	private static CalendarScope scope(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return CalendarScope.parse(raw.trim());
		}
		catch (IllegalArgumentException exception) {
			throw new ApiException(400, "INVALID_SCOPE", "Agenda invalida: use SOCIAL ou PROFESSIONAL.");
		}
	}
}