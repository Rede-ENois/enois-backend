package com.socialcoisa.rede.companies.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.companies.domain.MicroProjectProposal;
import com.socialcoisa.rede.companies.domain.ProposalStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "micro_project_proposals")
class MicroProjectProposalJpaEntity {

	@Id
	private String id;

	@Column(name = "project_id", nullable = false)
	private String projectId;

	@Column(name = "user_id", nullable = false)
	private String userId;

	@Column(nullable = false, length = 2000)
	private String message;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private ProposalStatus status;

	@Column(name = "proposed_at", nullable = false)
	private Instant proposedAt;

	@Column(name = "decided_at")
	private Instant decidedAt;

	@Column(name = "conversation_id")
	private String conversationId;

	protected MicroProjectProposalJpaEntity() {
	}

	static MicroProjectProposalJpaEntity fromDomain(MicroProjectProposal proposal) {
		MicroProjectProposalJpaEntity e = new MicroProjectProposalJpaEntity();
		e.id = proposal.id();
		e.projectId = proposal.projectId();
		e.userId = proposal.userId();
		e.message = proposal.message();
		e.status = proposal.status();
		e.proposedAt = proposal.proposedAt();
		e.decidedAt = proposal.decidedAt();
		e.conversationId = proposal.conversationId();
		return e;
	}

	MicroProjectProposal toDomain() {
		return MicroProjectProposal.restore(id, projectId, userId, message, status, proposedAt, decidedAt,
			conversationId);
	}
}
