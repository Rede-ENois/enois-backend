package com.socialcoisa.rede.moderation.adapters.in.web;

import java.util.Map;
import java.util.function.Supplier;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.ObjectMapper;

import com.socialcoisa.rede.idempotency.application.CommandResponse;
import com.socialcoisa.rede.idempotency.application.IdempotencyApplicationService;
import com.socialcoisa.rede.moderation.application.AdminAccountDeletionService;
import com.socialcoisa.rede.moderation.application.AdminModerationService;
import com.socialcoisa.rede.moderation.application.UserWarningService;
import com.socialcoisa.rede.moderation.domain.SanctionType;
import com.socialcoisa.rede.profile.application.ProfileService;
import com.socialcoisa.rede.shared.web.AdminGuard;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * Ações administrativas DIRETAS: advertência, banimento (e restrição/suspensão) de uma conta e
 * remoção de publicação, todas sem votação. É a superfície que o painel administrativo usa; o
 * container que a desenha e o design system estão no front (ver {@code docs/MODULES.md}).
 *
 * <p><b>Quem entra:</b> só conta com {@code users.is_admin}. A trava existe em dois lugares de
 * propósito — o {@code AdminRouteInterceptor} recusa antes do MVC (inclusive rota nova que alguém
 * esqueça de registrar) e cada método confirma com o {@code AdminGuard} antes de chamar o serviço.
 * Moderador aprovado não passa: as rotas não estão na lista de {@code /admin} aberta a moderadores,
 * e a confirmação explícita não consulta o papel de moderador.</p>
 *
 * <p><b>Quórum:</b> intacto e separado. A votação de denúncia ({@code /moderation/reports/*} e
 * {@code /moderation/sanction-proposals/*}) continua sendo o caminho de moderador e não chama nada
 * daqui; nada aqui conta voto.</p>
 */
@RestController
class AdminModerationController {

	private static final int HISTORY_LIMIT = 50;

	private final AdminModerationService admin;
	private final UserWarningService warnings;
	private final AdminAccountDeletionService accountDeletion;
	private final CurrentUserResolver currentUserResolver;
	private final AdminGuard adminGuard;
	private final IdempotencyApplicationService idempotency;
	private final ProfileService profileService;
	private final ObjectMapper objectMapper;

	AdminModerationController(AdminModerationService admin, UserWarningService warnings,
			AdminAccountDeletionService accountDeletion, CurrentUserResolver currentUserResolver,
			AdminGuard adminGuard, IdempotencyApplicationService idempotency,
			ProfileService profileService, ObjectMapper objectMapper) {
		this.admin = admin;
		this.warnings = warnings;
		this.accountDeletion = accountDeletion;
		this.currentUserResolver = currentUserResolver;
		this.adminGuard = adminGuard;
		this.idempotency = idempotency;
		this.profileService = profileService;
		this.objectMapper = objectMapper;
	}

	/**
	 * Perfil como o ADMINISTRADOR o vê: o mesmo perfil público, mais as advertências ativas da conta.
	 * É a leitura certa quando quem abre é administrador — incluir a contagem na resposta pública
	 * faria o perfil caro perder o cache e vazaria dado de moderação para qualquer visitante.
	 */
	@GetMapping("/admin/moderation/users/{username}")
	Object user(@RequestHeader(value = "X-Session-Token", required = false) String token,
			@PathVariable String username) {
		requireAdmin(token);
		// O serviço resolve o @ (404 quando não existe) e devolve o username canônico: a leitura
		// pública usa a mesma chave, inclusive quando quem chamou digitou @MAIÚSCULA.
		AdminModerationService.AdminUserView view = admin.user(username);
		return profileService.getByUsername(view.username()).withActiveWarnings((int) view.activeWarnings());
	}

	/** Ficha para decidir: sanção ativa, advertências ativas e histórico de sanções. */
	@GetMapping("/admin/moderation/users/{username}/summary")
	Object summary(
			@RequestHeader(value = "X-Session-Token", required = false) String token, @PathVariable String username) {
		requireAdmin(token);
		return admin.user(username);
	}

	/**
	 * Adverte a conta com motivo obrigatório. A chave de envio impede advertência duplicada em
	 * retentativa; sem chave a advertência é aceita e o registro ganha id próprio — o duplo clique
	 * que importa (com chave) continua protegido, e a recusa não vira "erro de cabeçalho" para
	 * quem chegou pela API.
	 */
	@PostMapping("/admin/moderation/users/{username}/warnings")
	Object warn(@RequestHeader(value = "X-Session-Token", required = false) String token,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String username, @RequestBody(required = false) ReasonRequest request) {
		CurrentUser user = requireAdmin(token);
		String reason = text(request);
		return write(user, "POST", "/api/admin/moderation/users/{username}/warnings", idempotencyKey,
			Map.of("username", username, "reason", reason == null ? "" : reason),
			201, () -> warnings.warn(user.id().value(), username, reason, idempotencyKey));
	}

	/** Histórico de advertências da conta: ativas e revogadas, mais recente primeiro. */
	@GetMapping("/admin/moderation/users/{username}/warnings")
	Object warningHistory(
			@RequestHeader(value = "X-Session-Token", required = false) String token,
			@PathVariable String username, @RequestParam(defaultValue = "20") int limit) {
		requireAdmin(token);
		return warnings.history(username, Math.min(Math.max(limit, 1), HISTORY_LIMIT));
	}

	/** Histórico administrativo da conta: advertências e sanções juntas, mais recente primeiro. */
	@GetMapping("/admin/moderation/users/{username}/history")
	AdminModerationService.AdminHistoryView history(
			@RequestHeader(value = "X-Session-Token", required = false) String token,
			@PathVariable String username, @RequestParam(defaultValue = "50") int limit) {
		requireAdmin(token);
		return admin.history(username, Math.min(Math.max(limit, 1), HISTORY_LIMIT));
	}

	/** Revoga a advertência sem apagar o registro (histórico preservado). */
	@PostMapping("/admin/moderation/warnings/{warningId}/revoke")
	Object revokeWarning(
			@RequestHeader(value = "X-Session-Token", required = false) String token,
			@PathVariable String warningId, @RequestBody(required = false) ReasonRequest request) {
		CurrentUser user = requireAdmin(token);
		return warnings.revoke(user.id().value(), warningId, text(request));
	}

	/**
	 * Sanção direta: {@code RESTRICTION}, {@code SUSPENDED}, {@code BAN} ou {@code PERMABAN}.
	 * O ban revoga as sessões, recusa novo login e põe o conteúdo do autor na purga — os mesmos
	 * efeitos da decisão por denúncia, sem esperar a votação.
	 */
	@PostMapping("/admin/moderation/users/{username}/sanction")
	Object sanction(@RequestHeader(value = "X-Session-Token", required = false) String token,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String username, @RequestBody(required = false) SanctionBody request) {
		CurrentUser user = requireAdmin(token);
		String type = request == null ? null : request.type();
		String reason = request == null ? null : request.reason();
		return write(user, "POST", "/api/admin/moderation/users/{username}/sanction", idempotencyKey,
			Map.of("username", username, "type", type == null ? "" : type, "reason", reason == null ? "" : reason), 200,
			() -> admin.sanction(user.id().value(), username, parseType(type), reason));
	}

	/**
	 * Exclusão administrativa da CONTA, sem a senha da pessoa (o caminho do dono continua sendo
	 * `POST /auth/account/delete`). Reusa a mesma anonimização da auto-exclusão; o motivo fica na
	 * auditoria. Conta admin e a própria conta são recusadas.
	 */
	@PostMapping("/admin/moderation/users/{username}/delete")
	Object deleteAccount(@RequestHeader(value = "X-Session-Token", required = false) String token,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String username, @RequestBody(required = false) ReasonRequest request) {
		CurrentUser user = requireAdmin(token);
		String reason = text(request);
		return write(user, "POST", "/api/admin/moderation/users/{username}/delete", idempotencyKey,
			Map.of("username", username, "reason", reason == null ? "" : reason), 200,
			() -> accountDeletion.deleteByAdmin(user.id().value(), username, reason));
	}

	/**
	 * Remoção administrativa de publicação com motivo obrigatório, independente de denúncia.
	 * A resposta diz o que aconteceu: {@code REMOVED}, {@code NOT_FOUND} ou {@code ALREADY_OFFLINE} —
	 * e id inexistente continua 404, nunca um sucesso que esconde que nada foi removido.
	 */
	@PostMapping("/admin/moderation/posts/{postId}/remove")
	Object removePost(@RequestHeader(value = "X-Session-Token", required = false) String token,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String postId, @RequestBody(required = false) ReasonRequest request) {
		CurrentUser user = requireAdmin(token);
		String reason = text(request);
		return write(user, "POST", "/api/admin/moderation/posts/{postId}/remove", idempotencyKey,
			Map.of("postId", postId, "reason", reason == null ? "" : reason), () -> {
				AdminModerationService.PostRemoval result = admin.removePost(user.id().value(), postId, reason);
				return new CommandResponse("NOT_FOUND".equals(result.status()) ? 404 : 200, result);
			});
	}

	/**
	 * Remoção administrativa de COMENTÁRIO com motivo obrigatório, independente de denúncia.
	 * A resposta diz o que aconteceu: {@code REMOVED}, {@code ALREADY_REMOVED} (já estava fora)
	 * ou {@code NOT_FOUND}.
	 */
	@PostMapping("/admin/moderation/comments/{commentId}/remove")
	Object removeComment(@RequestHeader(value = "X-Session-Token", required = false) String token,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String commentId, @RequestBody(required = false) ReasonRequest request) {
		CurrentUser user = requireAdmin(token);
		String reason = text(request);
		return write(user, "POST", "/api/admin/moderation/comments/{commentId}/remove", idempotencyKey,
			Map.of("commentId", commentId, "reason", reason == null ? "" : reason), () -> {
				AdminModerationService.CommentRemoval result =
					admin.removeComment(user.id().value(), commentId, reason);
				return new CommandResponse("NOT_FOUND".equals(result.status()) ? 404 : 200, result);
			});
	}

	/**
	 * Executa a escrita como o resto da API faz. Com {@code Idempotency-Key}, o mesmo pedido devolve a
	 * mesma resposta (replay) e a decisão acontece uma única vez; sem chave, a decisão acontece uma vez
	 * e o registro ganha id próprio. Nos dois casos o corpo é o JSON da decisão, sem envelope.
	 */
	private Object write(CurrentUser user, String method, String route, String idempotencyKey,
			Map<String, Object> request, int status, Supplier<Object> action) {
		return write(user, method, route, idempotencyKey, request,
			() -> new CommandResponse(status, action.get()));
	}

	private Object write(CurrentUser user, String method, String route, String idempotencyKey,
			Map<String, Object> request, Supplier<CommandResponse> action) {
		if (!StringUtils.hasText(idempotencyKey)) {
			// Sem chave não há replay: a decisão roda uma vez e a resposta é a dela (mesmo corpo do
			// replay, porque a chave do filtro de duplicidade continua valendo para o clique duplo).
			CommandResponse response = action.get();
			return ResponseEntity.status(response.status()).contentType(MediaType.APPLICATION_JSON)
				.body(objectMapper.writeValueAsString(response.body()));
		}
		ResponseEntity<String> replayed = idempotency.execute(user.id().value(), method, route, idempotencyKey,
			idempotency.hash(request), action);
		// O replay devolve JSON já escrito pela primeira execução: devolve cru para o corpo ser
		// idêntico ao original (e não uma string escapada dentro de outro JSON).
		return ResponseEntity.status(replayed.getStatusCode())
			.contentType(MediaType.APPLICATION_JSON)
			.body(replayed.getBody());
	}

	/** Sessão válida que seja administrador; qualquer outro papel recebe 403 sem detalhe. */
	private CurrentUser requireAdmin(String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		adminGuard.requireAdmin(user);
		return user;
	}

	private static SanctionType parseType(String type) {
		try {
			return SanctionType.valueOf(type == null ? "" : type.trim().toUpperCase(java.util.Locale.ROOT));
		}
		catch (IllegalArgumentException failure) {
			throw new ApiException(400, "INVALID_SANCTION_TYPE", "Escolha restrição, suspensão, banimento ou banimento permanente.");
		}
	}

	private static String text(ReasonRequest request) {
		return request == null ? null : request.reason();
	}

	record ReasonRequest(String reason) {
	}

	record SanctionBody(String type, String reason) {
	}
}