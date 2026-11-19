package com.socialcoisa.rede.confessionario.adapters.in.web;

/**
 * Corpo do POST /confessionario/posts/{postId}/comments. Reação em texto puro, resposta direta
 * à história — sem comentário de comentário e sem mídia.
 */
public record CommentConfissaoRequest(String content) {
}
