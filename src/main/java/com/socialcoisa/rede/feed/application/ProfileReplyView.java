package com.socialcoisa.rede.feed.application;

/** Comentario do usuario com contexto do post original, para a aba de respostas do perfil. */
public record ProfileReplyView(
		CommentView comment,
		ReplyContext context) {

	public record ReplyContext(
			String postId,
			String postAuthorName,
			String postAuthorUsername,
			String postContentSnippet) {
	}
}
