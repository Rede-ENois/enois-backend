package com.socialcoisa.rede.jobs.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.jobs.application.port.out.JobRepository;
import com.socialcoisa.rede.jobs.application.port.out.SelectionProcessRepository;
import com.socialcoisa.rede.jobs.domain.Job;
import com.socialcoisa.rede.jobs.domain.JobApplication;
import com.socialcoisa.rede.jobs.domain.JobApplicationStatus;
import com.socialcoisa.rede.jobs.domain.SelectionStage;
import com.socialcoisa.rede.jobs.domain.StageMovement;
import com.socialcoisa.rede.jobs.domain.StageMovementKind;
import com.socialcoisa.rede.jobs.domain.StageNote;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Pipeline de recrutamento de uma vaga: etapas do fluxo, movimento das candidaturas entre
 * etapas e notas internas da equipe.
 *
 * <p>Autorização: quem publicou a vaga comanda o processo — o mesmo dono que já tria
 * candidaturas em {@link JobService}. Tudo validado aqui no backend; o front apenas mostra.
 *
 * <p>A etapa atual de uma candidatura é o ÚLTIMO movimento dela — nunca um campo solto, para
 * não existir segunda fonte de verdade. Candidatura sem movimento está na entrada do fluxo
 * (a primeira etapa, quando configurada).
 */
@Service
public class SelectionProcessService {

	private final JobRepository jobs;
	private final SelectionProcessRepository pipeline;
	private final Clock clock;

	public SelectionProcessService(JobRepository jobs, SelectionProcessRepository pipeline, Clock clock) {
		this.jobs = jobs;
		this.pipeline = pipeline;
		this.clock = clock;
	}

	// ---------- Configuração do fluxo da vaga ----------

	@Transactional
	public SelectionStage addStage(String jobId, String requesterId, String name) {
		requireOwner(jobId, requesterId);
		List<SelectionStage> existing = pipeline.stagesOf(jobId);
		if (existing.size() >= SelectionStage.MAX_STAGES_PER_JOB) {
			throw new ApiException(422, "TOO_MANY_STAGES",
				"O fluxo desta vaga já tem o máximo de " + SelectionStage.MAX_STAGES_PER_JOB + " etapas.");
		}
		Set<String> names = existing.stream()
			.map(stage -> stage.name().toLowerCase())
			.collect(Collectors.toCollection(LinkedHashSet::new));
		if (names.contains(name.trim().toLowerCase())) {
			throw new ApiException(409, "STAGE_NAME_TAKEN", "Esta vaga já tem uma etapa com esse nome.");
		}
		SelectionStage stage = SelectionStage.create("stage_" + java.util.UUID.randomUUID().toString()
			.replace("-", ""), jobId, name, existing.size(), clock.instant());
		return pipeline.saveStage(stage);
	}

	@Transactional
	public SelectionStage renameStage(String stageId, String requesterId, String newName) {
		SelectionStage stage = requireStageOfOwnedJob(stageId, requesterId);
		String clean = newName == null ? "" : newName.trim();
		pipeline.stagesOf(stage.jobId()).stream()
			.filter(other -> !other.id().equals(stageId))
			.map(other -> other.name().toLowerCase())
			.filter(other -> other.equals(clean.toLowerCase()))
			.findFirst()
			.ifPresent(other -> {
				throw new ApiException(409, "STAGE_NAME_TAKEN", "Esta vaga já tem uma etapa com esse nome.");
			});
		return pipeline.saveStage(stage.renamed(clean));
	}

	@Transactional
	public void deleteStage(String stageId, String requesterId) {
		SelectionStage stage = requireStageOfOwnedJob(stageId, requesterId);
		// Etapa que alguém está ocupando AGORA não se apaga: o card do candidato ficaria apontando
		// para uma etapa inexistente. Etapa por onde só passaram (histórico) pode sair — o
		// movimento registrado continua contando o que aconteceu.
		if (lastMovements(stage.jobId()).values().stream().anyMatch(m -> stageId.equals(m.toStageId()))) {
			throw new ApiException(409, "STAGE_IN_USE",
				"Há candidatos nesta etapa. Mova-os antes de apagar; o histórico de quem passou fica.");
		}
		pipeline.deleteStage(stageId);
		resequence(stage.jobId());
	}

