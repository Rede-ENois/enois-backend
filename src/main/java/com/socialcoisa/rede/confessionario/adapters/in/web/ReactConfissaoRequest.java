package com.socialcoisa.rede.confessionario.adapters.in.web;

/**
 * Corpo do POST /confessionario/posts/{postId}/reactions. Vale qualquer
 * {@link com.socialcoisa.rede.feed.domain.ReactionType}; a resposta é contagem, nunca a lista
 * de quem reagiu.
 */
public record ReactConfissaoRequest(String reaction) {
}
