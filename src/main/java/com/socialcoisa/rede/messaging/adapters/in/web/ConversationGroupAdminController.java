package com.socialcoisa.rede.messaging.adapters.in.web;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.idempotency.application.CommandResponse;
import com.socialcoisa.rede.idempotency.application.IdempotencyApplicationService;
import com.socialcoisa.rede.messaging.application.ConversationGroupService;
import com.socialcoisa.rede.messaging.application.GroupParticipant;
import com.socialcoisa.rede.profile.application.NameStyleView;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * Painel de admin do grupo de conversa (issue #915): os participantes com o papel de cada um,
 * convidar, remover, mudar papel e conceder o historico a quem entrou depois.
 *
 * <p>Fica num controller proprio porque a gestao de grupo tem guardas diferentes das da conversa
 * (so dono ou administrador mexe) e porque o controller da conversa ja carrega o contrato inteiro
 * dela — caminho de escrita novo entra aqui, e nao engorda o de la.</p>
 */
@RestController
@RequestMapping("/conversations/{conversationId}/group")
class ConversationGroupAdminController {

	private final CurrentUserResolver currentUserResolver;
	private final ConversationGroupService groupService;
	private final IdempotencyApplicationService idempotency;
	private final UserProfileRepository profiles;

	ConversationGroupAdminController(CurrentUserResolver currentUserResolver,
			ConversationGroupService groupService, IdempotencyApplicationService idempotency,
			UserProfileRepository profiles) {
		this.currentUserResolver = currentUserResolver;
		this.groupService = groupService;
		this.idempotency = idempotency;
		this.profiles = profiles;
	}

	/**
	 * Quem participa do grupo e o meu papel nele. A identidade de cada participante vem numa leitura
	 * em lote dos perfis, como no resto da caixa de entrada — o painel de um grupo de 100 pessoas
	 * nao pode custar 100 consultas.
	 */
	@GetMapping("/participants")
	GroupPanelView participants(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String conversationId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String userId = user.id().value();
		List<GroupParticipant> participants = groupService.listParticipants(conversationId, userId);
		List<String> ids = participants.stream().map(GroupParticipant::userId).toList();
		Map<String, UserProfile> profilesById = profiles.findAllByIds(ids).stream()
			.collect(Collectors.toMap(UserProfile::id, Function.identity()));
		String myRole = participants.stream()
			.filter(participant -> participant.userId().equals(userId))
			.map(participant -> participant.role().name())
			.findFirst()
			.orElseThrow(() -> new ApiException(403, "NOT_PARTICIPANT", "Voce nao participa desta conversa."));
		List<ParticipantView> views = participants.stream()
			.map(participant -> toView(participant, profilesById.get(participant.userId())))
			.toList();
		return new GroupPanelView(conversationId, myRole, views);
	}

	/** Convida alguem para o grupo (dono ou administrador). 201 com o participante criado. */
	@PostMapping("/participants")
	ResponseEntity<String> addParticipant(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String conversationId, @RequestBody AddParticipantRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("conversationId", conversationId);
		payload.put("userId", request.userId() == null ? "" : request.userId());
		String hash = idempotency.hash(payload);
		return idempotency.execute(user.id(), "POST",
			"/api/conversations/{conversationId}/group/participants", idempotencyKey, hash, () -> {
				GroupParticipant added = groupService.addParticipant(conversationId, user.id().value(),
					request.userId());
				UserProfile profile = profiles.findById(added.userId()).orElse(null);
				return new CommandResponse(201, toView(added, profile));
			});
	}

	/** Tira alguem do grupo (dono ou administrador). 200 com o estado da remocao. */
	@DeleteMapping("/participants/{userId}")
	ResponseEntity<String> removeParticipant(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String conversationId, @PathVariable String userId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("conversationId", conversationId);
		payload.put("userId", userId);
		String hash = idempotency.hash(payload);
		return idempotency.execute(user.id(), "DELETE",
			"/api/conversations/{conversationId}/group/participants/{userId}", idempotencyKey, hash, () -> {
				groupService.removeParticipant(conversationId, user.id().value(), userId);
				return new CommandResponse(200, Map.of("status", "REMOVED"));
			});
	}

	/** Promove a administrador ou volta a membro (so o dono). */
	@PatchMapping("/participants/{userId}")
	ResponseEntity<String> changeRole(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String conversationId, @PathVariable String userId,
			@RequestBody ChangeRoleRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("conversationId", conversationId);
		payload.put("userId", userId);
		payload.put("role", request.role() == null ? "" : request.role());
		String hash = idempotency.hash(payload);
		return idempotency.execute(user.id(), "PATCH",
			"/api/conversations/{conversationId}/group/participants/{userId}", idempotencyKey, hash, () -> {
				GroupParticipant changed = groupService.changeRole(conversationId, user.id().value(), userId,
					request.role());
				UserProfile profile = profiles.findById(changed.userId()).orElse(null);
				return new CommandResponse(200, toView(changed, profile));
			});
	}

	/**
	 * Concessao de historico: a chave de cada mensagem antiga, ja cifrada para a chave publica de
	 * quem entrou depois, para a pessoa nova ver o historico inteiro. Corpo:
	 * {@code {"keys": {"<messageId>": "<chave cifrada>"}}}. Responde quantas mensagens receberam a
	 * chave (as que ja tinham nao contam, entao repetir a concessao e seguro).
	 */
	@PostMapping("/participants/{userId}/history-keys")
	ResponseEntity<String> grantHistoryKeys(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String conversationId, @PathVariable String userId,
			@RequestBody HistoryKeysRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("conversationId", conversationId);
		payload.put("userId", userId);
		payload.put("keys", request.keys() == null ? Map.of() : request.keys());
		String hash = idempotency.hash(payload);
		return idempotency.execute(user.id(), "POST",
			"/api/conversations/{conversationId}/group/participants/{userId}/history-keys", idempotencyKey, hash,
			() -> {
				int granted = groupService.grantHistoryKeys(conversationId, user.id().value(), userId,
					request.keys());
				return new CommandResponse(200, Map.of("granted", granted));
			});
	}

	private static ParticipantView toView(GroupParticipant participant, UserProfile profile) {
		if (profile == null) {
			return new ParticipantView(participant.userId(), null, null, null, participant.role().name(),
				participant.joinedAt(), null);
		}
		return new ParticipantView(participant.userId(), profile.username(), profile.name(), profile.avatar(),
			participant.role().name(), participant.joinedAt(), NameStyleView.from(profile));
	}

	record GroupPanelView(String conversationId, String myRole, List<ParticipantView> participants) {
	}

	record ParticipantView(String userId, String username, String name, String avatar, String role,
			Instant joinedAt, NameStyleView nameStyle) {
	}

	record AddParticipantRequest(String userId) {
	}

	record ChangeRoleRequest(String role) {
	}

	record HistoryKeysRequest(Map<String, String> keys) {
	}

}
