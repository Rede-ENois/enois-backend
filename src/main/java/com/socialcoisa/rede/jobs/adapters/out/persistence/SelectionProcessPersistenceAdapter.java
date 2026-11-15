package com.socialcoisa.rede.jobs.adapters.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.jobs.application.port.out.SelectionProcessRepository;
import com.socialcoisa.rede.jobs.domain.SelectionStage;
import com.socialcoisa.rede.jobs.domain.StageMovement;
import com.socialcoisa.rede.jobs.domain.StageNote;

@Repository
class SelectionProcessPersistenceAdapter implements SelectionProcessRepository {

	private final SpringDataSelectionStageJpaRepository stages;
	private final SpringDataStageMovementJpaRepository movements;
	private final SpringDataStageNoteJpaRepository notes;

	SelectionProcessPersistenceAdapter(SpringDataSelectionStageJpaRepository stages,
			SpringDataStageMovementJpaRepository movements, SpringDataStageNoteJpaRepository notes) {
		this.stages = stages;
		this.movements = movements;
		this.notes = notes;
	}

	@Override
	public SelectionStage saveStage(SelectionStage stage) {
		return stages.save(SelectionStageJpaEntity.fromDomain(stage)).toDomain();
	}

	@Override
	public Optional<SelectionStage> findStageById(String stageId) {
		return stages.findById(stageId).map(SelectionStageJpaEntity::toDomain);
	}

	@Override
	public List<SelectionStage> stagesOf(String jobId) {
		return stages.findByJobIdOrderByPositionAsc(jobId).stream()
			.map(SelectionStageJpaEntity::toDomain)
			.toList();
	}

	@Override
	public void deleteStage(String stageId) {
		stages.deleteById(stageId);
	}

	@Override
	public void updateStagePositions(List<SelectionStage> reordered) {
		for (SelectionStage stage : reordered) {
			stages.findById(stage.id()).ifPresent(entity -> {
				entity.applyPositionAndName(stage.position(), stage.name());
				stages.save(entity);
			});
		}
	}

	@Override
	public StageMovement saveMovement(StageMovement movement) {
		return movements.save(StageMovementJpaEntity.fromDomain(movement)).toDomain();
	}

	@Override
	public Optional<StageMovement> lastMovement(long applicationId) {
		return movements.findFirstByApplicationIdOrderByMovedAtDesc(applicationId)
			.map(StageMovementJpaEntity::toDomain);
	}

	@Override
	public List<StageMovement> movementsOf(long applicationId) {
		return movements.findByApplicationIdOrderByMovedAtAsc(applicationId).stream()
			.map(StageMovementJpaEntity::toDomain)
			.toList();
	}

	@Override
	public List<StageMovement> movementsOfJob(String jobId) {
		return movements.findByJobIdOrderByMovedAtAsc(jobId).stream()
			.map(StageMovementJpaEntity::toDomain)
			.toList();
	}

	@Override
	public List<StageMovement> latestMovementsOfJobs(java.util.Collection<String> jobIds) {
		if (jobIds.isEmpty()) {
			return List.of();
		}
		return movements.findLatestPerApplicationForJobs(jobIds).stream()
			.map(StageMovementJpaEntity::toDomain)
			.toList();
	}

	@Override
	public List<StageMovement> latestMovementsOf(java.util.Collection<Long> applicationIds) {
		if (applicationIds.isEmpty()) {
			return List.of();
		}
		return movements.findLatestPerApplication(applicationIds).stream()
			.map(StageMovementJpaEntity::toDomain)
			.toList();
	}

	@Override
	public StageNote saveNote(StageNote note) {
		return notes.save(StageNoteJpaEntity.fromDomain(note)).toDomain();
	}

	@Override
	public List<StageNote> notesOf(long applicationId) {
		return notes.findByApplicationIdOrderByCreatedAtAsc(applicationId).stream()
			.map(StageNoteJpaEntity::toDomain)
			.toList();
	}

}
