package com.socialcoisa.rede.jobs.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.socialcoisa.rede.companies.domain.RecruitmentCapability;
import com.socialcoisa.rede.jobs.application.port.out.JobRepository;
import com.socialcoisa.rede.jobs.domain.CandidateScore;
import com.socialcoisa.rede.jobs.domain.Job;
import com.socialcoisa.rede.jobs.domain.JobApplication;
import com.socialcoisa.rede.jobs.domain.JobApplicationStatus;
import com.socialcoisa.rede.jobs.domain.StageMovement;
import com.socialcoisa.rede.jobs.domain.StageMovementKind;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Triagem: score explicável de cada candidatura e ações em lote.
 *
 * <p>O score sai do RETRATO congelado na candidatura (habilidades e verificação do dia em que a
 * pessoa se candidatou), comparado com o que a vaga pede hoje. Ninguém é pontuado pelo que virou
 * depois nem pelo que declarou em outro lugar.
 *
 * <p>O lote confere todas as candidaturas antes de gravar: se alguma já foi reprovada ou não é
 * desta vaga, nada é movido e a resposta diz qual barrou o lote. O que o fluxo recusa no meio
 * (candidatura já na etapa de destino, por exemplo) aparece no resultado item a item — nunca some
 * pela metade sem aviso. Quem move em lote precisa de TRIAGE, igual ao movimento individual.
 */
@Service
public class CandidateTriageService {

	private final JobService jobs;
	private final JobRepository repository;
	private final SelectionProcessService process;
	private final JobCommandAuthorization command;
	private final TransactionTemplate itemTransaction;
	private final ObjectMapper objectMapper;

	public CandidateTriageService(JobService jobs, JobRepository repository, SelectionProcessService process,
			JobCommandAuthorization command, PlatformTransactionManager transactions, ObjectMapper objectMapper) {
		this.jobs = jobs;
		this.repository = repository;
		this.process = process;
		this.command = command;
		this.itemTransaction = new TransactionTemplate(transactions);
		this.itemTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.objectMapper = objectMapper;
	}

	/** Score de uma candidatura já carregada, para quem tem VIEW_CANDIDATES. */
	public CandidateScore scoreOf(Job job, JobApplication application) {
		JsonNode snapshot = snapshot(application);
		List<String> skills = new ArrayList<>();
		snapshot.path("skills").forEach(node -> {
			if (!node.asText("").isBlank()) {
				skills.add(node.asText());
			}
		});
		return CandidateScore.of(skills, snapshot.path("identityVerified").asBoolean(false), job);
	}

	/** Candidatura da triagem com score e o nome do retrato congelado (o que o card mostra). */
	public record Ranked(JobApplication application, CandidateScore score, String candidateName) {
	}

	/**
	 * Candidaturas da vaga com score, da mais aderente para a menos. Candidatura sem sinal de
	 * comparação vai para o fim, entre as demais sem score, pela ordem de chegada — nunca como
	 * "a pior", porque não houve comparação.
	 */
	public List<Ranked> ranked(String jobId, String requesterId, int limit) {
		Job job = command.requireCommander(jobId, requesterId, RecruitmentCapability.VIEW_CANDIDATES,
			"Somente quem publicou a vaga ou a equipe dela pode ver a triagem das candidaturas.");
		List<Ranked> ranked = new ArrayList<>();
		for (JobApplication application : jobs.applicationsOf(jobId, requesterId, limit)) {
			ranked.add(new Ranked(application, scoreOf(job, application), snapshot(application).path("name").asText("")));
		}
		ranked.sort(Comparator
			.comparing((Ranked entry) -> !entry.score().available())
			.thenComparingInt(entry -> -entry.score().total()));
		return List.copyOf(ranked);
	}

	/** Scores de todas as candidaturas da vaga, por id — para o board anexar sem N consultas. */
	public Map<String, CandidateScore> scoresOf(Job job, List<JobApplication> applications) {
		Map<String, CandidateScore> scores = new LinkedHashMap<>();
		for (JobApplication application : applications) {
			if (application.id() != null) {
				scores.put(application.id(), scoreOf(job, application));
			}
		}
		return scores;
	}

	/** Desfecho de um item do lote: movido, ou a recusa e o porquê. */
	public record BatchOutcome(String applicationId, StageMovement movement, String errorCode, String errorMessage) {

