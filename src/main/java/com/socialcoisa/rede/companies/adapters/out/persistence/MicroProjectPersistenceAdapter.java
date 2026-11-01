package com.socialcoisa.rede.companies.adapters.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.companies.application.port.out.MicroProjectRepository;
import com.socialcoisa.rede.companies.domain.MicroProject;
import com.socialcoisa.rede.companies.domain.MicroProjectProposal;
import com.socialcoisa.rede.companies.domain.MicroProjectReview;
import com.socialcoisa.rede.companies.domain.MicroProjectStatus;
import com.socialcoisa.rede.companies.domain.ProposalStatus;

@Repository
class MicroProjectPersistenceAdapter implements MicroProjectRepository {

	private final SpringDataMicroProjectJpaRepository projects;
	private final SpringDataMicroProjectProposalJpaRepository proposals;
	private final SpringDataMicroProjectReviewJpaRepository reviews;

	MicroProjectPersistenceAdapter(SpringDataMicroProjectJpaRepository projects,
			SpringDataMicroProjectProposalJpaRepository proposals, SpringDataMicroProjectReviewJpaRepository reviews) {
		this.projects = projects;
		this.proposals = proposals;
		this.reviews = reviews;
	}

	@Override
	public MicroProject save(MicroProject project) {
		return projects.save(MicroProjectJpaEntity.fromDomain(project)).toDomain();
	}

	@Override
	public Optional<MicroProject> findById(String id) {
		return projects.findById(id).map(MicroProjectJpaEntity::toDomain);
	}

	@Override
	public Optional<MicroProject> findByIdForUpdate(String id) {
		return projects.findForUpdate(id).map(MicroProjectJpaEntity::toDomain);
	}

	@Override
	public List<MicroProject> findOpen(int limit) {
		return projects.findByStatusOrderByCreatedAtDesc(MicroProjectStatus.OPEN, PageRequest.of(0, Math.max(1, limit)))
			.stream().map(MicroProjectJpaEntity::toDomain).toList();
	}

	@Override
	public List<MicroProject> findByCompany(String companyId, int limit) {
		return projects.findTop50ByCompanyIdAndStatusNotOrderByCreatedAtDesc(companyId, MicroProjectStatus.CANCELLED).stream()
			.map(MicroProjectJpaEntity::toDomain).toList();
	}

	@Override
	public MicroProjectProposal saveProposal(MicroProjectProposal proposal) {
		return proposals.save(MicroProjectProposalJpaEntity.fromDomain(proposal)).toDomain();
	}

	@Override
	public Optional<MicroProjectProposal> findProposalById(String id) {
		return proposals.findById(id).map(MicroProjectProposalJpaEntity::toDomain);
	}

	@Override
	public boolean hasProposalsForUpdate(String projectId) {
		return !proposals.findForUpdate(projectId).isEmpty();
	}

	@Override
	public boolean existsProposalByUser(String projectId, String userId) {
		return proposals.existsByProjectIdAndUserId(projectId, userId);
	}

	@Override
	public List<MicroProjectProposal> proposalsOfProject(String projectId) {
		return proposals.findByProjectIdOrderByProposedAtAsc(projectId).stream()
			.map(MicroProjectProposalJpaEntity::toDomain).toList();
	}

	@Override
	public List<MicroProjectProposal> proposalsOfUser(String userId, int limit) {
		return proposals.findTop20ByUserIdOrderByProposedAtDesc(userId).stream()
			.map(MicroProjectProposalJpaEntity::toDomain).toList();
	}

	@Override
	public long countByCompanyAndStatus(String companyId, MicroProjectStatus status) {
		return projects.countByCompanyIdAndStatus(companyId, status);
	}

	@Override
	public java.util.Map<String, Long> openCountsByCompany(java.util.Collection<String> companyIds) {
		return companyIds.isEmpty() ? java.util.Map.of()
			: CompanyPersistenceAdapter.toCounts(projects.countOpenByCompanyIds(companyIds));
	}

	@Override
	public MicroProjectReview saveReview(MicroProjectReview review) {
		return reviews.save(MicroProjectReviewJpaEntity.fromDomain(review)).toDomain();
	}

	@Override
	public List<MicroProjectReview> latestReviews(String companyId) {
		return reviews.findTop6ByCompanyIdOrderByCreatedAtDesc(companyId).stream()
			.map(MicroProjectReviewJpaEntity::toDomain).toList();
	}
}
