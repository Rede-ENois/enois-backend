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
import com.socialcoisa.rede.moderation.application.AdminEntityService;
import com.socialcoisa.rede.shared.web.AdminGuard;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * Gerenciamento administrativo de comunidades e grupos: exclusão, lista completa de membros e
 * remoção de membro — sem votação, sem aprovação de dono e sem papel local.
 *
 * <p><b>Quem entra:</b> só {@code users.is_admin}. O {@code AdminRouteInterceptor} recusa antes do
 * MVC (o caminho fica fora da lista aberta a moderadores) e cada método confirma com o
 * {@code AdminGuard}: não existe cargo vindo do corpo do pedido que dê acesso. Dono, admin local e
 * moderador do grupo/da comunidade continuam onde estavam, pelos caminhos deles.</p>
 */
@RestController
class AdminEntityController {

	private final AdminEntityService entities;
	private final CurrentUserResolver currentUserResolver;
	private final AdminGuard adminGuard;
	private final IdempotencyApplicationService idempotency;
	private final ObjectMapper objectMapper;

	AdminEntityController(AdminEntityService entities, CurrentUserResolver currentUserResolver, AdminGuard adminGuard,
			IdempotencyApplicationService idempotency, ObjectMapper objectMapper) {
		this.entities = entities;
		this.currentUserResolver = currentUserResolver;
		this.adminGuard = adminGuard;
		this.idempotency = idempotency;
		this.objectMapper = objectMapper;
	}

	// ---------------------------------------------------------------- comunidade

	@GetMapping("/admin/moderation/communities/{communityId}")
	Object community(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String communityId) {
		requireAdmin(sessionToken);
		return entities.communityOverview(communityId);
	}

	@GetMapping("/admin/moderation/communities/{communityId}/members")
	Object communityMembers(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String communityId, @RequestParam(required = false) String q,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
		requireAdmin(sessionToken);
		return entities.communityMembers(communityId, q, page, size);
	}

	/** Exclusão da comunidade. POST com motivo: o corpo da requisição é o que fica na auditoria. */
	@PostMapping("/admin/moderation/communities/{communityId}/delete")
	Object deleteCommunity(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String communityId, @RequestBody(required = false) ReasonRequest request) {
		CurrentUser user = requireAdmin(sessionToken);
		String reason = request == null ? "" : request.reason();
		return write(user, "POST", "/api/admin/moderation/communities/{communityId}/delete", idempotencyKey,
			Map.of("communityId", communityId, "reason", reason), 200,
			() -> entities.deleteCommunity(user.id().value(), communityId, reason));
	}

	@PostMapping("/admin/moderation/communities/{communityId}/members/{userId}/remove")
	Object removeCommunityMember(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String communityId, @PathVariable String userId,
			@RequestBody(required = false) ReasonRequest request) {
		CurrentUser admin = requireAdmin(sessionToken);
		String reason = request == null ? "" : request.reason();
		return write(admin, "POST", "/api/admin/moderation/communities/{communityId}/members/{userId}/remove",
			idempotencyKey, Map.of("communityId", communityId, "userId", userId, "reason", reason), 200,
			() -> entities.removeCommunityMember(admin.id().value(), communityId, userId, reason));
	}

	// --------------------------------------------------------------------- grupo

	@GetMapping("/admin/moderation/groups/{groupId}")
	Object group(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String groupId) {
		requireAdmin(sessionToken);
		return entities.groupOverview(groupId);
	}

	@GetMapping("/admin/moderation/groups/{groupId}/members")
	Object groupMembers(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String groupId, @RequestParam(required = false) String q,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
		requireAdmin(sessionToken);
		return entities.groupMembers(groupId, q, page, size);
	}

	/** Exclusão física do grupo (conteúdo incluído). POST com motivo, pelo mesmo contrato acima. */
	@PostMapping("/admin/moderation/groups/{groupId}/delete")
	Object deleteGroup(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String groupId, @RequestBody(required = false) ReasonRequest request) {
		CurrentUser user = requireAdmin(sessionToken);
		String reason = request == null ? "" : request.reason();
		return write(user, "POST", "/api/admin/moderation/groups/{groupId}/delete", idempotencyKey,
			Map.of("groupId", groupId, "reason", reason), 200,
			() -> entities.deleteGroup(user.id().value(), groupId, reason));
	}

	/** Encerra (desliga) o grupo preservando o conteúdo — alternativa à exclusão física. */
	@PostMapping("/admin/moderation/groups/{groupId}/close")
	Object closeGroup(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String groupId, @RequestBody(required = false) ReasonRequest request) {
		CurrentUser user = requireAdmin(sessionToken);
		String reason = request == null ? "" : request.reason();
		return write(user, "POST", "/api/admin/moderation/groups/{groupId}/close", idempotencyKey,
			Map.of("groupId", groupId, "reason", reason), 200,
			() -> entities.closeGroup(user.id().value(), groupId, reason));
	}

	@PostMapping("/admin/moderation/groups/{groupId}/members/{userId}/remove")
	Object removeGroupMember(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String groupId, @PathVariable String userId,
			@RequestBody(required = false) ReasonRequest request) {
		CurrentUser admin = requireAdmin(sessionToken);
		String reason = request == null ? "" : request.reason();
		return write(admin, "POST", "/api/admin/moderation/groups/{groupId}/members/{userId}/remove", idempotencyKey,
			Map.of("groupId", groupId, "userId", userId, "reason", reason), 200,
			() -> entities.removeGroupMember(admin.id().value(), groupId, userId, reason));
	}

	// ------------------------------------------------------------------ interno

	/**
	 * Mesmo contrato de escrita do painel administrativo: com {@code Idempotency-Key} o pedido
	 * repetido devolve a resposta original sem repetir a decisão; sem chave, roda uma vez.
	 */
	private Object write(CurrentUser user, String method, String route, String idempotencyKey,
			Map<String, Object> request, int status, Supplier<Object> action) {
		if (!StringUtils.hasText(idempotencyKey)) {
			return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
				.body(objectMapper.writeValueAsString(action.get()));
		}
		ResponseEntity<String> replayed = idempotency.execute(user.id().value(), method, route, idempotencyKey,
			idempotency.hash(request), () -> new CommandResponse(status, action.get()));
		return ResponseEntity.status(replayed.getStatusCode()).contentType(MediaType.APPLICATION_JSON)
			.body(replayed.getBody());
	}

	private CurrentUser requireAdmin(String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		adminGuard.requireAdmin(user);
		return user;
	}

	record ReasonRequest(String reason) {
	}
}