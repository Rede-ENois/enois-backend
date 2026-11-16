package com.socialcoisa.rede.feed.application.port.out;

import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.feed.domain.DraftPost;

public interface DraftPostRepository {

	DraftPost save(DraftPost draft);

	/** O rascunho mais recente de alguém (a visão de um só, mantida para o compositor atual). */
	Optional<DraftPost> findByUser(String userId);

	/** Todos os rascunhos da pessoa, do mais recente ao mais antigo. */
	List<DraftPost> findAllByUser(String userId);

	/** Um rascunho pelo id, sem olhar dono — o chamador confere a posse. */
	Optional<DraftPost> findById(String id);

	void delete(String id);
}
