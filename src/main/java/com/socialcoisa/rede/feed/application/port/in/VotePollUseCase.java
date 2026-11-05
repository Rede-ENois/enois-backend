package com.socialcoisa.rede.feed.application.port.in;

import com.socialcoisa.rede.feed.application.PollView;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.UserId;

public interface VotePollUseCase {

	/** Voto unico e definitivo: uma opcao, um voto por pessoa, sem troca depois. */
	PollView vote(PostId postId, UserId user, int optionPosition);
}
