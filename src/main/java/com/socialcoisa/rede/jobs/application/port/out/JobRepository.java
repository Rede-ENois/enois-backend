package com.socialcoisa.rede.jobs.application.port.out;

import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.jobs.domain.Job;

public interface JobRepository {

	void save(Job job);

	Optional<Job> findById(String id);

	/**
	 * Igual ao findById, mas travando a linha da vaga ate o fim da transacao. Usado por
	 * candidatar-se e encerrar para serializar as duas operacoes.
	 */
	Optional<Job> findByIdForUpdate(String id);

	List<Job> findAll(String query, String type, String level, String location, List<String> skills,
			java.time.Instant before, int limit);

	void deleteById(String id);

	/** Vagas publicadas pelo usuario. */
	List<Job> findByPostedBy(String userId, java.time.Instant before, int limit);

	/** Vagas em que o usuario se candidatou, mais recentes primeiro. */
	List<Job> findAppliedByUser(String userId, java.time.Instant before, int limit);

	int countApplications(String jobId);

	/** Candidaturas ainda em APPLIED (sem nenhum retorno do empregador) — usado ao excluir. */
	int countUnansweredApplications(String jobId);

	/** Para listas: ids em que o usuario se candidatou/salvou, em consultas em lote. */
	java.util.Set<String> findAppliedJobIds(String userId, java.util.Collection<String> jobIds);

	java.util.Set<String> findSavedJobIds(String userId, java.util.Collection<String> jobIds);

	/** Todas as vagas salvas do usuário, mais recentes primeiro (a aba de favoritas). */
	List<String> findSavedJobIds(String userId);

	/** Vagas internas por id (resolver as favoritas). */
	List<Job> findByIds(java.util.Collection<String> ids);

	java.util.Map<String, Integer> countApplicationsByJobIds(java.util.Collection<String> jobIds);

	/** Quantas candidaturas de cada vaga estão no estado dado, numa consulta só. */
	java.util.Map<String, Integer> countApplicationsByJobIdsAndStatus(java.util.Collection<String> jobIds,
			com.socialcoisa.rede.jobs.domain.JobApplicationStatus status);

	/** Grava a candidatura (com respostas e retrato do perfil) e devolve o id gerado. */
	String saveApplication(com.socialcoisa.rede.jobs.domain.JobApplication application);

	/** Candidaturas recebidas por uma vaga, mais recentes primeiro. */
	List<com.socialcoisa.rede.jobs.domain.JobApplication> findApplicationsByJob(String jobId, int limit);

	/** Candidaturas feitas por uma pessoa, da mais recente para a mais antiga. */
	List<com.socialcoisa.rede.jobs.domain.JobApplication> findApplicationsByUser(String userId, int limit);

	/** Uma candidatura pelo id, para a triagem do empregador. */
	Optional<com.socialcoisa.rede.jobs.domain.JobApplication> findApplicationById(String applicationId);

	/** Grava so o estado da candidatura (nao mexe nos outros campos nem nas respostas). */
	void updateApplicationStatus(String applicationId, com.socialcoisa.rede.jobs.domain.JobApplicationStatus status,
			String feedback, java.time.Instant reviewedAt);

	void removeApplication(String jobId, String userId);

	boolean hasApplied(String jobId, String userId);

	void saveBookmark(String jobId, String userId);

	void removeBookmark(String jobId, String userId);

	boolean hasBookmarked(String jobId, String userId);

}
