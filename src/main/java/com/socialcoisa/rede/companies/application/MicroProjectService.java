package com.socialcoisa.rede.companies.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.companies.application.port.out.MicroProjectRepository;
import com.socialcoisa.rede.companies.domain.MicroProject;
import com.socialcoisa.rede.companies.domain.MicroProjectProposal;
import com.socialcoisa.rede.companies.domain.MicroProjectReview;
import com.socialcoisa.rede.corporate.application.CorporateProfileService;
import com.socialcoisa.rede.messaging.application.MessagingService;
import com.socialcoisa.rede.notifications.application.NotificationService;
import com.socialcoisa.rede.shared.blocks.BlockedUsersReader;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.TextFieldLimit;

/**
 * Marketplace de microprojetos: a empresa publica um problema real com entregaveis e criterios
 * de aceite; profissionais se candidatam; a empresa escolhe um (projeto IN_PROGRESS) e fecha
 * com o aceite. Pagamento acontece FORA da plataforma — aqui vive o contrato de escopo.
 */
@Service
public class MicroProjectService {

	private final MicroProjectRepository projects;
	private final CompanyService companyService;
	private final NotificationService notificationService;
	private final Clock clock;
	private final com.socialcoisa.rede.coin.application.CoinService coins;
	private final CorporateProfileService corporateProfiles;
	private final MessagingService messaging;
	private final BlockedUsersReader blockedUsers;

	public MicroProjectService(MicroProjectRepository projects, CompanyService companyService,
			NotificationService notificationService, Clock clock,
			com.socialcoisa.rede.coin.application.CoinService coins, CorporateProfileService corporateProfiles,
			MessagingService messaging, BlockedUsersReader blockedUsers) {
		this.projects = projects;
		this.companyService = companyService;
		this.notificationService = notificationService;
		this.clock = clock;
		this.coins = coins;
		this.corporateProfiles = corporateProfiles;
		this.messaging = messaging;
		this.blockedUsers = blockedUsers;
	}

	/**
	 * Bônus da plataforma para o profissional selecionado (plano C2): +200 faíscas por
	 * microprojeto concluído, teto de 3 por mês. Idempotente por projeto; falha aqui não
	 * desfaz o aceite.
	 */
	private void rewardProfessional(String professionalId, String projectId) {
		try {
			String mes = java.time.YearMonth.now(clock).toString();
			String prefix = "mpbonus:" + professionalId + ":" + mes + ":";
			if (coins.rewardCountByPrefix(prefix) >= 3) {
				return;
			}
			coins.reward(professionalId, coins.scaledReward(com.socialcoisa.rede.coin.application.CoinService.FAIS(200)), prefix + projectId, "Microprojeto concluído (bônus da plataforma)");
		} catch (RuntimeException ignored) {
			// o aceite já aconteceu; a faísca não pode ser motivo de erro para a empresa
		}
	}

	@Transactional
	public MicroProject publish(String companyId, String userId, String title, String problem, String scope,
			String deliverables, String acceptanceCriteria, String budget, LocalDate deadline) {
		CompanyAccess access = companyWithRole(companyId, userId);
		// Publicar e escrita na vitrine da empresa: sem papel nenhum, qualquer pessoa logada
		// publicaria um projeto publico na pagina dos outros.
		if (!access.hasRole()) {
			throw new ApiException(403, "NOT_COMPANY_MEMBER", "Voce nao faz parte desta pagina empresarial.");
		}
		validateFields(title, problem, scope, deliverables, acceptanceCriteria, budget);
		MicroProject project = MicroProject.create(
			"mproj_" + UUID.randomUUID().toString().replace("-", ""), access.company().id(), userId,
			title.trim(), problem.trim(),
			StringUtils.hasText(scope) ? scope.trim() : null,
			deliverables.trim(), acceptanceCriteria.trim(),
			StringUtils.hasText(budget) ? budget.trim() : null,
			deadline, clock.instant());
		MicroProject saved = projects.save(project);
		notificationService.create(access.company().ownerUserId(), "MICROPROJETO", userId, "Microprojeto publicado",
			"\"" + saved.title() + "\" esta aberto na pagina da empresa.", "/api/empresa/" + access.company().slug(),
			"Ver");
		companyService.notifyFollowers(access.company(), userId, "Novo microprojeto",
			access.company().name() + " publicou \"" + saved.title() + "\".");
		return saved;
	}

