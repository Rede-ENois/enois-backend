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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.companies.domain.RecruitmentCapability;
import com.socialcoisa.rede.jobs.application.CandidateTriageService;
import com.socialcoisa.rede.jobs.application.JobCommandAuthorization;
import com.socialcoisa.rede.jobs.application.JobService;
import com.socialcoisa.rede.jobs.application.SelectionProcessService;
import com.socialcoisa.rede.jobs.domain.ApplicationEvaluation;
import com.socialcoisa.rede.jobs.domain.CandidateScore;
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
	private final CandidateTriageService triage;
	private final JobCommandAuthorization command;
	private final CurrentUserResolver currentUserResolver;

	SelectionProcessController(SelectionProcessService process, JobService jobs, CandidateTriageService triage,
			JobCommandAuthorization command, CurrentUserResolver currentUserResolver) {
		this.process = process;
		this.jobs = jobs;
		this.triage = triage;
		this.command = command;
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
		List<SelectionStage> stages = process.stagesForBoard(jobId, requesterId);
		Map<Long, SelectionProcessService.PipelinePosition> positions = process.positionsOf(jobId, requesterId);
		List<JobApplication> applications = jobs.applicationsOf(jobId, requesterId, 1000);
		Map<String, CandidateScore> scores = triage.scoresOf(
			command.requireCommander(jobId, requesterId, RecruitmentCapability.VIEW_CANDIDATES,
				"Somente quem publicou a vaga ou a equipe dela pode ver a triagem das candidaturas."),
			applications);

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
			BoardCardView card = BoardCardView.of(application, position, scores.get(application.id()));
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
		return MovementView.of(process.moveApplication(jobId, applicationId, user.id().value(), body.stageId(),
			kindOf(body.kind()), body.note()));
	}

	/**
	 * Move várias candidaturas de uma vez. Cada item aparece no resultado, movido ou recusado com
	 * o motivo — a resposta 200 não quer dizer "todas entraram".
	 */
	@PostMapping("/applications/batch-move")
	List<BatchMoveResultView> batchMove(@PathVariable String jobId, @RequestBody BatchMoveRequest body,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return triage.moveBatch(jobId, user.id().value(), body.applicationIds(), body.stageId(), kindOf(body.kind()),
			body.note()).stream()
			.map(outcome -> new BatchMoveResultView(outcome.applicationId(),
				outcome.movement() == null ? null : MovementView.of(outcome.movement()),
				outcome.errorCode(), outcome.errorMessage()))
			.toList();
	}

	// ---------- Triagem ----------

	/**
	 * A fila de triagem: candidaturas com score explicável, da mais aderente para a menos, com a
	 * lista de componentes que formou cada ponto. Candidatura sem dado para comparar vem depois,
	 * marcada — não como a pior nota.
	 */
	@GetMapping("/candidates")
	List<RankedCandidateView> candidates(@PathVariable String jobId,
			@RequestParam(required = false) Integer limit,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return triage.ranked(jobId, user.id().value(), limit == null ? 100 : limit).stream()
			.map(RankedCandidateView::of)
			.toList();
	}

	private static StageMovementKind kindOf(String kind) {
		return switch (kind == null ? "ADVANCE" : kind.toUpperCase()) {
			case "ADVANCE" -> StageMovementKind.ADVANCE;
			case "RETURN" -> StageMovementKind.RETURN;
			case "REJECT" -> StageMovementKind.REJECT;
			default -> throw new com.socialcoisa.rede.shared.web.ApiException(400, "INVALID_MOVEMENT_KIND",
				"A movimentação precisa ser ADVANCE, RETURN ou REJECT.");
		};
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

	// ---------- Avaliações internas ----------

	@GetMapping("/applications/{applicationId}/evaluations")
	List<EvaluationView> evaluations(@PathVariable String jobId, @PathVariable String applicationId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return process.evaluations(jobId, applicationId, user.id().value()).stream()
			.map(EvaluationView::of)
			.toList();
	}

	@PutMapping("/applications/{applicationId}/evaluations")
	EvaluationView evaluate(@PathVariable String jobId, @PathVariable String applicationId,
			@RequestBody EvaluationRequest body,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return EvaluationView.of(process.evaluate(jobId, applicationId, user.id().value(), body.criteria(),
			body.parsedVerdict(), body.summary()));
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

	/**
	 * O card do board. O score vem com os componentes escritos (nunca só o número) e
	 * `scoreAvailable = false` quando a vaga não diz o que pede — a tela mostra "sem dados para
	 * comparar" em vez de "0".
	 */
	record BoardCardView(String applicationId, String userId, String status, String appliedAt,
			String stageId, boolean rejected, String lastMovementAt, ScoreView score) {

		static BoardCardView of(JobApplication application,
				SelectionProcessService.PipelinePosition position, CandidateScore score) {
			return new BoardCardView(application.id(), application.userId(), application.status().name(),
				application.appliedAt() == null ? null : application.appliedAt().toString(),
				position == null ? null : position.stageId(), position != null && position.rejected(),
				position == null || position.lastMovementAt() == null ? null
					: position.lastMovementAt().toString(),
				score == null ? null : ScoreView.of(score));
		}
	}

	record ScoreComponentView(String code, String label, int points, int maxPoints) {

		static ScoreComponentView of(CandidateScore.Component component) {
			return new ScoreComponentView(component.code(), component.label(), component.points(),
				component.maxPoints());
		}
	}

	record ScoreView(int total, boolean available, List<ScoreComponentView> components) {

		static ScoreView of(CandidateScore score) {
			return new ScoreView(score.total(), score.available(),
				score.components().stream().map(ScoreComponentView::of).toList());
		}
	}

	record BatchMoveRequest(List<String> applicationIds, String stageId, String kind, String note) {
	}

	record BatchMoveResultView(String applicationId, MovementView movement, String errorCode, String errorMessage) {
	}

	/** Candidatura na fila de triagem: quem é, em que pé está e por que tem esse score. */
	record RankedCandidateView(String applicationId, String userId, String name, String status, String appliedAt,
			ScoreView score) {

		static RankedCandidateView of(CandidateTriageService.Ranked ranked) {
			return new RankedCandidateView(ranked.application().id(), ranked.application().userId(),
				ranked.candidateName(), ranked.application().status().name(),
				ranked.application().appliedAt() == null ? null : ranked.application().appliedAt().toString(),
				ScoreView.of(ranked.score()));
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

	record EvaluationView(String id, String applicationId, String authorId, Map<String, Integer> criteria,
			String verdict, String summary, String createdAt, String updatedAt) {

		static EvaluationView of(ApplicationEvaluation evaluation) {
			return new EvaluationView(evaluation.id() == null ? null : String.valueOf(evaluation.id()),
				String.valueOf(evaluation.applicationId()), evaluation.authorId(), evaluation.criteria(),
				evaluation.verdict().name(), evaluation.summary(),
				evaluation.createdAt().toString(), evaluation.updatedAt().toString());
		}
	}

	record EvaluationRequest(Map<String, Integer> criteria, String verdict, String summary) {

		ApplicationEvaluation.Verdict parsedVerdict() {
			try {
				return ApplicationEvaluation.Verdict.valueOf(verdict == null ? "" : verdict.trim());
			}
			catch (IllegalArgumentException exception) {
				throw new com.socialcoisa.rede.shared.web.ApiException(422, "EVALUATION_VERDICT_INVALID",
					"O veredito precisa ser STRONG_YES, YES, MAYBE ou NO.");
			}
		}
	}

	record NoteView(String id, String applicationId, String authorId, String body, String createdAt) {

		static NoteView of(StageNote note) {
			return new NoteView(note.id() == null ? null : String.valueOf(note.id()),
				String.valueOf(note.applicationId()), note.authorId(), note.body(),
				note.createdAt().toString());
		}
	}

	// ---------- Modelos de processo seletivo ----------

	record ApplyTemplateRequest(String templateId) {
	}

	/** Aplica o modelo na vaga: semeia as etapas na ordem. Vaga com fluxo já configurado → 409. */
	@PostMapping("/templates/apply")
	List<StageView> applyTemplate(@PathVariable String jobId, @RequestBody ApplyTemplateRequest body,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return process.applyTemplate(jobId, user.id().value(), body.templateId()).stream()
			.map(StageView::of)
			.toList();
	}

	/** O modelo como a API devolve (compartilhado com o hub, que lista a biblioteca). */
	record TemplateView(String id, String name, List<String> stages, String createdAt, String updatedAt) {

		static TemplateView of(com.socialcoisa.rede.jobs.domain.SelectionProcessTemplate template) {
			return new TemplateView(template.id(), template.name(), template.stageNames(),
				template.createdAt().toString(), template.updatedAt().toString());
		}
	}

}