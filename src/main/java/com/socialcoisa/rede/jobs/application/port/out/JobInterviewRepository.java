package com.socialcoisa.rede.jobs.application.port.out;

import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.jobs.domain.JobInterview;

/** Porta de persistência das entrevistas. A interface não conhece JPA. */
public interface JobInterviewRepository {

	void save(JobInterview interview);

	Optional<JobInterview> findById(String id);

	/** A entrevista que está valendo para a candidatura (no máximo uma, pelo índice único). */
	Optional<JobInterview> findScheduledByApplication(String applicationId);

	List<JobInterview> findByApplication(String applicationId);

	/** As entrevistas valendo nas candidaturas de uma vaga (a lista do empregador). */
	List<JobInterview> findScheduledByJob(String jobId);

	/** As entrevistas valendo num lote de candidaturas (a lista do candidato), numa consulta só. */
	List<JobInterview> findScheduledByApplications(java.util.Collection<String> applicationIds);
}
