package com.socialcoisa.rede.feed.application.port.in;

import com.socialcoisa.rede.feed.application.PostView;
import com.socialcoisa.rede.feed.domain.UserId;

public interface GetPostUseCase {

	/**
	 * Leitura de um post unico por id (usado, por exemplo, para abrir a notificacao no post real).
	 */
	PostView getPost(String postId, UserId viewerId);
}