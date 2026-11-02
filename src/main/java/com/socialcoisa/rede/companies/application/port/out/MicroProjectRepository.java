package com.socialcoisa.rede.companies.application.port.out;

import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.companies.domain.MicroProject;
import com.socialcoisa.rede.companies.domain.MicroProjectProposal;
import com.socialcoisa.rede.companies.domain.ProposalStatus;

public interface MicroProjectRepository {

	MicroProject save(MicroProject project);

	Optional<MicroProject> findById(String id);

	Optional<MicroProject> findByIdForUpdate(String id);

	/** Vitrine: projetos abertos, mais recentes primeiro. */
	List<MicroProject> findOpen(int limit);

	/** Todos os projetos de uma empresa (painel e pagina publica). */
	List<MicroProject> findByCompany(String companyId, int limit);

	MicroProjectProposal saveProposal(MicroProjectProposal proposal);

	Optional<MicroProjectProposal> findProposalById(String id);

	boolean hasProposalsForUpdate(String projectId);

	boolean existsProposalByUser(String projectId, String userId);

	List<MicroProjectProposal> proposalsOfProject(String projectId);

	List<MicroProjectProposal> proposalsOfUser(String userId, int limit);

	long countByCompanyAndStatus(String companyId, com.socialcoisa.rede.companies.domain.MicroProjectStatus status);

	/** Open microprojects per company, in one grouped read. */
	java.util.Map<String, Long> openCountsByCompany(java.util.Collection<String> companyIds);

	com.socialcoisa.rede.companies.domain.MicroProjectReview saveReview(
			com.socialcoisa.rede.companies.domain.MicroProjectReview review);

	/** Latest reviews of a company, newest first (at most six). */
	List<com.socialcoisa.rede.companies.domain.MicroProjectReview> latestReviews(String companyId);
}
