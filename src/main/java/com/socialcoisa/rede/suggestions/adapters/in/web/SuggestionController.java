package com.socialcoisa.rede.suggestions.adapters.in.web;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;
import com.socialcoisa.rede.shared.web.ModeratorGuard;
import com.socialcoisa.rede.suggestions.application.SuggestionService;
import com.socialcoisa.rede.suggestions.domain.Suggestion;
import com.socialcoisa.rede.suggestions.domain.SuggestionStatus;
import com.socialcoisa.rede.suggestions.domain.SuggestionTarget;

@RestController
class SuggestionController {

	private final SuggestionService service;
	private final CurrentUserResolver currentUserResolver;
	private final ModeratorGuard moderatorGuard;

	SuggestionController(SuggestionService service, CurrentUserResolver currentUserResolver,
			ModeratorGuard moderatorGuard) {
		this.service = service;
		this.currentUserResolver = currentUserResolver;
		this.moderatorGuard = moderatorGuard;
	}

	/** Envia uma sugestão: fica pendente até um moderador aprovar. */
	@PostMapping("/suggestions")
	ResponseEntity<SuggestionView> submit(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestBody CreateSuggestion request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		Suggestion created = service.submit(user.id().value(), request.target(), request.title(), request.body());
		return ResponseEntity.status(HttpStatus.CREATED).body(SuggestionView.from(created));
	}

	/** Histórico de quem enviou: a issue pública só aparece aqui depois da aprovação. */
	@GetMapping("/suggestions/mine")
	List<SuggestionView> mine(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(defaultValue = "20") int limit) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return service.mine(user.id().value(), limit).stream().map(SuggestionView::from).toList();
	}

	@GetMapping("/admin/suggestions")
	List<SuggestionAdminView> list(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(required = false) SuggestionStatus status, @RequestParam(defaultValue = "50") int limit) {
		moderatorGuard.requireModerator(currentUserResolver.resolve(sessionToken));
		return service.list(status, limit).stream().map(SuggestionAdminView::from).toList();
	}

	/** Aprova e abre a issue pública no repositório do destino. */
	@PostMapping("/admin/suggestions/{id}/approve")
	SuggestionAdminView approve(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String id) {
		CurrentUser moderator = currentUserResolver.resolve(sessionToken);
		moderatorGuard.requireModerator(moderator);
		return SuggestionAdminView.from(service.approve(id, moderator.id().value()));
	}

	@PostMapping("/admin/suggestions/{id}/reject")
	SuggestionAdminView reject(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String id) {
		CurrentUser moderator = currentUserResolver.resolve(sessionToken);
		moderatorGuard.requireModerator(moderator);
		return SuggestionAdminView.from(service.reject(id, moderator.id().value()));
	}

	record CreateSuggestion(SuggestionTarget target, String title, String body) {
	}

	/** Visão de quem sugeriu: só o que ele mesmo escreveu e o andamento. */
	record SuggestionView(String id, String target, String title, String body, String status, String githubIssueUrl,
			String createdAt) {
		static SuggestionView from(Suggestion suggestion) {
			return new SuggestionView(suggestion.id(), suggestion.target().name(), suggestion.title(),
				suggestion.body(), suggestion.status().name(), suggestion.githubIssueUrl(),
				suggestion.createdAt().toString());
		}
	}

	/** Visão do painel: o id de quem sugeriu serve para ação de moderação, nunca vai para a issue. */
	record SuggestionAdminView(String id, String userId, String target, String title, String body, String status,
			String githubIssueNumber, String githubIssueUrl, String reviewedBy, String reviewedAt, String createdAt) {
		static SuggestionAdminView from(Suggestion suggestion) {
			return new SuggestionAdminView(suggestion.id(), suggestion.userId(), suggestion.target().name(),
				suggestion.title(), suggestion.body(), suggestion.status().name(),
				suggestion.githubIssueNumber() == null ? null : suggestion.githubIssueNumber().toString(),
				suggestion.githubIssueUrl(), suggestion.reviewedBy(),
				suggestion.reviewedAt() == null ? null : suggestion.reviewedAt().toString(),
				suggestion.createdAt().toString());
		}
	}
}
