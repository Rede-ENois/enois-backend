package com.socialcoisa.rede.desabafos.adapters.in.web;

/**
 * Corpo do POST /desabafos/posts/{postId}/comments. Acolhida em texto puro, resposta direta ao
 * desabafo — sem comentário de comentário e sem mídia.
 */
public record CommentDesabafoRequest(String content) {
}
