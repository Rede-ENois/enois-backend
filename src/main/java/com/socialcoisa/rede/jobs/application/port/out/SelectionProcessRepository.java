package com.socialcoisa.rede.jobs.application.port.out;

import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.jobs.domain.SelectionStage;
import com.socialcoisa.rede.jobs.domain.StageMovement;
import com.socialcoisa.rede.jobs.domain.StageNote;

/**
 * Persistência do pipeline de recrutamento: etapas do fluxo da vaga, movimentos das
 * candidaturas e notas internas da equipe.
 */
public interface SelectionProcessRepository {

	SelectionStage saveStage(SelectionStage stage);

	Optional<SelectionStage> findStageById(String stageId);

	List<SelectionStage> stagesOf(String jobId);

	void deleteStage(String stageId);

	void updateStagePositions(List<SelectionStage> stages);

	StageMovement saveMovement(StageMovement movement);

	/** Último movimento da candidatura — define a etapa atual. Vazio quando ninguém moveu ainda. */
	Optional<StageMovement> lastMovement(long applicationId);

	List<StageMovement> movementsOf(long applicationId);

	/** Movimentos de todas as candidaturas da vaga, em ordem — base da visão de pipeline. */
	List<StageMovement> movementsOfJob(String jobId);

	/**
	 * Último movimento de cada candidatura, para um conjunto de vagas, numa consulta só — a
	 * base da Central de recrutamento. Uma consulta por candidatura seria o N+1 que a tela com
	 * milhares de candidatos não aguenta.
	 */
	List<StageMovement> latestMovementsOfJobs(java.util.Collection<String> jobIds);

	/**
	 * Último movimento de cada uma das candidaturas pedidas, numa consulta só. A listagem
	 * paginada precisa da etapa apenas das linhas da página — pedir o histórico da vaga inteira
	 * desfaria a paginação.
	 */
	List<StageMovement> latestMovementsOf(java.util.Collection<Long> applicationIds);

	StageNote saveNote(StageNote note);

	List<StageNote> notesOf(long applicationId);

}
