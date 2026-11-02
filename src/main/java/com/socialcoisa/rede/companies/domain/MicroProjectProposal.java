package com.socialcoisa.rede.companies.domain;

import java.time.Instant;

/** {@code conversationId}: the professional conversation the proposal opened (null on old ones). */
public record MicroProjectProposal(String id, String projectId, String userId, String message,
		ProposalStatus status, Instant proposedAt, Instant decidedAt, String conversationId) {

	public static MicroProjectProposal create(String id, String projectId, String userId, String message,
			String conversationId, Instant now) {
		return new MicroProjectProposal(id, projectId, userId, message, ProposalStatus.SENT, now, null,
			conversationId);
	}

	public static MicroProjectProposal restore(String id, String projectId, String userId, String message,
			ProposalStatus status, Instant proposedAt, Instant decidedAt, String conversationId) {
		return new MicroProjectProposal(id, projectId, userId, message, status, proposedAt, decidedAt,
			conversationId);
	}

	public MicroProjectProposal select(Instant at) {
		return new MicroProjectProposal(id, projectId, userId, message, ProposalStatus.SELECTED, proposedAt, at,
			conversationId);
	}

	public MicroProjectProposal decline(Instant at) {
		return new MicroProjectProposal(id, projectId, userId, message, ProposalStatus.DECLINED, proposedAt, at,
			conversationId);
	}

	public boolean isSent() {
		return status == ProposalStatus.SENT;
	}
}
