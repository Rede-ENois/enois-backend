package com.socialcoisa.rede.feed.adapters.in.web;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.feed.application.ScheduledPostService;
import com.socialcoisa.rede.feed.domain.PostType;
import com.socialcoisa.rede.feed.domain.ScheduledPost;
import com.socialcoisa.rede.idempotency.application.CommandResponse;
import com.socialcoisa.rede.idempotency.application.IdempotencyApplicationService;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * Agendamento de publicação: criar, listar os meus e cancelar. A PUBLICAÇÃO em si é do worker —
 * aqui só se marca o horário. Tudo exige sessão; um agendado nunca aparece para outra pessoa.
 */
@RestController
class ScheduledPostController {

	private final ScheduledPostService service;
	private final CurrentUserResolver currentUserResolver;
	private final IdempotencyApplicationService idempotency;

	ScheduledPostController(ScheduledPostService service, CurrentUserResolver currentUserResolver,
			IdempotencyApplicationService idempotency) {
		this.service = service;
		this.currentUserResolver = currentUserResolver;
		this.idempotency = idempotency;
	}

	@PostMapping("/feed/scheduled")
	ResponseEntity<String> schedule(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody ScheduleRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(request);
		return idempotency.execute(user.id(), "POST", "/api/feed/scheduled", idempotencyKey, hash,
			() -> new CommandResponse(201, view(service.schedule(user.id().value(),
				new ScheduledPostService.ScheduleCommand(tipo(request.type()), request.content(),
					request.pageHtml(), request.articleBody(), request.tags(), request.mediaIds(),
					request.mentionedUserIds(), request.communityId(), request.topic(), request.pollOptions(),
					instante(request.scheduledAt()))))));
	}

	@GetMapping("/feed/scheduled")
	List<View> list(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return service.list(user.id().value()).stream().map(ScheduledPostController::view).toList();
	}

	@DeleteMapping("/feed/scheduled/{id}")
	Map<String, String> cancel(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String id) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		service.cancel(user.id().value(), id);
		return Map.of("id", id, "status", "CANCELLED");
	}

	private PostType tipo(String tipo) {
		if (tipo == null || tipo.isBlank()) {
			return null;
		}
		try {
			return PostType.valueOf(tipo.trim().toUpperCase());
		}
		catch (IllegalArgumentException error) {
			throw new ApiException(400, "TIPO_INVALIDO", "Tipo de publicação desconhecido.");
		}
	}

	private Instant instante(String valor) {
		if (valor == null || valor.isBlank()) {
			return null;
		}
		try {
			return Instant.parse(valor.trim());
		}
		catch (DateTimeParseException error) {
			throw new ApiException(400, "DATA_INVALIDA", "Data do agendamento inválida (use ISO 8601).");
		}
	}

	private static View view(ScheduledPost post) {
		return new View(post.id(), post.type().name(), post.content(), post.pageHtml(), post.communityId(),
			post.topic(), post.scheduledAt().toString(), post.createdAt().toString(), post.lastError(),
			post.attempts());
	}

	record ScheduleRequest(String type, String content, String pageHtml, String articleBody, List<String> tags,
			List<String> mediaIds, List<String> mentionedUserIds, String communityId, String topic,
			List<String> pollOptions, String scheduledAt) {
	}

	record View(String id, String type, String content, String pageHtml, String communityId, String topic,
			String scheduledAt, String createdAt, String lastError, int attempts) {
	}
}
