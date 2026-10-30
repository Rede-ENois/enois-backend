package com.socialcoisa.rede.notices.adapters.in.web;

import java.util.Locale;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.notices.application.NoticeService;
import com.socialcoisa.rede.notices.domain.AdminNotice;
import com.socialcoisa.rede.notices.domain.NoticeSurface;
import com.socialcoisa.rede.shared.web.AdminGuard;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * O aviso da plataforma que os administradores publicam do painel.
 *
 * GET /avisos/ativo é público e responde vazio (204) quando não há aviso — a tela não precisa de
 * sessão para mostrar o recado, e um "sem aviso" não é erro. PUT e DELETE são de /admin/**: o
 * interceptor já fecha a rota, e o requireAdmin abaixo é a defesa em profundidade do costume.
 */
@RestController
class NoticeController {

	private final NoticeService notices;
	private final CurrentUserResolver currentUserResolver;
	private final AdminGuard adminGuard;

	NoticeController(NoticeService notices, CurrentUserResolver currentUserResolver, AdminGuard adminGuard) {
		this.notices = notices;
		this.currentUserResolver = currentUserResolver;
		this.adminGuard = adminGuard;
	}

	record PublishRequest(String title, String bodyHtml, String surface) {
	}

	record NoticeView(String id, String title, String bodyHtml, String surface, String publishedAt,
			String updatedAt) {

		static NoticeView of(AdminNotice notice) {
			return new NoticeView(notice.id(), notice.title(), notice.bodyHtml(), notice.surface().name(),
				notice.publishedAt().toString(), notice.updatedAt().toString());
		}
	}

	@GetMapping("/avisos/ativo")
	ResponseEntity<NoticeView> active() {
		Optional<NoticeView> notice = notices.current().map(NoticeView::of);
		return notice.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
	}

	@PutMapping("/admin/avisos")
	NoticeView publish(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestBody(required = false) PublishRequest request) {
		adminGuard.requireAdmin(currentUserResolver.resolve(sessionToken));
		return NoticeView.of(notices.publish(request == null ? null : request.title(),
			request == null ? null : request.bodyHtml(), surfaceOf(request == null ? null : request.surface())));
	}

	@DeleteMapping("/admin/avisos")
	ResponseEntity<Void> retire(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		adminGuard.requireAdmin(currentUserResolver.resolve(sessionToken));
		notices.retire();
		return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
	}

	/**
	 * Superfície obrigatória: sem ela um client antigo publicaria um aviso e o painel decidiria
	 * sozinho se ele vira faixa ou popup — e um aviso escrito para o banner apareceria na cara de
	 * todo mundo em modal.
	 */
	private NoticeSurface surfaceOf(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return NoticeSurface.valueOf(raw.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException ex) {
			throw new ApiException(400, "INVALID_NOTICE_SURFACE",
				"Superfície do aviso desconhecida: use banner, modal ou both.");
		}
	}
}