	@Transactional
	public List<SelectionStage> reorderStages(String jobId, String requesterId, List<String> stageIdsInOrder) {
		requireOwner(jobId, requesterId);
		List<SelectionStage> existing = pipeline.stagesOf(jobId);
		if (stageIdsInOrder.size() != existing.size()) {
			throw new ApiException(422, "REORDER_INCOMPLETE",
				"A reordenação precisa listar todas as etapas da vaga, sem nenhuma a mais.");
		}
		Map<String, SelectionStage> byId = existing.stream()
			.collect(Collectors.toMap(SelectionStage::id, Function.identity()));
		List<SelectionStage> reordered = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		for (String stageId : stageIdsInOrder) {
			SelectionStage stage = byId.get(stageId);
			if (stage == null || !seen.add(stageId)) {
				throw new ApiException(422, "REORDER_INVALID",
					"A ordem enviada não corresponde às etapas desta vaga.");
			}
			reordered.add(stage.atPosition(reordered.size()));
		}
		pipeline.updateStagePositions(reordered);
		return pipeline.stagesOf(jobId);
	}

	public List<SelectionStage> stages(String jobId, String requesterId) {
		requireOwner(jobId, requesterId);
		return pipeline.stagesOf(jobId);
	}

	// ---------- Movimento das candidaturas ----------

	/**
	 * Move a candidatura para uma etapa (ou reprova). O motivo é obrigatório na reprovação e
	 * vira parte do histórico que o candidato vê — o mesmo retorno de que ele já dispunha,
	 * agora amarrado ao momento do processo.
	 */
	@Transactional
	public StageMovement moveApplication(String jobId, String applicationId, String requesterId,
			String toStageId, StageMovementKind kind, String note) {
		Job job = requireOwnedJob(jobId, requesterId);
		JobApplication application = jobs.findApplicationById(applicationId)
			.filter(found -> found.jobId().equals(jobId))
			.orElseThrow(() -> new ApiException(404, "APPLICATION_NOT_FOUND", "Candidatura não encontrada."));
		if (application.status() == JobApplicationStatus.NOT_SELECTED) {
			throw new ApiException(409, "APPLICATION_REJECTED",
				"Esta candidatura foi reprovada e não se move no pipeline.");
		}

		List<SelectionStage> stages = pipeline.stagesOf(jobId);
		Optional<StageMovement> last = pipeline.lastMovement(Long.parseLong(applicationId));
		String currentStageId = last.map(StageMovement::toStageId).orElseGet(
			() -> stages.isEmpty() ? null : stages.get(0).id());

		if (kind == StageMovementKind.REJECT) {
			String reason = note == null || note.isBlank() ? null : note.trim();
			if (reason == null) {
				throw new ApiException(400, "FEEDBACK_REQUIRED",
					"Conte por que a candidatura não seguiu — o candidato recebe esse retorno.");
			}
			StageMovement movement = new StageMovement(null, Long.parseLong(applicationId), jobId, currentStageId,
				currentStageId,
				StageMovementKind.REJECT, requesterId, reason, clock.instant());
			StageMovement saved = pipeline.saveMovement(movement);
			jobs.updateApplicationStatus(applicationId, JobApplicationStatus.NOT_SELECTED, reason,
				clock.instant());
			return saved;
		}

		SelectionStage target = stages.stream()
			.filter(stage -> stage.id().equals(toStageId))
			.findFirst()
			.orElseThrow(() -> new ApiException(404, "STAGE_NOT_FOUND",
				"Etapa não encontrada neste fluxo."));
		if (toStageId.equals(currentStageId)) {
			throw new ApiException(409, "ALREADY_IN_STAGE", "A candidatura já está nesta etapa.");
		}
		StageMovementKind effective = kind == StageMovementKind.RETURN ? StageMovementKind.RETURN
			: StageMovementKind.ADVANCE;
		return pipeline.saveMovement(new StageMovement(null, Long.parseLong(applicationId), jobId, currentStageId,
			target.id(), effective, requesterId, note, clock.instant()));
	}

	public record PipelinePosition(String stageId, boolean rejected, java.time.Instant lastMovementAt) {
	}

