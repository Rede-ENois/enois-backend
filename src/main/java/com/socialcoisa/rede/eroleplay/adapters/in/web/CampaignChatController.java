package com.socialcoisa.rede.eroleplay.adapters.in.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.eroleplay.application.CampaignChatService;
import com.socialcoisa.rede.eroleplay.domain.CampaignMember;
import com.socialcoisa.rede.eroleplay.domain.CampaignMessage;
import com.socialcoisa.rede.eroleplay.domain.ChatChannel;
import com.socialcoisa.rede.idempotency.application.CommandResponse;
import com.socialcoisa.rede.idempotency.application.IdempotencyApplicationService;
import com.socialcoisa.rede.messaging.adapters.in.web.MessageStreamHub;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * Endpoints do chat de campanha: canais IC/OOC, apartes privados, fixação e presença.
 *
 * <p>Nenhuma rota aqui é pública: o chat é a cena da mesa e quem não participa não lê nada. Toda
 * escrita responde sob {@code Idempotency-Key} — clicar duas vezes no enviar não publica duas
 * vezes. O aviso de "chat mudou" sai pelo stream de mensagens (evento {@code campaign}); o corpo
 * da mensagem nunca viaja no stream, o cliente recarrega por aqui, onde o recorte é aplicado.</p>
 */
@RestController
@RequestMapping("/roleplay/campaigns/{campaignId}/chat")
class CampaignChatController {

	private final CampaignChatService chat;
	private final CurrentUserResolver currentUserResolver;
	private final IdempotencyApplicationService idempotency;
	private final MessageStreamHub streamHub;

	CampaignChatController(CampaignChatService chat, CurrentUserResolver currentUserResolver,
			IdempotencyApplicationService idempotency, MessageStreamHub streamHub) {
		this.chat = chat;
		this.currentUserResolver = currentUserResolver;
		this.idempotency = idempotency;
		this.streamHub = streamHub;
	}

	/** Publica no canal. {@code recipientId} só existe no aparte privado. */
	@PostMapping
	ResponseEntity<String> post(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String campaignId, @RequestBody PostInput request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		ChatChannel channel = requireChannel(request.channel());
		String hash = idempotency.hash(Map.of("campaignId", campaignId, "action", "chat-post",
			"channel", channel.wire(), "recipientId", nullSafe(request.recipientId()),
			"content", nullSafe(request.content())));
		return idempotency.execute(user.id(), "POST", "/api/roleplay/campaigns/{campaignId}/chat",
			idempotencyKey, hash, () -> new CommandResponse(201,
				view(chat.post(campaignId, channel, user.id().value(), request.recipientId(), request.content()))));
	}

	/** O histórico do canal, já no recorte de quem pede. */
	@GetMapping
	List<MessageView> history(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String campaignId,
			@RequestParam(name = "channel", defaultValue = "IC") String channel) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return chat.history(campaignId, requireChannel(channel), user.id().value()).stream()
			.map(CampaignChatController::view)
			.toList();
	}

	/** As mensagens fixadas da campanha, no recorte de quem pede. */
	@GetMapping("/pinned")
	List<MessageView> pinned(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String campaignId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return chat.pinned(campaignId, user.id().value()).stream()
			.map(CampaignChatController::view)
			.toList();
	}

	/** Uma mensagem, no recorte de quem pede (404 quando não é dele). */
	@GetMapping("/{messageId}")
	MessageView one(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String campaignId, @PathVariable String messageId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return view(chat.read(campaignId, messageId, user.id().value()));
	}

	/** Fixa: só o mestre. */
	@PostMapping("/{messageId}/pin")
	ResponseEntity<String> pin(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String campaignId, @PathVariable String messageId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("campaignId", campaignId, "action", "chat-pin", "messageId",
			messageId));
		return idempotency.execute(user.id(), "POST", "/api/roleplay/campaigns/{campaignId}/chat/{messageId}/pin",
			idempotencyKey, hash, () -> new CommandResponse(200,
				view(chat.pin(campaignId, messageId, user.id().value()))));
	}

	/** Desfixa: só o mestre. */
	@PostMapping("/{messageId}/unpin")
	ResponseEntity<String> unpin(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String campaignId, @PathVariable String messageId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("campaignId", campaignId, "action", "chat-unpin", "messageId",
			messageId));
		return idempotency.execute(user.id(), "POST", "/api/roleplay/campaigns/{campaignId}/chat/{messageId}/unpin",
			idempotencyKey, hash, () -> new CommandResponse(200,
				view(chat.unpin(campaignId, messageId, user.id().value()))));
	}

	/**
	 * Quem da mesa está com stream aberto agora. A fronteira da campanha é conferida ANTES do
	 * cruzamento com o hub — nunca se responde presença sem passar pela autorização.
	 */
	@GetMapping("/presence")
	List<PresenceView> presence(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String campaignId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		Predicate<String> hasOpenStream = streamHub::hasOpenStream;
		return chat.presentMembers(campaignId, user.id().value(), hasOpenStream).stream()
			.map(CampaignChatController::presenceView)
			.toList();
	}

	private ChatChannel requireChannel(String value) {
		if (value == null || value.isBlank()) {
			throw new ApiException(400, "CHAT_CHANNEL_REQUIRED", "Escolha um canal (IC ou OOC).");
		}
		try {
			return ChatChannel.of(value);
		}
		catch (IllegalArgumentException rejected) {
			throw new ApiException(400, "CHAT_CHANNEL_INVALID", "O canal é IC ou OOC.");
		}
	}

	private static String nullSafe(String value) {
		return value == null ? "" : value;
	}

	static MessageView view(CampaignMessage message) {
		return new MessageView(message.id(), message.campaignId(), message.channel().wire(), message.authorId(),
			message.recipientId(), message.content(), message.isPinned(), message.pinnedBy(), message.pinnedAt(),
			message.createdAt());
	}

	private static PresenceView presenceView(CampaignMember member) {
		return new PresenceView(member.userId(), member.role().name(), member.joinedAt());
	}

	record PostInput(String channel, String recipientId, String content) {
	}

	record MessageView(String id, String campaignId, String channel, String authorId, String recipientId,
			String content, boolean pinned, String pinnedBy, Instant pinnedAt, Instant createdAt) {
	}

	record PresenceView(String userId, String role, Instant joinedAt) {
	}
}
