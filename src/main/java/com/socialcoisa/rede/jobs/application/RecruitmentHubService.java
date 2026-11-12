package com.socialcoisa.rede.jobs.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.socialcoisa.rede.jobs.application.port.out.JobRepository;
import com.socialcoisa.rede.jobs.application.port.out.SelectionProcessRepository;
import com.socialcoisa.rede.jobs.domain.Job;
import com.socialcoisa.rede.jobs.domain.JobApplicationStatus;
import com.socialcoisa.rede.jobs.domain.JobStatus;
import com.socialcoisa.rede.jobs.domain.SelectionStage;
import com.socialcoisa.rede.jobs.domain.StageMovement;
import com.socialcoisa.rede.jobs.domain.StageMovementKind;

/**
 * Central de recrutamento: as vagas de quem recruta, com os números que decidem o dia —
 * quantas candidaturas, quantas esperando triagem, quantas em processo, quantas reprovadas.
 *
 * <p>Três consultas em lote (vagas, contagem de reprovadas por vaga, último movimento por
 * candidatura) — nunca uma por candidatura. "Aguardando triagem" é a candidatura que ninguém
 * moveu e que ainda não foi reprovada: o mesmo critério do board.
 */
@Service
public class RecruitmentHubService {

	private final JobRepository jobs;
	private final SelectionProcessRepository pipeline;

	public RecruitmentHubService(JobRepository jobs, SelectionProcessRepository pipeline) {
		this.jobs = jobs;
		this.pipeline = pipeline;
	}

	public record JobSummary(String jobId, String title, String company, String status, int total,
			int awaitingTriage, int inProcess, int rejected, int stages) {
	}

	public record HubView(List<JobSummary> jobs, Totals totals) {
	}

	public record Totals(int jobs, int openJobs, int candidates, int awaitingTriage, int inProcess,
			int rejected) {
	}

	public HubView hubOf(String recruiterId, int limit) {
		List<Job> mine = jobs.findByPostedBy(recruiterId, java.time.Instant.parse("9999-12-31T23:59:59Z"),
			Math.clamp(limit, 1, 100));
		Set<String> jobIds = mine.stream().map(Job::id).collect(java.util.stream.Collectors.toSet());
		if (jobIds.isEmpty()) {
			return new HubView(List.of(), new Totals(0, 0, 0, 0, 0, 0));
		}

		Map<String, Integer> totals = jobs.countApplicationsByJobIds(jobIds);
		Map<String, Integer> rejectedByJob = jobs.countApplicationsByJobIdsAndStatus(jobIds,
			JobApplicationStatus.NOT_SELECTED);
		// Último movimento por candidatura, da vaga inteira — é o que define "já triada".
		Map<String, Integer> movedAwayByJob = new java.util.HashMap<>();
		for (StageMovement movement : pipeline.latestMovementsOfJobs(jobIds)) {
			if (movement.kind() != StageMovementKind.REJECT) {
				movedAwayByJob.merge(movement.jobId(), 1, Integer::sum);
			}
		}

		List<JobSummary> summaries = new ArrayList<>();
		int totalCandidates = 0;
		int totalAwaiting = 0;
		int totalInProcess = 0;
		int totalRejected = 0;
		int openJobs = 0;
		for (Job job : mine) {
			int total = totals.getOrDefault(job.id(), 0);
			int rejected = rejectedByJob.getOrDefault(job.id(), 0);
			// Candidatura que já tem movimento saiu da triagem; sem movimento, espera.
			int movedAway = movedAwayByJob.getOrDefault(job.id(), 0);
			int awaiting = Math.max(0, total - rejected - movedAway);
			int inProcess = Math.max(0, total - rejected - awaiting);
			List<SelectionStage> stages = pipeline.stagesOf(job.id());
			summaries.add(new JobSummary(job.id(), job.title(), job.company(), job.status().name(), total,
				awaiting, inProcess, rejected, stages.size()));
			totalCandidates += total;
			totalAwaiting += awaiting;
			totalInProcess += inProcess;
			totalRejected += rejected;
			if (job.status() == JobStatus.OPEN) {
				openJobs++;
			}
		}
		summaries.sort(Comparator.comparingInt(JobSummary::awaitingTriage).reversed()
			.thenComparing(JobSummary::jobId));
		return new HubView(summaries, new Totals(summaries.size(), openJobs, totalCandidates, totalAwaiting,
			totalInProcess, totalRejected));
	}

}