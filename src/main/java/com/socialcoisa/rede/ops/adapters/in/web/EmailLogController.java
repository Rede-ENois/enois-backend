package com.socialcoisa.rede.ops.adapters.in.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.ops.application.port.out.EmailLogRepository;
import com.socialcoisa.rede.ops.domain.EmailLog;
import com.socialcoisa.rede.moderation.application.ModeratorService;
import com.socialcoisa.rede.shared.web.AdminGuard;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.PiiMask;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/** Log de metadados dos e-mails enviados (painel central, apenas administradores). */
@RestController
class EmailLogController {

	private final EmailLogRepository logs;
	private final CurrentUserResolver currentUserResolver;
	private final AdminGuard adminGuard;
	private final ModeratorService moderators;

	EmailLogController(EmailLogRepository logs, CurrentUserResolver currentUserResolver, AdminGuard adminGuard,
			ModeratorService moderators) {
		this.logs = logs;
		this.currentUserResolver = currentUserResolver;
		this.adminGuard = adminGuard;
		this.moderators = moderators;
	}

	@GetMapping("/admin/emails")
	List<EmailLogView> recent(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(defaultValue = "50") int limit) {
		requireModerator(currentUserResolver.resolve(userId, sessionToken));
		// 2FA fora do painel: nem destinatário, nem assunto, nem existência do envio. O log existe
		// para acompanhar entrega de avisos; código de segundo fator não é metadado de operação.
		return logs.findRecent(limit).stream()
			.filter(log -> !"TWO_FACTOR".equals(log.kind()))
			.map(EmailLogView::from)
			.toList();
	}

	private void requireModerator(CurrentUser user) {
		if (!adminGuard.isAdmin(user) && !moderators.isModerator(user.id().value())) {
			throw new ApiException(403, "FORBIDDEN", "Acesso restrito a moderadores.");
		}
	}

	record EmailLogView(String id, String toEmail, String subject, String kind, String status, String error,
			String createdAt) {
		static EmailLogView from(EmailLog log) {
			return new EmailLogView(log.id(), PiiMask.email(log.toEmail()), PiiMask.text(log.subject()),
				log.kind(), log.status().name(),
				PiiMask.text(log.error()), log.createdAt().toString());
		}
	}
}
