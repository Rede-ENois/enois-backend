package com.socialcoisa.rede.feed.application.port.in;

import com.socialcoisa.rede.feed.application.PostView;
import com.socialcoisa.rede.feed.domain.UserId;

public interface GetPostUseCase {

	/**
	 * Leitura de um post unico por id (usado, por exemplo, para abrir a notificacao no post real).
	 */
	PostView getPost(String postId, UserId viewerId);

	/**
	 * Confere que o post existe e que quem pediu pode ve-lo. Post de canal privado so e visivel
	 * para membro da comunidade; para qualquer outro visitante responde 404 (nao revela que o
	 * post existe). Usado pelas rotas que agem sobre um post antes de ler ou gravar algo nele.
	 */
	void requireVisible(String postId, UserId viewerId);
}