	private void validateFields(String title, String problem, String scope, String deliverables, String acceptanceCriteria, String budget) {
		// O teto e o da COLUNA (`micro_projects.title varchar(200)`).
		TextFieldLimit.check("COMPANY_FIELD_TOO_LONG", "titulo", title, 200);
		if (!StringUtils.hasText(title) || title.trim().length() < 8) {
			throw new ApiException(400, "INVALID_TITLE", "Titulo do microprojeto precisa de pelo menos 8 caracteres.");
		}
		if (!StringUtils.hasText(problem) || problem.trim().length() < 30) {
			throw new ApiException(400, "INVALID_PROBLEM",
				"Descreva o problema real em pelo menos 30 caracteres — e o que o piloto vai resolver.");
		}
		if (!StringUtils.hasText(deliverables)) {
			throw new ApiException(400, "INVALID_DELIVERABLES", "Entregaveis sao obrigatorios.");
		}
		if (!StringUtils.hasText(acceptanceCriteria)) {
			throw new ApiException(400, "INVALID_ACCEPTANCE", "Criterios de aceite sao obrigatorios.");
		}
		TextFieldLimit.check("COMPANY_FIELD_TOO_LONG", "problema", problem, 4000);
		TextFieldLimit.check("COMPANY_FIELD_TOO_LONG", "escopo", scope, 4000);
		TextFieldLimit.check("COMPANY_FIELD_TOO_LONG", "entregaveis", deliverables, 4000);
		TextFieldLimit.check("COMPANY_FIELD_TOO_LONG", "criterios de aceite", acceptanceCriteria, 4000);
		TextFieldLimit.check("COMPANY_FIELD_TOO_LONG", "orcamento", budget, 160);
	}

	private MicroProject lockedProject(String id) {
		return projects.findByIdForUpdate(id)
			.orElseThrow(() -> new ApiException(404, "PROJECT_NOT_FOUND", "Microprojeto nao encontrado."));
	}

	private void requireEditable(MicroProject project, String userId) {
		requireCompanyRole(project, userId);
		if (!project.isOpen() || projects.hasProposalsForUpdate(project.id())) {
			throw new ApiException(409, "PROJECT_NOT_EDITABLE",
				"Apenas microprojetos abertos sem candidaturas podem ser editados ou removidos.");
		}
	}

	@Transactional
	public MicroProject edit(String projectId, String userId, String title, String problem, String scope,
			String deliverables, String acceptanceCriteria, String budget, LocalDate deadline) {
		MicroProject project = lockedProject(projectId);
		requireEditable(project, userId);
		validateFields(title, problem, scope, deliverables, acceptanceCriteria, budget);
		return projects.save(project.edit(title.trim(), problem.trim(),
			StringUtils.hasText(scope) ? scope.trim() : null, deliverables.trim(), acceptanceCriteria.trim(),
			StringUtils.hasText(budget) ? budget.trim() : null, deadline, clock.instant()));
	}

	@Transactional
	public MicroProject remove(String projectId, String userId) {
		MicroProject project = lockedProject(projectId);
		requireEditable(project, userId);
		return projects.save(project.cancel(clock.instant()));
	}

	@Transactional(readOnly = true)
	public List<MicroProject> openProjects(int limit) {
		return projects.findOpen(limit);
	}

	@Transactional(readOnly = true)
	public List<MicroProject> byCompany(String companyId) {
		return projects.findByCompany(companyId, 50);
	}

