package com.socialcoisa.rede.jobs.application;

import java.util.Optional;

import org.springframework.stereotype.Service;

import com.socialcoisa.rede.companies.domain.RecruitmentCapability;
import com.socialcoisa.rede.jobs.application.port.out.JobRepository;
import com.socialcoisa.rede.jobs.application.port.out.RecruitmentTeamReader;
import com.socialcoisa.rede.jobs.domain.Job;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Um lugar só para a pergunta "quem manda nesta vaga?".
 *
 * <p>Duas regras convivem: o AUTOR da vaga comanda sempre (fluxo antigo, intacto — vaga sem
 * página nunca consulta equipe) e, quando a vaga nasceu numa página, a EQUIPE de recrutamento
 * dela comanda segundo o papel de cada um, pela tabela de capabilities do módulo de páginas.
 * Quem não é autor e não tem a capability recebe o mesmo código de antes
 * (NOT_OWNER), então nenhuma resposta nova vaza para quem já não via.
 */
@Service
public class JobCommandAuthorization {

	private final JobRepository jobs;
	private final RecruitmentTeamReader team;

	public JobCommandAuthorization(JobRepository jobs, RecruitmentTeamReader team) {
		this.jobs = jobs;
		this.team = team;
	}

	/** A vaga se quem pede comanda ela para a capability, ou 403/404 com a mensagem do fluxo. */
	public Job requireCommander(String jobId, String requesterId, RecruitmentCapability capability,
			String notOwnerMessage) {
		Job job = jobs.findById(jobId)
			.orElseThrow(() -> new ApiException(404, "JOB_NOT_FOUND", "Vaga não encontrada."));
		return requireCommander(job, requesterId, capability, notOwnerMessage);
	}

	/** Variante com a vaga em mão (quando o chamador já a carregou ou a travou para update). */
	public Job requireCommander(Job job, String requesterId, RecruitmentCapability capability,
			String notOwnerMessage) {
		if (job.postedBy().equals(requesterId)) {
			return job;
		}
		String companyId = job.companyId();
		if (companyId != null && team.can(companyId, requesterId, capability)) {
			return job;
		}
		throw new ApiException(403, "NOT_OWNER", notOwnerMessage);
	}

	/** Consulta sem erro: quem pede comanda a vaga para a capability? */
	public boolean canCommand(Job job, String requesterId, RecruitmentCapability capability) {
		return job.postedBy().equals(requesterId)
			|| (job.companyId() != null && team.can(job.companyId(), requesterId, capability));
	}

	/** A vaga pelo id, sem checagem — para o chamador montar a própria mensagem. */
	public Optional<Job> job(String jobId) {
		return jobs.findById(jobId);
	}
}
