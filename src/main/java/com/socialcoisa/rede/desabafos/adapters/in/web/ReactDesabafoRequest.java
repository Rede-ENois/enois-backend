package com.socialcoisa.rede.desabafos.adapters.in.web;

/**
 * Corpo do POST /desabafos/posts/{postId}/reactions. Vale qualquer {@link com.socialcoisa.rede.feed.domain.ReactionType},
 * mas a interface oferece só as de apoio; a resposta é contagem, nunca a lista de quem reagiu.
 */
public record ReactDesabafoRequest(String reaction) {
}
