package com.socialcoisa.rede.feed.adapters.in.web;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.feed.application.DraftPostService;
import com.socialcoisa.rede.feed.domain.DraftPost;
import com.socialcoisa.rede.feed.domain.PostType;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * O rascunho do compositor: ver, salvar (substitui o que havia) e descartar. Tudo exige sessão
 * e o rascunho é só do dono — um rascunho nunca aparece para outra pessoa. PUBLICAR e AGENDAR
 * continuam pelos endpoints de sempre; quem chama é o composer, que apaga o rascunho quando uma
 * das duas dá certo.
 */
@RestController
class DraftPostController {

	private final DraftPostService service;
	private final CurrentUserResolver currentUserResolver;

	DraftPostController(DraftPostService service, CurrentUserResolver currentUserResolver) {
		this.service = service;
		this.currentUserResolver = currentUserResolver;
	}

	@GetMapping("/feed/draft")
	DraftResponse draft(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return new DraftResponse(service.find(user.id().value()).map(DraftPostController::view).orElse(null));
	}

	@PutMapping("/feed/draft")
	DraftResponse save(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestBody DraftRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		DraftPost salvo = service.save(user.id().value(), new DraftPostService.DraftCommand(
			tipo(request.type()), request.content(), request.pageHtml(), request.articleBody(), request.topic(),
			request.pollOptions(), Boolean.TRUE.equals(request.pollMultipleChoice()),
			instante(request.pollEndsAt()), request.mentionedUserIds()));
		return new DraftResponse(view(salvo));
	}

	@DeleteMapping("/feed/draft")
	Map<String, String> discard(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		service.delete(user.id().value());
		return Map.of("status", "DISCARDED");
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
		if (!StringUtils.hasText(valor)) {
			return null;
		}
		try {
			return Instant.parse(valor.trim());
		}
		catch (DateTimeParseException error) {
			throw new ApiException(400, "DATA_INVALIDA", "Data do prazo da enquete inválida (use ISO 8601).");
		}
	}

	private static View view(DraftPost draft) {
		return new View(draft.id(), draft.type().name(), draft.content(), draft.pageHtml(), draft.articleBody(),
			draft.topic(), draft.pollOptions(), draft.pollMultipleChoice(),
			draft.pollEndsAt() == null ? null : draft.pollEndsAt().toString(), draft.mentionedUserIds(),
			draft.createdAt().toString(), draft.updatedAt().toString());
	}

	record DraftRequest(String type, String content, String pageHtml, String articleBody, String topic,
			List<String> pollOptions, Boolean pollMultipleChoice, String pollEndsAt,
			List<String> mentionedUserIds) {
	}

	record View(String id, String type, String content, String pageHtml, String articleBody, String topic,
			List<String> pollOptions, boolean pollMultipleChoice, String pollEndsAt,
			List<String> mentionedUserIds, String createdAt, String updatedAt) {
	}

	/** `draft` vem nulo quando não há rascunho — ausência não é erro. */
	record DraftResponse(View draft) {
	}
}
