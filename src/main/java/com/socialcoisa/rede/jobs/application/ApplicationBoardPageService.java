package com.socialcoisa.rede.jobs.application;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.socialcoisa.rede.jobs.application.port.out.JobRepository;
import com.socialcoisa.rede.jobs.application.port.out.SelectionProcessRepository;
import com.socialcoisa.rede.jobs.domain.Job;
import com.socialcoisa.rede.jobs.domain.JobApplication;
import com.socialcoisa.rede.jobs.domain.JobApplicationStatus;
import com.socialcoisa.rede.jobs.domain.SelectionStage;
import com.socialcoisa.rede.jobs.domain.StageMovement;
import com.socialcoisa.rede.jobs.domain.StageMovementKind;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Listagem paginada das candidaturas de uma vaga, para a tabela e as colunas do board.
 *
 * <p>Antes o board trazia a vaga inteira e cortava na memória (teto de 1000). Numa vaga com
 * milhares de candidaturas isso carrega tudo a cada rolagem. Aqui a página sai do banco por
 * cursor keyset — (applied_at, id), do mais recente para o mais antigo — com o filtro de estado
 * e a busca aplicados na consulta. A etapa só é buscada para as linhas da página.
 */
@Service
public class ApplicationBoardPageService {

	/** Teto por página: a tabela não precisa de mais, e o custo de leitura fica previsível. */
	public static final int MAX_PAGE_SIZE = 100;

	private final JobRepository jobs;
	private final SelectionProcessRepository pipeline;

	public ApplicationBoardPageService(JobRepository jobs, SelectionProcessRepository pipeline) {
		this.jobs = jobs;
		this.pipeline = pipeline;
	}

	/** Uma linha da página: a candidatura e onde ela está no fluxo. */
	public record BoardRow(JobApplication application, SelectionProcessService.PipelinePosition position) {
	}

	/**
	 * Uma página de candidaturas da vaga. {@code cursor} é o par da última linha devolvida; a
	 * próxima página começa depois dele.
	 */
	public record BoardPage(List<BoardRow> rows, Instant lastAppliedAt, Long lastId) {
	}

	public BoardPage page(String jobId, String requesterId, Instant beforeAppliedAt, Long beforeId,
			JobApplicationStatus status, String search, int limit) {
		// A autorização é a mesma do board e da Central: só o autor da vaga enxerga quem se
		// candidatou — checada no backend, nunca a partir do que o cliente manda.
		Job job = jobs.findById(jobId)
			.orElseThrow(() -> new ApiException(404, "JOB_NOT_FOUND", "Vaga não encontrada."));
		if (!job.postedBy().equals(requesterId)) {
			throw new ApiException(403, "NOT_OWNER", "Só quem publicou a vaga vê as candidaturas recebidas.");
		}
		int size = Math.clamp(limit, 1, MAX_PAGE_SIZE);
		List<JobApplication> applications = jobs.findApplicationsByJobPage(jobId, beforeAppliedAt, beforeId,
			status, search, size);
		if (applications.isEmpty()) {
			return new BoardPage(List.of(), null, null);
		}
		Map<Long, SelectionProcessService.PipelinePosition> positions = positionsOf(jobId, applications);
		List<BoardRow> rows = applications.stream()
			.map(application -> new BoardRow(application, positions.get(Long.valueOf(application.id()))))
			.toList();
		JobApplication last = applications.get(applications.size() - 1);
		return new BoardPage(rows, last.appliedAt(), Long.valueOf(last.id()));
	}

	/**
	 * Etapa de cada candidatura da página. Duas consultas no total (etapas do fluxo e último
	 * movimento das linhas pedidas), não uma por candidatura. Candidatura sem movimento está na
	 * entrada do fluxo — o mesmo critério do board completo.
	 */
	private Map<Long, SelectionProcessService.PipelinePosition> positionsOf(String jobId,
			List<JobApplication> applications) {
		List<SelectionStage> stages = pipeline.stagesOf(jobId);
		String entry = stages.isEmpty() ? null : stages.get(0).id();
		Map<Long, SelectionProcessService.PipelinePosition> positions = new LinkedHashMap<>();
		List<Long> ids = applications.stream().map(application -> Long.valueOf(application.id())).toList();
		for (StageMovement movement : pipeline.latestMovementsOf(ids)) {
			positions.put(movement.applicationId(), new SelectionProcessService.PipelinePosition(
				movement.toStageId(), movement.kind() == StageMovementKind.REJECT, movement.movedAt()));
		}
		for (JobApplication application : applications) {
			Long id = Long.valueOf(application.id());
			positions.putIfAbsent(id, new SelectionProcessService.PipelinePosition(entry,
				application.status() == JobApplicationStatus.NOT_SELECTED, null));
		}
		return positions;
	}

}
