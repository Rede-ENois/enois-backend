package com.socialcoisa.rede.feed.application.port.in;

import com.socialcoisa.rede.feed.application.PollView;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.UserId;

public interface VotePollUseCase {

	/** Grava o voto na alternativa: cria o primeiro ou move o existente. Um voto por pessoa. */
	PollView vote(PostId postId, UserId user, int optionPosition);
}
