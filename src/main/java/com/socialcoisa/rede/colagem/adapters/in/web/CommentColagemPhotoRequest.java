package com.socialcoisa.rede.colagem.adapters.in.web;

/** Corpo do POST /colagem/posts/{postId}/comments: texto, uma foto, ou os dois. */
public record CommentColagemPhotoRequest(String parentCommentId, String content, String mediaId,
		Boolean mediaSpoiler) {
}
