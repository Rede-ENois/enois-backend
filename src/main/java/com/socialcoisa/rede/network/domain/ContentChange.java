package com.socialcoisa.rede.network.domain;

import java.time.Instant;

/**
 * Uma mudança de conteúdo público no feed que o nó de borda consome (V283).
 *
 * <p>É o delta, não o conteúdo: diz qual agregado ganhou versão nova, e o nó decide se recarrega a
 * entrada que ele tem em cache. Sem isto, um nó com milhares de agregados em cache não tem como
 * saber o que mudou — teria de perguntar um por um, e o que ninguém pergunta nunca converge.</p>
 *
 * <p>O {@code seq} é a posição no feed, monotônica e estritamente crescente. O nó guarda o último
 * que processou e pede o que veio depois. Não é carimbo de tempo pelo mesmo motivo que a versão não
 * é (ver {@link ContentVersion}): duas mudanças no mesmo milissegundo precisam de ordem total, e uma
 * hora repetida faria o nó pular uma delas ou reler a outra para sempre.</p>
 *
 * @param seq posição no feed
 * @param kind tipo do conteúdo (a allowlist)
 * @param contentId id estável do agregado
 * @param version versão cunhada nesta mudança
 * @param contentHash sha-256 hex do corpo dessa versão
 * @param changedAt quando a versão foi cunhada
 */
public record ContentChange(long seq, ContentKind kind, String contentId, long version, String contentHash,
		Instant changedAt) {
}