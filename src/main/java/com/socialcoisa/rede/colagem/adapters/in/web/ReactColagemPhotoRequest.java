package com.socialcoisa.rede.colagem.adapters.in.web;

/** Corpo do POST /colagem/posts/{postId}/reactions: o nome do tipo da reação (LIKE, FIRE...). */
public record ReactColagemPhotoRequest(String reaction) {
}
