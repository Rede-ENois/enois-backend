package com.socialcoisa.rede.feed.domain;

import java.time.Instant;
import java.util.List;

/**
 * Onde a pessoa parou de escrever no compositor: um rascunho por usuário, guardado FORA de
 * {@code posts} — não é post, não aparece em feed, perfil nem busca. Salvar de novo atualiza o
 * mesmo rascunho; publicar, agendar ou descartar o apaga. As extensões da enquete viajam com o
 * rascunho (na publicação direta elas valem; no agendamento a enquete nasce simples, como hoje).
 *
 * A mídia anexada NÃO faz parte do rascunho: os arquivos só sobem no momento de publicar — o
 * rascunho guarda o que é texto e estrutura, e a pessoa reanexa a mídia ao retomar.
 */
public record DraftPost(String id, String userId, PostType type, String content, String pageHtml,
		String articleBody, String topic, List<String> pollOptions, boolean pollMultipleChoice,
		Instant pollEndsAt, List<String> mentionedUserIds, Instant createdAt, Instant updatedAt) {
}