	/** The proposals a person sent, to tell each project card where that person stands. */
	@Transactional(readOnly = true)
	public List<MicroProjectProposal> proposalsOf(String userId) {
		return projects.proposalsOfUser(userId, 200);
	}

	@Transactional(readOnly = true)
	public MicroProject project(String projectId) {
		return projects.findById(projectId)
			.orElseThrow(() -> new ApiException(404, "PROJECT_NOT_FOUND", "Microprojeto nao encontrado."));
	}

	/**
	 * A proposal leaves from the proposer's PROFESSIONAL identity and lands in a professional
	 * conversation with the page owner (opened or reused), where the message is posted; the
	 * conversation id is kept on the proposal so the team can open it.
	 */
	@Transactional
	public MicroProjectProposal propose(String projectId, String userId, String message) {
		if (!StringUtils.hasText(message) || message.trim().length() < 20) {
			throw new ApiException(400, "INVALID_PROPOSAL",
				"Conte sua proposta em pelo menos 20 caracteres.");
		}
		TextFieldLimit.check("COMPANY_FIELD_TOO_LONG", "proposta", message.trim(), 2000);
		MicroProject project = lockedProject(projectId);
		if (!project.isOpen()) {
			throw new ApiException(409, "PROJECT_NOT_OPEN", "Este microprojeto nao esta mais aberto.");
		}
		CompanyAccess access = companyWithRole(project.companyId(), userId);
		if (access.hasRole()) {
			throw new ApiException(403, "OWN_COMPANY_PROJECT", "Voce nao pode se candidatar a projeto da propria pagina.");
		}
		if (projects.existsProposalByUser(projectId, userId)) {
			throw new ApiException(409, "ALREADY_PROPOSED", "Voce ja se candidatou a este microprojeto.");
		}
		corporateProfiles.requireActive(userId);
		String ownerId = access.company().ownerUserId();
		if (blockedUsers.hiddenIdsFor(userId).contains(ownerId)) {
			throw new ApiException(403, "USER_BLOCKED", "Não é possível conversar com este usuário.");
		}
		String conversationId = messaging.freelanceConversationBetween(userId, ownerId,
			"Microprojeto: " + project.title());
		messaging.sendMessage(conversationId, userId,
			"Proposta para o microprojeto \"" + project.title() + "\"\n\n" + message.trim());
		MicroProjectProposal saved = projects.saveProposal(MicroProjectProposal.create(
			"mprop_" + UUID.randomUUID().toString().replace("-", ""), projectId, userId, message.trim(),
			conversationId, clock.instant()));
		notificationService.create(project.createdBy(), "MICROPROJETO", userId, "Nova candidatura",
			"Alguem se candidatou ao microprojeto \"" + project.title() + "\".", "/api/empresa/" + access.company().slug(),
			"Ver");
		return saved;
	}

	@Transactional(readOnly = true)
	public List<MicroProjectProposal> proposals(String projectId, String userId) {
		MicroProject project = project(projectId);
		requireCompanyRole(project, userId);
		return projects.proposalsOfProject(projectId);
	}

	@Transactional
	public MicroProject selectProposal(String projectId, String proposalId, String userId) {
		MicroProject project = lockedProject(projectId);
		requireCompanyRole(project, userId);
		if (!project.isOpen()) {
			throw new ApiException(409, "PROJECT_NOT_OPEN", "Este microprojeto nao esta mais aberto.");
		}
		MicroProjectProposal proposal = projects.findProposalById(proposalId)
			.orElseThrow(() -> new ApiException(404, "PROPOSAL_NOT_FOUND", "Candidatura nao encontrada."));
		if (!proposal.projectId().equals(projectId) || !proposal.isSent()) {
			throw new ApiException(409, "PROPOSAL_NOT_AVAILABLE", "Esta candidatura nao esta mais disponivel.");
		}
		MicroProject selected = project.select(proposalId, clock.instant());
		projects.save(selected);
		projects.saveProposal(proposal.select(clock.instant()));
		declineOthers(projectId, proposalId, clock.instant());
		notificationService.create(proposal.userId(), "MICROPROJETO", userId, "Voce foi selecionado",
			"Sua candidatura ao microprojeto \"" + project.title() + "\" foi escolhida. Fale com a empresa para alinhar o piloto.",
			"/api/empresa/" + companySlug(project.companyId()), "Ver");
		return selected;
	}

