package com.socialcoisa.rede.feed.application.port.out;

import java.util.Optional;

import com.socialcoisa.rede.feed.domain.DraftPost;

public interface DraftPostRepository {

	DraftPost save(DraftPost draft);

	/** O rascunho de alguém — um por usuário (salvar de novo substitui). */
	Optional<DraftPost> findByUser(String userId);

	void delete(String id);
}
