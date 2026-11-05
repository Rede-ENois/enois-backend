package com.socialcoisa.rede.feed.domain;

import java.time.Instant;
import java.util.List;

/**
 * Uma publicação que ainda NÃO existe no feed: fica guardada até a hora marcada e o worker a
 * publica pelo mesmo caso de uso do composer. Se a publicação for recusada (conta restrita,
 * conteúdo inválido), o motivo viaja em {@code lastError} e a linha para de ser tentada.
 *
 * O assunto do post avulso viaja com ele: foi declarado quando foi agendado, então é o que
 * entra no ar — o post não perde a classificação por passar pela fila.
 *
 * As alternativas da enquete viajam igual: são o conteúdo do post, e sem elas a publicação seria
 * recusada na hora marcada por falta de alternativas.
 */
public record ScheduledPost(String id, String userId, PostType type, String content, String pageHtml,
		String articleBody, List<String> tags, List<String> mediaIds, List<String> mentionedUserIds,
		String communityId, String topic, List<String> pollOptions, Instant scheduledAt, Instant createdAt,
		String lastError, int attempts) {

	public ScheduledPost failed(String error) {
		return new ScheduledPost(id, userId, type, content, pageHtml, articleBody, tags, mediaIds, mentionedUserIds,
			communityId, topic, pollOptions, scheduledAt, createdAt, error, attempts + 1);
	}
}
