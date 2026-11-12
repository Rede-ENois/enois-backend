package com.socialcoisa.rede.network.domain;

import java.util.List;

/**
 * Uma página do catálogo da réplica de leitura (fase 3, {@code NODE_READ_REPLICA}).
 *
 * <p>A réplica recebe as <b>projeções públicas</b> — as mesmas que a borda entrega, com a mesma
 * versão —, nunca tabela crua. A página é só a lista de ids que precisam ser lidos agora: o corpo
 * continua vindo de {@code GET /network/content/{kind}/{id}}, pelo mesmo caminho autorizado. Assim
 * o catálogo diz <i>o que existe</i> e a leitura de um item diz <i>o que ele é</i> em uma versão, e
 * os dois são o mesmo corpo versionado — não duas verdades sobre o mesmo agregado.</p>
 *
 * <p><b>O catálogo não pode ser mais largo que a vitrine.</b> Se um item que a vitrine esconde
 * (comunidade de conta banida, comunidade com o texto do ataque, conta removida) aparecesse aqui, a
 * réplica passaria a servir o que o Control Plane não serve — e a réplica é superfície pública. O
 * filtro é o mesmo da vitrine, aplicado na enumeração.</p>
 *
 * <p>{@code hasMore} e {@code nextCursor} são independentes de propósito. O cursor é "até onde eu
 * li" e existe sempre que algo foi lido — inclusive na última página, para a réplica guardar a
 * posição e não refazer a travessia inteira amanhã. {@code hasMore} é "há linhas além desta", e na
 * última página é falso mesmo com o cursor preenchido.</p>
 *
 * @param kind tipo do conteúdo (a allowlist {@link ContentKind})
 * @param items os ids desta página, na ordem da chave
 * @param hasMore há linhas além desta página no catálogo
 * @param nextCursor chave do último item lido; nulo só quando nada foi lido
 */
public record ReplicaPage(ContentKind kind, List<String> items, boolean hasMore, ReplicaCursor nextCursor) {

	public ReplicaPage {
		items = items == null ? List.of() : List.copyOf(items);
	}
}