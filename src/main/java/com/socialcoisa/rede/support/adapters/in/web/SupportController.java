package com.socialcoisa.rede.support.adapters.in.web;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.moderation.application.ModeratorService;
import com.socialcoisa.rede.shared.web.AdminGuard;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;
import com.socialcoisa.rede.shared.web.PiiMask;
import com.socialcoisa.rede.support.application.SupportGithubConfig;
import com.socialcoisa.rede.support.application.SupportService;
import com.socialcoisa.rede.support.domain.SupportCategory;
import com.socialcoisa.rede.support.domain.SupportRequest;
import com.socialcoisa.rede.support.domain.SupportStatus;

@RestController
class SupportController {

	private final SupportService supportService;
	private final SupportGithubConfig githubConfig;
	private final CurrentUserResolver currentUserResolver;
	private final AdminGuard adminGuard;
	private final ModeratorService moderators;
	private final String supportEmail;

	SupportController(SupportService supportService, SupportGithubConfig githubConfig,
			CurrentUserResolver currentUserResolver, AdminGuard adminGuard, ModeratorService moderators,
			@org.springframework.beans.factory.annotation.Value("${rede.support.email:}") String supportEmail) {
		this.supportService = supportService;
		this.githubConfig = githubConfig;
		this.currentUserResolver = currentUserResolver;
		this.adminGuard = adminGuard;
		this.moderators = moderators;
		this.supportEmail = supportEmail;
	}

	/**
	 * Canal de contato publico. Serve para a pagina /contato oferecer o e-mail do time a quem ainda
	 * nao tem conta (o formulario exige sessao). Devolve null quando o time nao configurou um e-mail.
	 */
	@GetMapping("/support/config")
	SupportConfigView config() {
		return new SupportConfigView(StringUtils.hasText(supportEmail) ? supportEmail.trim() : null);
	}

	/** Abre um pedido de ajuda para ser atendido por uma pessoa do time. */
	@PostMapping("/support/requests")
	ResponseEntity<SupportRequestView> open(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestBody CreateSupportRequest request) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		SupportRequest created = supportService.open(user.id().value(), request.subject(), request.message(),
			request.contactEmail(), request.imageUrl(), request.category());
		return ResponseEntity.status(HttpStatus.CREATED).body(SupportRequestView.from(created));
	}

	/** Histórico do próprio solicitante: o encerramento (painel ou GitHub) aparece aqui. */
	@GetMapping("/support/requests/mine")
	List<SupportRequestView> mine(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(defaultValue = "20") int limit) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		return supportService.mine(user.id().value(), limit).stream()
			.map(SupportRequestView::from)
			.toList();
	}

	/** Fila do painel central: filtros por categoria e status (apenas administradores). */
	@GetMapping("/admin/support")
	List<SupportAdminView> list(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(required = false) SupportCategory category,
			@RequestParam(required = false) SupportStatus status,
			@RequestParam(defaultValue = "50") int limit) {
		requireModerator(currentUserResolver.resolve(userId, sessionToken));
		return supportService.list(category, status, limit).stream()
			.map(SupportAdminView::from)
			.toList();
	}

	/** Triagem: muda status e/ou categoria de um ticket (apenas administradores). */
	@PatchMapping("/admin/support/{id}")
	SupportAdminView update(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String id, @RequestBody UpdateSupportRequest request) {
		requireModerator(currentUserResolver.resolve(userId, sessionToken));
		return SupportAdminView.from(supportService.update(id, request.status(), request.category()));
	}

	/** Resposta oficial ao solicitante: grava, envia por e-mail e notifica no app. */
	@PostMapping("/admin/support/{id}/reply")
	SupportAdminView reply(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String id, @RequestBody ReplySupportRequest request) {
		CurrentUser admin = currentUserResolver.resolve(userId, sessionToken);
		requireModerator(admin);
		return SupportAdminView.from(supportService.reply(id, admin.id().value(), request.message()));
	}

	/**
	 * Estado do espelho no GitHub para o painel. Nunca devolve token nem segredo — só se existem.
	 */
	@GetMapping("/admin/support/github")
	SupportGithubSettingsView githubSettings(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		requireModerator(currentUserResolver.resolve(userId, sessionToken));
		return githubView();
	}

	/**
	 * Troca a configuração do espelho (token, repositório, segredo do webhook) sem reiniciar o pod.
	 * Campo nulo mantém o valor atual; string vazia remove.
	 */
	@PutMapping("/admin/support/github")
	SupportGithubSettingsView updateGithubSettings(
			@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestBody UpdateGithubSettings request) {
		requireModerator(currentUserResolver.resolve(userId, sessionToken));
		githubConfig.update(request.token(), request.repo(), request.webhookSecret());
		return githubView();
	}

	private SupportGithubSettingsView githubView() {
		return new SupportGithubSettingsView(githubConfig.enabled(), githubConfig.repository(),
			githubConfig.tokenConfigured(), githubConfig.webhookSecretConfigured());
	}

	private void requireModerator(CurrentUser user) {
		if (!adminGuard.isAdmin(user) && !moderators.isModerator(user.id().value())) {
			throw new ApiException(403, "FORBIDDEN", "Acesso restrito a moderadores.");
		}
	}

	record SupportConfigView(String email) {
	}

	record UpdateGithubSettings(String token, String repo, String webhookSecret) {
	}

	record SupportGithubSettingsView(boolean enabled, String repo, boolean tokenConfigured,
			boolean webhookSecretConfigured) {
	}

	record CreateSupportRequest(String subject, String message, String contactEmail, String imageUrl,
			SupportCategory category) {
	}

	record UpdateSupportRequest(SupportStatus status, SupportCategory category) {
	}

	record ReplySupportRequest(String message) {
	}

	/** Visão do solicitante: sem o espelho interno do GitHub (repo privado do time). */
	record SupportRequestView(String id, String userId, String subject, String message, String contactEmail,
			String imageUrl, String category, String status, String adminReply, String repliedBy,
			String repliedAt, String createdAt) {
		static SupportRequestView from(SupportRequest request) {
			return new SupportRequestView(request.id(), request.userId(), request.subject(), request.message(),
				PiiMask.email(request.contactEmail()), request.imageUrl(), request.category().name(),
				request.status().name(),
				request.adminReply(), request.repliedBy(),
				request.repliedAt() == null ? null : request.repliedAt().toString(),
				request.createdAt().toString());
		}
	}

	/** Visão do painel: inclui a issue espelhada para o time abrir o item no GitHub. */
	record SupportAdminView(String id, String userId, String subject, String message, String contactEmail,
			String imageUrl, String category, String status, String adminReply, String repliedBy,
			String repliedAt, String createdAt, Integer githubIssueNumber, String githubIssueUrl) {
		static SupportAdminView from(SupportRequest request) {
			return new SupportAdminView(request.id(), request.userId(), request.subject(), request.message(),
				PiiMask.email(request.contactEmail()), request.imageUrl(), request.category().name(),
				request.status().name(), request.adminReply(), request.repliedBy(),
				request.repliedAt() == null ? null : request.repliedAt().toString(),
				request.createdAt().toString(), request.githubIssueNumber(), request.githubIssueUrl());
		}
	}
}
