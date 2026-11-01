package com.socialcoisa.rede.companies.domain;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Microprojeto: problema real publicado pela empresa, piloto pago de escopo limitado com
 * entregaveis e criterios de aceite definidos ANTES de comecar. O pagamento acontece fora da
 * plataforma — aqui vive o contrato de escopo e o andamento.
 */
public class MicroProject {

	private final String id;
	private final String companyId;
	private final String createdBy;
	private final String title;
	private final String problem;
	private final String scope;
	private final String deliverables;
	private final String acceptanceCriteria;
	private final String budget;
	private final LocalDate deadline;
	private final MicroProjectStatus status;
	private final String selectedProposalId;
	private final Instant createdAt;
	private final Instant updatedAt;

	private MicroProject(String id, String companyId, String createdBy, String title, String problem, String scope,
			String deliverables, String acceptanceCriteria, String budget, LocalDate deadline,
			MicroProjectStatus status, String selectedProposalId, Instant createdAt, Instant updatedAt) {
		this.id = id;
		this.companyId = companyId;
		this.createdBy = createdBy;
		this.title = title;
		this.problem = problem;
		this.scope = scope;
		this.deliverables = deliverables;
		this.acceptanceCriteria = acceptanceCriteria;
		this.budget = budget;
		this.deadline = deadline;
		this.status = status;
		this.selectedProposalId = selectedProposalId;
		this.createdAt = createdAt;
		this.updatedAt = updatedAt;
	}

	public static MicroProject create(String id, String companyId, String createdBy, String title, String problem,
			String scope, String deliverables, String acceptanceCriteria, String budget, LocalDate deadline,
			Instant now) {
		return new MicroProject(id, companyId, createdBy, title, problem, scope, deliverables, acceptanceCriteria,
			budget, deadline, MicroProjectStatus.OPEN, null, now, now);
	}

	public static MicroProject restore(String id, String companyId, String createdBy, String title, String problem,
			String scope, String deliverables, String acceptanceCriteria, String budget, LocalDate deadline,
			MicroProjectStatus status, String selectedProposalId, Instant createdAt, Instant updatedAt) {
		return new MicroProject(id, companyId, createdBy, title, problem, scope, deliverables, acceptanceCriteria,
			budget, deadline, status, selectedProposalId, createdAt, updatedAt);
	}

	public MicroProject edit(String title, String problem, String scope, String deliverables,
			String acceptanceCriteria, String budget, LocalDate deadline, Instant now) {
		return new MicroProject(id, companyId, createdBy, title, problem, scope, deliverables, acceptanceCriteria,
			budget, deadline, status, selectedProposalId, createdAt, now);
	}

	public MicroProject touch(Instant now) {
		return new MicroProject(id, companyId, createdBy, title, problem, scope, deliverables, acceptanceCriteria,
			budget, deadline, status, selectedProposalId, createdAt, now);
	}

	public MicroProject select(String proposalId, Instant now) {
		return new MicroProject(id, companyId, createdBy, title, problem, scope, deliverables, acceptanceCriteria,
			budget, deadline, MicroProjectStatus.IN_PROGRESS, proposalId, createdAt, now);
	}

	public MicroProject complete(Instant now) {
		return new MicroProject(id, companyId, createdBy, title, problem, scope, deliverables, acceptanceCriteria,
			budget, deadline, MicroProjectStatus.COMPLETED, selectedProposalId, createdAt, now);
	}

	public MicroProject cancel(Instant now) {
		return new MicroProject(id, companyId, createdBy, title, problem, scope, deliverables, acceptanceCriteria,
			budget, deadline, MicroProjectStatus.CANCELLED, selectedProposalId, createdAt, now);
	}

	public boolean isOpen() {
		return status == MicroProjectStatus.OPEN;
	}

	public String id() { return id; }
	public String companyId() { return companyId; }
	public String createdBy() { return createdBy; }
	public String title() { return title; }
	public String problem() { return problem; }
	public String scope() { return scope; }
	public String deliverables() { return deliverables; }
	public String acceptanceCriteria() { return acceptanceCriteria; }
	public String budget() { return budget; }
	public LocalDate deadline() { return deadline; }
	public MicroProjectStatus status() { return status; }
	public String selectedProposalId() { return selectedProposalId; }
	public Instant createdAt() { return createdAt; }
	public Instant updatedAt() { return updatedAt; }
}
