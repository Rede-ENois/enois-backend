package com.socialcoisa.rede.jobs.application.port.out;

import java.util.Optional;

import com.socialcoisa.rede.jobs.domain.JobApplicationDraft;

/**
 * Rascunho da candidatura (autosave). Um por par (candidato, vaga) — gravar por cima é o
 * comportamento, não caso especial.
 */
public interface JobApplicationDraftRepository {

	JobApplicationDraft save(JobApplicationDraft draft);

	/** O rascunho deste candidato nesta vaga, se existir. */
	Optional<JobApplicationDraft> find(String jobId, String userId);

	void delete(String jobId, String userId);
}
