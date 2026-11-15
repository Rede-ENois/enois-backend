package com.socialcoisa.rede.jobs.adapters.in.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.jobs.application.JobService;
import com.socialcoisa.rede.jobs.application.SelectionProcessService;
import com.socialcoisa.rede.jobs.domain.JobApplication;
import com.socialcoisa.rede.jobs.domain.SelectionStage;
import com.socialcoisa.rede.jobs.domain.StageMovement;
import com.socialcoisa.rede.jobs.domain.StageMovementKind;
import com.socialcoisa.rede.jobs.domain.StageNote;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * Processo seletivo da vaga (pipeline de recrutamento): fluxo de etapas, Kanban das
 * candidaturas e ficha interna (movimentos e notas).
 *
 * <p>Mesma superfície de {@link JobContractController} (`/jobs/{jobId}/...`), arquivo próprio
 * porque aquele já acumula vaga, agregador externo, entrevista e currículo. Toda a autorização
 * está no serviço, no backend — nenhuma rota daqui confia no que o cliente diz sobre quem é.
 */
@RestController
@RequestMapping("/jobs/{jobId}/process")
class SelectionProcessController {

	private final SelectionProcessService process;
	private final JobService jobs;
	private final CurrentUserResolver currentUserResolver;

	SelectionProcessController(SelectionProcessService process, JobService jobs,
			CurrentUserResolver currentUserResolver) {
		this.process = process;
		this.jobs = jobs;
		this.currentUserResolver = currentUserResolver;
	}

	// ---------- Fluxo de etapas ----------

	@GetMapping("/stages")
	List<StageView> stages(@PathVariable String jobId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return process.stages(jobId, user.id().value()).stream().map(StageView::of).toList();
	}

	@PostMapping("/stages")
	StageView addStage(@PathVariable String jobId, @RequestBody StageRequest body,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return StageView.of(process.addStage(jobId, user.id().value(), body.name()));
	}

	@PatchMapping("/stages/{stageId}")
	StageView renameStage(@PathVariable String jobId, @PathVariable String stageId,
			@RequestBody StageRequest body,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return StageView.of(process.renameStage(stageId, user.id().value(), body.name()));
	}

	@DeleteMapping("/stages/{stageId}")
	void deleteStage(@PathVariable String jobId, @PathVariable String stageId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		process.deleteStage(stageId, user.id().value());
	}

	@PostMapping("/stages/order")
	List<StageView> reorder(@PathVariable String jobId, @RequestBody ReorderRequest body,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return process.reorderStages(jobId, user.id().value(), body.stageIds()).stream()
			.map(StageView::of)
			.toList();
	}

	// ---------- Kanban ----------

	/**
	 * O board inteiro numa resposta: etapas na ordem, candidaturas na etapa atual, e os
	 * contadores do topo. A candidatura é a mesma de `/jobs/{jobId}/applications` — este board
	 * só acrescenta onde ela está no fluxo, sem criar uma segunda candidatura.
	 */
	@GetMapping
	BoardView board(@PathVariable String jobId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String requesterId = user.id().value();
		List<SelectionStage> stages = process.stages(jobId, requesterId);
		Map<Long, SelectionProcessService.PipelinePosition> positions = process.positionsOf(jobId, requesterId);
		List<JobApplication> applications = jobs.applicationsOf(jobId, requesterId, 1000);

		Map<String, List<BoardCardView>> columns = new LinkedHashMap<>();
		for (SelectionStage stage : stages) {
			columns.put(stage.id(), new ArrayList<>());
		}
		// Vaga ainda sem fluxo configurado: uma coluna de entrada (sem etapa) para ninguém ficar
		// invisível enquanto o processo é organizado.
		if (stages.isEmpty()) {
			columns.put(null, new ArrayList<>());
		}
		List<BoardCardView> rejected = new ArrayList<>();
		int inProcess = 0;
		for (JobApplication application : applications) {
			if (application.id() == null) {
				continue;
			}
			SelectionProcessService.PipelinePosition position = positions.get(Long.valueOf(application.id()));
			BoardCardView card = BoardCardView.of(application, position);
			if (position != null && position.rejected()) {
				rejected.add(card);
				continue;
			}
			inProcess++;
			String stageId = position == null ? null : position.stageId();
			String columnId = columns.containsKey(stageId) ? stageId : null;
			columns.computeIfAbsent(columnId, unused -> new ArrayList<>()).add(card);
		}
		return new BoardView(stages.stream().map(StageView::of).toList(),
			columns.entrySet().stream().map(entry -> new ColumnView(entry.getKey(), entry.getValue())).toList(),
			rejected, new PipelineSummaryView(applications.size(), inProcess, rejected.size()));
	}

