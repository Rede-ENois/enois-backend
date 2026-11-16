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

import com.socialcoisa.rede.companies.domain.RecruitmentCapability;
import com.socialcoisa.rede.jobs.application.port.out.JobApplicationStatusNotifier;
import com.socialcoisa.rede.jobs.application.port.out.JobRepository;
import com.socialcoisa.rede.jobs.application.port.out.SelectionProcessRepository;
import com.socialcoisa.rede.jobs.application.port.out.SelectionProcessTemplateRepository;
import com.socialcoisa.rede.jobs.domain.Job;
import com.socialcoisa.rede.jobs.domain.JobApplication;
import com.socialcoisa.rede.jobs.domain.ApplicationEvaluation;
import com.socialcoisa.rede.jobs.domain.JobApplicationStatus;
import com.socialcoisa.rede.jobs.domain.SelectionProcessTemplate;
import com.socialcoisa.rede.jobs.domain.SelectionStage;
import com.socialcoisa.rede.jobs.domain.StageMovement;
import com.socialcoisa.rede.jobs.domain.StageMovementKind;
import com.socialcoisa.rede.jobs.domain.StageNote;
import com.socialcoisa.rede.shared.audit.AuditTrail;
import com.socialcoisa.rede.shared.web.ApiException;

import tools.jackson.databind.ObjectMapper;

/**
 * Pipeline de recrutamento de uma vaga: etapas do fluxo, movimento das candidaturas entre
 * etapas e notas internas da equipe.
 *
 * <p>Autorização: o autor da vaga comanda o processo; quando a vaga nasceu numa página
 * empresarial, a equipe de recrutamento dela comanda segundo o papel — configurar fluxo pede
 * CONFIGURE_PROCESS, mover e anotar pedem TRIAGE (tudo resolvido por {@link JobCommandAuthorization}).
 * Tudo validado aqui no backend; o front apenas mostra.
 *
 * <p>A etapa atual de uma candidatura é o ÚLTIMO movimento dela — nunca um campo solto, para
 * não existir segunda fonte de verdade. Candidatura sem movimento está na entrada do fluxo
 * (a primeira etapa, quando configurada).
 */
@Service
public class SelectionProcessService {

	/** Teto do movimento em lote: além disso a seleção foi feita errada, e não se adivinha. */
	public static final int MAX_BATCH_SIZE = 200;

	private final JobRepository jobs;
	private final SelectionProcessRepository pipeline;
	private final SelectionProcessTemplateRepository templates;
	private final JobCommandAuthorization command;
	private final JobApplicationStatusNotifier statusNotifier;
	private final Clock clock;
	private final AuditTrail audit;
	private final ObjectMapper objectMapper;

	/** Ações da trilha de auditoria de recrutamento — a mesma trilha de todo o sistema. */
	public static final String AUDIT_MOVED = "RECRUITMENT_APPLICATION_MOVED";
	public static final String AUDIT_NOTED = "RECRUITMENT_APPLICATION_NOTED";
	public static final String AUDIT_EVALUATED = "RECRUITMENT_APPLICATION_EVALUATED";

