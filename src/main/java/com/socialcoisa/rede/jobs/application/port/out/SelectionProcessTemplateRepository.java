package com.socialcoisa.rede.jobs.application.port.out;

import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.jobs.domain.SelectionProcessTemplate;

/** Modelos de processo seletivo de quem recruta (biblioteca pessoal). */
public interface SelectionProcessTemplateRepository {

	SelectionProcessTemplate save(SelectionProcessTemplate template);

	Optional<SelectionProcessTemplate> findById(String id);

	/** Modelos do dono, do mais recente para o mais antigo, já cortados no banco. */
	List<SelectionProcessTemplate> findByOwner(String ownerId, int limit);

	void deleteById(String id);
}