		static BatchOutcome moved(String applicationId, StageMovement movement) {
			return new BatchOutcome(applicationId, movement, null, null);
		}

		static BatchOutcome refused(String applicationId, ApiException failure) {
			return new BatchOutcome(applicationId, null, failure.code(), failure.getMessage());
		}
	}

	/**
	 * Move várias candidaturas para a mesma etapa (ou reprova todas com o mesmo motivo). Cada
	 * item passa pelo MESMO caminho do movimento individual (gate de TRIAGE, regra de etapa,
	 * retorno obrigatório na reprovação); o resultado devolve o desfecho de cada uma, com erro
	 * explícito por item — o recrutador vê exatamente o que entrou e o que recusou.
	 */
	public List<BatchOutcome> moveBatch(String jobId, String requesterId, List<String> applicationIds,
			String toStageId, StageMovementKind kind, String note) {
		command.requireCommander(jobId, requesterId, RecruitmentCapability.TRIAGE,
			"Somente quem publicou a vaga ou a equipe dela pode conduzir a seleção.");
		List<String> ids = distinctIds(applicationIds);
		Map<String, JobApplicationStatus> status = statusOf(jobId, ids);
		if (kind == StageMovementKind.REJECT && (note == null || note.isBlank())) {
			throw new ApiException(400, "FEEDBACK_REQUIRED",
				"Conte por que as candidaturas não seguiram — o candidato recebe esse retorno.");
		}
		status.forEach((id, current) -> {
			if (current == JobApplicationStatus.NOT_SELECTED) {
				throw new ApiException(409, "APPLICATION_REJECTED",
					"A candidatura " + id + " foi reprovada e não se move no pipeline.");
			}
		});
		return List.copyOf(outcomesOf(jobId, requesterId, ids, toStageId, kind, note));
	}

	/**
	 * Um item de cada vez, em transação própria: a recusa de uma candidatura não pode desfazer o
	 * movimento das anteriores. Transação de quem chamou é suspensa aqui de propósito.
	 */
	private List<BatchOutcome> outcomesOf(String jobId, String requesterId, List<String> ids,
			String toStageId, StageMovementKind kind, String note) {
		List<BatchOutcome> outcomes = new ArrayList<>();
		for (String id : ids) {
			try {
				StageMovement movement = itemTransaction.execute(
					state -> process.moveApplication(jobId, id, requesterId, toStageId, kind, note));
				outcomes.add(BatchOutcome.moved(id, movement));
			}
			catch (ApiException failure) {
				outcomes.add(BatchOutcome.refused(id, failure));
			}
		}
		return outcomes;
	}

	private List<String> distinctIds(List<String> applicationIds) {
		if (applicationIds == null || applicationIds.isEmpty()) {
			throw new ApiException(400, "NO_APPLICATIONS_SELECTED", "Selecione ao menos uma candidatura.");
		}
		List<String> ids = applicationIds.stream()
			.filter(id -> id != null && !id.isBlank())
			.map(String::trim)
			.distinct()
			.toList();
		if (ids.isEmpty()) {
			throw new ApiException(400, "NO_APPLICATIONS_SELECTED", "Selecione ao menos uma candidatura.");
		}
		if (ids.size() > SelectionProcessService.MAX_BATCH_SIZE) {
			throw new ApiException(422, "BATCH_TOO_LARGE",
				"Mova até " + SelectionProcessService.MAX_BATCH_SIZE + " candidaturas por vez.");
		}
		return ids;
	}

	private Map<String, JobApplicationStatus> statusOf(String jobId, List<String> ids) {
		Map<String, JobApplicationStatus> status = new LinkedHashMap<>();
		for (String id : ids) {
			JobApplication application = repository.findApplicationById(id)
				.filter(found -> found.jobId().equals(jobId))
				.orElseThrow(() -> new ApiException(404, "APPLICATION_NOT_FOUND",
					"Candidatura " + id + " não encontrada nesta vaga."));
			status.put(id, application.status());
		}
		return status;
	}

	private JsonNode snapshot(JobApplication application) {
		try {
			return objectMapper.readTree(
				application.candidateSnapshot() == null ? "{}" : application.candidateSnapshot());
		}
		catch (Exception exception) {
			return objectMapper.createObjectNode();
		}
	}
}
