package com.socialcoisa.rede.jobs.application.port.out;

import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.jobs.domain.ExternalJobApplication;

/** Histórico das candidaturas enviadas pelo Énóis para vagas externas. */
public interface ExternalJobApplicationRepository {

	void save(ExternalJobApplication application);

	/** A candidatura desta pessoa para esta vaga (a regra é uma por vaga). */
	Optional<ExternalJobApplication> findByJobAndUser(String externalJobId, String userId);

	/** As candidaturas externas da pessoa, mais recentes primeiro. */
	List<ExternalJobApplication> findByUser(String userId, int limit);
}