	/**
	 * O Kanban da vaga: etapa atual de cada candidatura. A etapa é o destino do último
	 * movimento; sem movimento, a candidatura está na entrada do fluxo. Uma consulta de
	 * movimentos para a vaga inteira — não uma por candidato.
	 */
	public Map<Long, PipelinePosition> positionsOf(String jobId, String requesterId) {
		requireOwner(jobId, requesterId);
		List<SelectionStage> stages = pipeline.stagesOf(jobId);
		String entry = stages.isEmpty() ? null : stages.get(0).id();
		Map<Long, PipelinePosition> positions = new java.util.LinkedHashMap<>();
		for (Map.Entry<Long, StageMovement> entryMovement : lastMovements(jobId).entrySet()) {
			StageMovement movement = entryMovement.getValue();
			positions.put(entryMovement.getKey(), new PipelinePosition(movement.toStageId(),
				movement.kind() == StageMovementKind.REJECT, movement.movedAt()));
		}
		// Candidaturas sem movimento aparecem na entrada do fluxo.
		for (JobApplication application : jobs.findApplicationsByJob(jobId, 1000)) {
			if (application.id() == null) {
				continue;
			}
			positions.putIfAbsent(Long.valueOf(application.id()),
				new PipelinePosition(entry, application.status() == JobApplicationStatus.NOT_SELECTED, null));
		}
		return positions;
	}

	/** Último movimento de cada candidatura da vaga, numa consulta só. */
	private Map<Long, StageMovement> lastMovements(String jobId) {
		Map<Long, StageMovement> last = new java.util.LinkedHashMap<>();
		// A consulta vem em ordem crescente; o último visto por candidatura é o atual.
		for (StageMovement movement : pipeline.movementsOfJob(jobId)) {
			last.put(movement.applicationId(), movement);
		}
		return last;
	}

	/** Histórico completo de movimentos da candidatura (dono da vaga). */
	public List<StageMovement> movements(String jobId, String applicationId, String requesterId) {
		requireOwnedApplication(jobId, applicationId, requesterId);
		return pipeline.movementsOf(Long.parseLong(applicationId));
	}

	// ---------- Notas internas ----------

	@Transactional
	public StageNote addNote(String jobId, String applicationId, String requesterId, String body) {
		requireOwnedApplication(jobId, applicationId, requesterId);
		return pipeline.saveNote(new StageNote(null, Long.parseLong(applicationId), requesterId, body,
			clock.instant()));
	}

	/** Notas internas: só o dono da vaga. Nunca expostas ao candidato por nenhum caminho daqui. */
	public List<StageNote> notes(String jobId, String applicationId, String requesterId) {
		requireOwnedApplication(jobId, applicationId, requesterId);
		return pipeline.notesOf(Long.parseLong(applicationId));
	}

	// ---------- Autorização ----------

	private void requireOwner(String jobId, String requesterId) {
		requireOwnedJob(jobId, requesterId);
	}

	private Job requireOwnedJob(String jobId, String requesterId) {
		Job job = jobs.findById(jobId)
			.orElseThrow(() -> new ApiException(404, "JOB_NOT_FOUND", "Vaga não encontrada."));
		if (!job.postedBy().equals(requesterId)) {
			throw new ApiException(403, "NOT_OWNER", "Só quem publicou a vaga comanda o processo seletivo.");
		}
		return job;
	}

	private SelectionStage requireStageOfOwnedJob(String stageId, String requesterId) {
		SelectionStage stage = pipeline.findStageById(stageId)
			.orElseThrow(() -> new ApiException(404, "STAGE_NOT_FOUND", "Etapa não encontrada."));
		requireOwner(stage.jobId(), requesterId);
		return stage;
	}

	private void requireOwnedApplication(String jobId, String applicationId, String requesterId) {
		requireOwner(jobId, requesterId);
		jobs.findApplicationById(applicationId)
			.filter(found -> found.jobId().equals(jobId))
			.orElseThrow(() -> new ApiException(404, "APPLICATION_NOT_FOUND", "Candidatura não encontrada."));
	}

	/** Refaz as posições 0..n-1 depois de uma exclusão, sem buracos na ordem. */
	private void resequence(String jobId) {
		List<SelectionStage> remaining = pipeline.stagesOf(jobId);
		List<SelectionStage> resequenced = new ArrayList<>();
		int index = 0;
		for (SelectionStage stage : remaining) {
			resequenced.add(stage.atPosition(index++));
		}
		if (!resequenced.equals(remaining)) {
			pipeline.updateStagePositions(resequenced);
		}
	}

}