	public SelectionProcessService(JobRepository jobs, SelectionProcessRepository pipeline,
			SelectionProcessTemplateRepository templates, JobCommandAuthorization command,
			JobApplicationStatusNotifier statusNotifier, Clock clock, AuditTrail audit, ObjectMapper objectMapper) {
		this.jobs = jobs;
		this.pipeline = pipeline;
		this.templates = templates;
		this.command = command;
		this.statusNotifier = statusNotifier;
		this.clock = clock;
		this.audit = audit;
		this.objectMapper = objectMapper;
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

	/**
	 * As etapas para montar o board — leitura (VIEW_CANDIDATES), não configuração. O board é
	 * visível a toda a equipe de recrutamento; gerenciar as etapas em si, não.
	 */
	public List<SelectionStage> stagesForBoard(String jobId, String requesterId) {
		requireCommander(jobId, requesterId, RecruitmentCapability.VIEW_CANDIDATES);
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
		Job job = requireCommander(jobId, requesterId, RecruitmentCapability.TRIAGE);
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
			recordAudit(AUDIT_MOVED, requesterId, applicationId,
				Map.of("kind", "REJECT", "fromStageId", String.valueOf(currentStageId)));
			// Reprovar pelo board tem de avisar o candidato igual à triagem pelo estado clássico: o
			// retorno que ele já recebe é o que o faz entender a decisão. O estado persistido acima é
			// a fonte; o aviso sai dele, pela mesma porta de notificação das outras mudanças.
			statusNotifier.notifyStatusChange(job, rejected(application, reason), JobApplicationStatus.NOT_SELECTED);
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
		StageMovement saved = pipeline.saveMovement(new StageMovement(null, Long.parseLong(applicationId), jobId,
			currentStageId, target.id(), effective, requesterId, note, clock.instant()));
		recordAudit(AUDIT_MOVED, requesterId, applicationId,
			Map.of("kind", effective.name(), "toStageId", target.id()));
		return saved;
	}

	public record PipelinePosition(String stageId, boolean rejected, java.time.Instant lastMovementAt) {
	}

	/**
	 * O Kanban da vaga: etapa atual de cada candidatura. A etapa é o destino do último
	 * movimento; sem movimento, a candidatura está na entrada do fluxo. Uma consulta de
	 * movimentos para a vaga inteira — não uma por candidato.
	 */
	public Map<Long, PipelinePosition> positionsOf(String jobId, String requesterId) {
		requireCommander(jobId, requesterId, RecruitmentCapability.VIEW_CANDIDATES);
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
		StageNote saved = pipeline.saveNote(new StageNote(null, Long.parseLong(applicationId), requesterId,
			body, clock.instant()));
		recordAudit(AUDIT_NOTED, requesterId, applicationId, Map.of("noteId", String.valueOf(saved.id())));
		return saved;
	}

	/** Notas internas: só o dono da vaga. Nunca expostas ao candidato por nenhum caminho daqui. */
	public List<StageNote> notes(String jobId, String applicationId, String requesterId) {
		requireOwnedApplication(jobId, applicationId, requesterId);
		return pipeline.notesOf(Long.parseLong(applicationId));
	}

	// ---------- Avaliações internas ----------

	/**
	 * Registra (ou refaz) a avaliação do próprio avaliador sobre a candidatura: uma por autor,
	 * com veredito, critérios e parecer. Quem move no pipeline (TRIAGE) pode avaliar; a posição
	 * é de quem a escreveu — nem o dono da vaga escreve pelos outros.
	 */
	@Transactional
	public ApplicationEvaluation evaluate(String jobId, String applicationId, String requesterId,
			Map<String, Integer> criteria, ApplicationEvaluation.Verdict verdict, String summary) {
		requireOwnedApplication(jobId, applicationId, requesterId);
		long id = Long.parseLong(applicationId);
		ApplicationEvaluation evaluation = ApplicationEvaluation.create(id, requesterId, criteria, verdict,
			summary, clock.instant());
		ApplicationEvaluation saved = pipeline.evaluationsOf(id).stream()
			.filter(existing -> existing.authorId().equals(requesterId))
			.findFirst()
			.map(existing -> existing.reevaluate(criteria, verdict, summary, clock.instant()))
			.map(pipeline::saveEvaluation)
			.orElseGet(() -> pipeline.saveEvaluation(evaluation));
		recordAudit(AUDIT_EVALUATED, requesterId, applicationId,
			Map.of("verdict", saved.verdict().name()));
		return saved;
	}

	/**
	 * A linha do tempo de auditoria da candidatura: os atos de recrutamento registrados na
	 * trilha única do sistema, mais novos primeiro. Quem vê candidatos lê; nada aqui vai ao
	 * candidato (o histórico dele são os movimentos, não a auditoria da equipe).
	 */
	public List<com.socialcoisa.rede.feed.application.port.out.AuditLogRepository.AuditRecord> auditTimeline(String jobId, String applicationId, String requesterId) {
		requireCommander(jobId, requesterId, RecruitmentCapability.VIEW_CANDIDATES);
		jobs.findApplicationById(applicationId)
			.filter(found -> found.jobId().equals(jobId))
			.orElseThrow(() -> new ApiException(404, "APPLICATION_NOT_FOUND", "Candidatura não encontrada."));
		return audit.recentForTarget("job_application:" + applicationId, 200);
	}

	private void recordAudit(String action, String actorId, String applicationId, Map<String, String> metadata) {
		audit.record(actorId, action, "job_application:" + applicationId, objectMapper.writeValueAsString(metadata));
	}

	/**
	 * A candidatura como ela fica DEPOIS da reprovação, para o aviso sair do estado novo e do
	 * retorno que o candidato tem direito a ler.
	 *
	 * <p>Reconstruída em vez de passar pelo {@code withStatus}: o pipeline já aceita reprovar em
	 * qualquer etapa, e a transição clássica {@code ADVANCED → NOT_SELECTED} não está na tabela do
	 * domínio — validar aqui transformaria a reprovação pelo board em erro, que é justamente o
	 * caminho que este aviso veio destravar.
	 */
	private static JobApplication rejected(JobApplication application, String reason) {
		if (application.status() == JobApplicationStatus.NOT_SELECTED) {
			return application;
		}
		return JobApplication.restore(application.id(), application.jobId(), application.userId(),
			application.message(), application.candidateSnapshot(), application.appliedAt(), application.answers(),
			JobApplicationStatus.NOT_SELECTED, reason, null);
	}

	/** Avaliações internas: leitura com quem vê candidatos. Nunca expostas ao candidato. */
	public List<ApplicationEvaluation> evaluations(String jobId, String applicationId, String requesterId) {
		requireCommander(jobId, requesterId, RecruitmentCapability.VIEW_CANDIDATES);
		jobs.findApplicationById(applicationId)
			.filter(found -> found.jobId().equals(jobId))
			.orElseThrow(() -> new ApiException(404, "APPLICATION_NOT_FOUND", "Candidatura não encontrada."));
		return pipeline.evaluationsOf(Long.parseLong(applicationId));
	}

	// ---------- Autorização ----------

	private void requireOwner(String jobId, String requesterId) {
		requireCommander(jobId, requesterId, RecruitmentCapability.CONFIGURE_PROCESS);
	}

	private Job requireCommander(String jobId, String requesterId, RecruitmentCapability capability) {
		return command.requireCommander(jobId, requesterId, capability,
			"Só quem publicou a vaga comanda o processo seletivo.");
	}

	private SelectionStage requireStageOfOwnedJob(String stageId, String requesterId) {
		SelectionStage stage = pipeline.findStageById(stageId)
			.orElseThrow(() -> new ApiException(404, "STAGE_NOT_FOUND", "Etapa não encontrada."));
		requireOwner(stage.jobId(), requesterId);
		return stage;
	}

	private void requireOwnedApplication(String jobId, String applicationId, String requesterId) {
		requireCommander(jobId, requesterId, RecruitmentCapability.TRIAGE);
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

	// ---------- Modelos de processo seletivo ----------

	/**
	 * Biblioteca pessoal: cada recrutador guarda os fluxos que usa e aplica por cópia na vaga.
	 * Modelo é do dono (não da página): a vaga que recebe a cópia passa pelo gate normal de
	 * CONFIGURE_PROCESS, então quem aplica precisa poder configurar AQUELA vaga.
	 */
	@Transactional
	public SelectionProcessTemplate createTemplate(String requesterId, String name, List<String> stageNames) {
		String cleanName = name == null ? "" : name.trim();
		templates.findByOwner(requesterId, 100).stream()
			.filter(existing -> existing.name().equalsIgnoreCase(cleanName))
			.findFirst()
			.ifPresent(existing -> {
				throw new ApiException(409, "TEMPLATE_NAME_TAKEN", "Você já tem um modelo com esse nome.");
			});
		SelectionProcessTemplate template = SelectionProcessTemplate.create(
			"ptmpl_" + java.util.UUID.randomUUID().toString().replace("-", ""), requesterId, cleanName,
			stageNames, clock.instant());
		return templates.save(template);
	}

	public List<SelectionProcessTemplate> templatesOf(String requesterId, int limit) {
		return templates.findByOwner(requesterId, limit);
	}

	@Transactional
	public void deleteTemplate(String templateId, String requesterId) {
		SelectionProcessTemplate template = templates.findById(templateId)
			.orElseThrow(() -> new ApiException(404, "TEMPLATE_NOT_FOUND", "Modelo não encontrado."));
		if (!template.ownerId().equals(requesterId)) {
			throw new ApiException(403, "NOT_OWNER", "Este modelo não é seu.");
		}
		templates.deleteById(templateId);
	}

	/**
	 * Aplica o modelo numa vaga: semeia as etapas NA ORDEM do modelo. Vaga que já tem fluxo
	 * recebe 409 — aplicar por cima misturaria dois fluxos; quem quer trocar apaga as etapas
	 * vazias primeiro (a regra de etapa ocupada protege quem já está no pipeline).
	 */
	@Transactional
	public List<SelectionStage> applyTemplate(String jobId, String requesterId, String templateId) {
		requireCommander(jobId, requesterId, RecruitmentCapability.CONFIGURE_PROCESS);
		SelectionProcessTemplate template = templates.findById(templateId)
			.orElseThrow(() -> new ApiException(404, "TEMPLATE_NOT_FOUND", "Modelo não encontrado."));
		List<SelectionStage> existing = pipeline.stagesOf(jobId);
		if (!existing.isEmpty()) {
			throw new ApiException(409, "PIPELINE_ALREADY_CONFIGURED",
				"Esta vaga já tem fluxo de etapas. Apague as etapas vazias antes de aplicar um modelo.");
		}
		int position = 0;
		for (String stageName : template.stageNames()) {
			pipeline.saveStage(SelectionStage.create(
				"stage_" + java.util.UUID.randomUUID().toString().replace("-", ""), jobId, stageName,
				position++, clock.instant()));
		}
		return pipeline.stagesOf(jobId);
	}

}
