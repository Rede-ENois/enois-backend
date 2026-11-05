package com.socialcoisa.rede.freelances.application.port.out;

import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.freelances.domain.Freelance;

public interface FreelanceRepository {
	Freelance save(Freelance freelance);
	Optional<Freelance> findById(String id);
	/** Freelas abertos; `query` nulo/vazio lista todos (busca no título e no escopo). */
	List<Freelance> findOpen(String query, int limit, int offset);
	List<Freelance> findByUser(String userId);
	long countOpen();
	/** Apaga o freela e as propostas dele (quem criou desistiu do trabalho). */
	void deleteById(String id);
}