	// ---------- Movimento ----------

	@PostMapping("/applications/{applicationId}/move")
	MovementView move(@PathVariable String jobId, @PathVariable String applicationId,
			@RequestBody MoveRequest body,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		StageMovementKind kind = switch (body.kind() == null ? "ADVANCE" : body.kind().toUpperCase()) {
			case "ADVANCE" -> StageMovementKind.ADVANCE;
			case "RETURN" -> StageMovementKind.RETURN;
			case "REJECT" -> StageMovementKind.REJECT;
			default -> throw new com.socialcoisa.rede.shared.web.ApiException(400, "INVALID_MOVEMENT_KIND",
				"A movimentação precisa ser ADVANCE, RETURN ou REJECT.");
		};
		return MovementView.of(process.moveApplication(jobId, applicationId, user.id().value(), body.stageId(),
			kind, body.note()));
	}

	/** Linha do tempo da candidatura: quem moveu, quando, de onde para onde. */
	@GetMapping("/applications/{applicationId}/movements")
	List<MovementView> movements(@PathVariable String jobId, @PathVariable String applicationId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return process.movements(jobId, applicationId, user.id().value()).stream()
			.map(MovementView::of)
			.toList();
	}

	// ---------- Notas internas ----------

	@GetMapping("/applications/{applicationId}/notes")
	List<NoteView> notes(@PathVariable String jobId, @PathVariable String applicationId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return process.notes(jobId, applicationId, user.id().value()).stream().map(NoteView::of).toList();
	}

	@PostMapping("/applications/{applicationId}/notes")
	NoteView addNote(@PathVariable String jobId, @PathVariable String applicationId,
			@RequestBody NoteRequest body,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return NoteView.of(process.addNote(jobId, applicationId, user.id().value(), body.body()));
	}

	// ---------- Contratos ----------

	record StageRequest(String name) {
	}

	record ReorderRequest(List<String> stageIds) {
	}

	record MoveRequest(String stageId, String kind, String note) {
	}

	record NoteRequest(String body) {
	}

	record StageView(String id, String name, int position) {

		static StageView of(SelectionStage stage) {
			return new StageView(stage.id(), stage.name(), stage.position());
		}
	}

	record BoardCardView(String applicationId, String userId, String status, String appliedAt,
			String stageId, boolean rejected, String lastMovementAt) {

		static BoardCardView of(JobApplication application,
				SelectionProcessService.PipelinePosition position) {
			return new BoardCardView(application.id(), application.userId(), application.status().name(),
				application.appliedAt() == null ? null : application.appliedAt().toString(),
				position == null ? null : position.stageId(), position != null && position.rejected(),
				position == null || position.lastMovementAt() == null ? null
					: position.lastMovementAt().toString());
		}
	}

	record ColumnView(String stageId, List<BoardCardView> cards) {
	}

	record PipelineSummaryView(int total, int inProcess, int rejected) {
	}

	record BoardView(List<StageView> stages, List<ColumnView> columns, List<BoardCardView> rejected,
			PipelineSummaryView summary) {
	}

	record MovementView(String id, String applicationId, String fromStageId, String toStageId, String kind,
			String actorId, String note, String movedAt) {

		static MovementView of(StageMovement movement) {
			return new MovementView(movement.id() == null ? null : String.valueOf(movement.id()),
				String.valueOf(movement.applicationId()), movement.fromStageId(), movement.toStageId(),
				movement.kind().name(), movement.actorId(), movement.note(), movement.movedAt().toString());
		}
	}

	record NoteView(String id, String applicationId, String authorId, String body, String createdAt) {

		static NoteView of(StageNote note) {
			return new NoteView(note.id() == null ? null : String.valueOf(note.id()),
				String.valueOf(note.applicationId()), note.authorId(), note.body(),
				note.createdAt().toString());
		}
	}

}