	/**
	 * Completes the project. {@code rating} (1..5, optional) with an optional {@code text} (up to
	 * 500) becomes the company's one review of the selected professional.
	 */
	@Transactional
	public MicroProject complete(String projectId, String userId, Integer rating, String text) {
		MicroProject project = lockedProject(projectId);
		requireCompanyRole(project, userId);
		if (project.status() != com.socialcoisa.rede.companies.domain.MicroProjectStatus.IN_PROGRESS) {
			throw new ApiException(409, "PROJECT_NOT_IN_PROGRESS", "So microprojeto em andamento pode ser concluido.");
		}
		String reviewText = StringUtils.hasText(text) ? text.trim() : null;
		// A text without a rating is not a review either: the client is told, never silently dropped.
		if (rating == null ? reviewText != null : rating < 1 || rating > 5) {
			throw new ApiException(400, "INVALID_RATING", "Avalie com uma nota de 1 a 5.");
		}
		TextFieldLimit.check("COMPANY_FIELD_TOO_LONG", "avaliacao", reviewText, 500);
		MicroProject completed = projects.save(project.complete(clock.instant()));
		if (project.selectedProposalId() != null) {
			projects.findProposalById(project.selectedProposalId()).ifPresent(proposal -> {
				if (rating != null) {
					projects.saveReview(new MicroProjectReview(project.id(), project.companyId(), proposal.userId(),
						rating, reviewText, clock.instant()));
				}
				notificationService.create(
					proposal.userId(), "MICROPROJETO", userId, "Microprojeto concluido",
					"A empresa confirmou o aceite dos entregaveis de \"" + project.title() + "\".",
					"/api/empresa/" + companySlug(project.companyId()), "Ver");
				rewardProfessional(proposal.userId(), project.id());
			});
		}
		return completed;
	}

	@Transactional(readOnly = true)
	public long countByStatus(String companyId, com.socialcoisa.rede.companies.domain.MicroProjectStatus status) {
		return projects.countByCompanyAndStatus(companyId, status);
	}

	@Transactional(readOnly = true)
	public java.util.Map<String, Long> openCountsByCompany(java.util.Collection<String> companyIds) {
		return projects.openCountsByCompany(companyIds);
	}

	@Transactional(readOnly = true)
	public List<MicroProjectReview> latestReviews(String companyId) {
		return projects.latestReviews(companyId);
	}

	private void declineOthers(String projectId, String selectedProposalId, Instant at) {
		for (MicroProjectProposal proposal : projects.proposalsOfProject(projectId)) {
			if (proposal.isSent() && !proposal.id().equals(selectedProposalId)) {
				projects.saveProposal(proposal.decline(at));
			}
		}
	}

	private void requireCompanyRole(MicroProject project, String userId) {
		companyService.requireRole(companyService.company(project.companyId()), userId,
			com.socialcoisa.rede.companies.domain.CompanyRole.EDITOR);
	}

	private String companySlug(String companyId) {
		return companyService.company(companyId).slug();
	}

	/** Empresa + papel do usuario nela (null = sem papel). */
	public record CompanyAccess(com.socialcoisa.rede.companies.domain.Company company,
			com.socialcoisa.rede.companies.domain.CompanyRole role) {
		public boolean hasRole() {
			return role != null;
		}
	}

	private CompanyAccess companyWithRole(String companyId, String userId) {
		var company = companyService.company(companyId);
		return new CompanyAccess(company, companyService.roleOf(companyId, userId));
	}
}
