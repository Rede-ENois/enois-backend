package com.socialcoisa.rede.feed.adapters.in.web;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.coin.application.CoinService;
import com.socialcoisa.rede.feed.application.PublishPostCommand;
import com.socialcoisa.rede.feed.application.port.in.PublishPostUseCase;
import com.socialcoisa.rede.idempotency.application.CommandResponse;
import com.socialcoisa.rede.idempotency.application.IdempotencyApplicationService;
import com.socialcoisa.rede.moderation.application.HoneypotGuard;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

@Component
public class PublicacaoPostHttp {

	private final PublishPostUseCase publishPostUseCase;
	private final IdempotencyApplicationService idempotency;
	private final CurrentUserResolver currentUserResolver;
	private final HoneypotGuard honeypots;
	private final CoinService coins;

	PublicacaoPostHttp(PublishPostUseCase publishPostUseCase, IdempotencyApplicationService idempotency,
			CurrentUserResolver currentUserResolver, HoneypotGuard honeypots, CoinService coins) {
		this.publishPostUseCase = publishPostUseCase;
		this.idempotency = idempotency;
		this.currentUserResolver = currentUserResolver;
		this.honeypots = honeypots;
		this.coins = coins;
	}

	public ResponseEntity<String> publicar(String sessionToken, String idempotencyKey, PublishPostRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		if (honeypots.triggered(user.id().value(), request.website(), "post")) {
			return ResponseEntity.status(201)
				.body("{\"id\":\"post_" + java.util.UUID.randomUUID().toString().replace("-", "") + "\"}");
		}
		// A standalone post a person writes must declare its topic: without one it misses the feed
		// filters and "O que quero ver". Checked here, at the person's entry point, because system
		// publishers (blog, games, legacy scheduled posts) go through the same use case without one.
		if (!StringUtils.hasText(request.communityId())
				&& !StringUtils.hasText(request.topic())) {
			throw new ApiException(400, "TOPIC_REQUIRED", "Escolha o assunto da publicação.");
		}
		// Imagem no meio do texto: todo marcador {{media:id}} precisa referenciar anexo do autor.
		com.socialcoisa.rede.shared.text.InlineMedia.validate(request.content(), request.mediaIds());
		String hash = idempotency.hash(request);
		return idempotency.execute(user.id(), "POST", "/api/feed/posts", idempotencyKey, hash, () -> {
			var published = publishPostUseCase.publish(new PublishPostCommand(user.id(), request.type(), request.content(),
				request.pageHtml(), request.articleBody(), request.tags(), request.mediaIds(), request.communityId(),
				request.mentionedUserIds(), request.topic(), request.channelId(), Boolean.TRUE.equals(request.mediaSpoiler()),
				request.pollOptions(), Boolean.TRUE.equals(request.pollMultipleChoice()), request.pollEndsAt()));
			// Presence counts here and not inside PublishPostService: the scheduler also publishes
			// through it, and a scheduled post going live is not the person being here today.
			coins.recordPresence(user.id().value());
			return new CommandResponse(201, published);
		});
	}
}
