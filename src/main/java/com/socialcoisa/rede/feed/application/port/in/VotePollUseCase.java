package com.socialcoisa.rede.feed.application.port.in;

import java.util.List;

import com.socialcoisa.rede.feed.application.PollView;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.UserId;

public interface VotePollUseCase {

	/**
	 * Registra (ou troca) a selecao de alternativas de quem vota, enquanto a enquete esta
	 * aberta. `option` e a escolha unica; `options` e a lista da multipla escolha — o servico
	 * decide qual campo usar pela configuracao da enquete.
	 */
	PollView vote(PostId postId, UserId user, Integer option, List<Integer> options);
}
