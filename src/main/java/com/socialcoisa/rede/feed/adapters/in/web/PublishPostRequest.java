package com.socialcoisa.rede.feed.adapters.in.web;

import java.time.Instant;
import java.util.List;

import com.socialcoisa.rede.feed.domain.PostType;

/**
 * Assunto (`topic`) só vale para post AVULSO: o de comunidade herda o da comunidade, e mandar
 * os dois é recusado no serviço.
 */
public record PublishPostRequest(PostType type, String content, String pageHtml, String articleBody, List<String> tags,
		List<String> mediaIds, String communityId, List<String> mentionedUserIds, String topic, String channelId,
		String website, Boolean mediaSpoiler,
		/** Alternativas da enquete (2 a 6), apenas quando type == POLL; nos outros tipos ignora. */
		List<String> pollOptions,
		/** Enquete de multipla escolha (POLL): varias alternativas por pessoa. */
		Boolean pollMultipleChoice,
		/** Prazo de encerramento da enquete (POLL), em ISO-8601; ausente = sem prazo. */
		Instant pollEndsAt) {
}
