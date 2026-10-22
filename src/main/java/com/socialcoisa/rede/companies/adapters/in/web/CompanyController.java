package com.socialcoisa.rede.companies.adapters.in.web;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.companies.application.CompanyService;
import com.socialcoisa.rede.companies.application.MicroProjectService;
import com.socialcoisa.rede.companies.domain.Company;
import com.socialcoisa.rede.companies.domain.CompanyMember;
import com.socialcoisa.rede.companies.domain.CompanyRole;
import com.socialcoisa.rede.companies.domain.MicroProject;
import com.socialcoisa.rede.companies.domain.MicroProjectProposal;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * Paginas empresariais: leitura publica (detalhe + microprojetos abertos) e operacoes da
 * equipe (criar/editar pagina, membros, publicar microprojeto, escolher candidato, concluir).
 * Autorizacao por papel no backend, sempre.
 */
@RestController
class CompanyController {

	private final CompanyService companies;
	private final MicroProjectService microProjects;
	private final CurrentUserResolver currentUserResolver;

	CompanyController(CompanyService companies, MicroProjectService microProjects,
			CurrentUserResolver currentUserResolver) {
		this.companies = companies;
		this.microProjects = microProjects;
		this.currentUserResolver = currentUserResolver;
	}

	// --- leitura publica ---

	@GetMapping("/empresas")
	List<CompanySummaryView> list(@RequestParam(defaultValue = "30") int limit) {
		// Listagem por enquanto via paginas de quem publica: o diretorio derivado de vagas
		// (/jobs/companies) cobre descoberta; aqui ficam as paginas empresariais criadas.
		return companies.listAll(publicListLimit(limit)).stream().map(CompanySummaryView::from).toList();
	}

	@GetMapping("/empresa/{slug}")
	CompanyDetailView bySlug(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String slug) {
		Company company = companies.bySlug(slug);
		CurrentUser viewer = resolveSafely(userId, sessionToken);
		CompanyRole role = viewer == null ? null : companies.roleOf(company.id(), viewer.id().value());
		return new CompanyDetailView(company.id(), company.slug(), company.name(), company.description(),
			company.sector(), company.size(), company.location(), company.website(), company.logo(), company.cover(),
			role == null ? null : role.name(),
			microProjects.byCompany(company.id()).stream().map(MicroProjectView::from).toList());
	}

	// --- equipe ---

	@PostMapping("/empresas")
	Map<String, Object> create(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestBody CreateCompanyRequest request) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		Company created = companies.create(user.id().value(), request.name(), request.description(),
			request.sector(), request.size(), request.location(), request.website());
		return Map.of("id", created.id(), "slug", created.slug(), "name", created.name());
	}

	@PatchMapping("/empresas/{companyId}")
	CompanySummaryView update(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String companyId, @RequestBody UpdateCompanyRequest request) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		Company updated = companies.update(companyId, user.id().value(), request.name(), request.description(),
			request.sector(), request.size(), request.location(), request.website(), request.logo(),
			request.cover());
		return CompanySummaryView.from(updated);
	}

	@GetMapping("/empresas/mine")
	List<CompanySummaryView> mine(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		return companies.ownedBy(user.id().value()).stream().map(CompanySummaryView::from).toList();
	}

	@GetMapping("/empresas/{companyId}/members")
	List<MemberView> members(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String companyId) {
		currentUserResolver.resolve(userId, sessionToken);
		return companies.members(companyId).stream().map(MemberView::from).toList();
	}

	@PostMapping("/empresas/{companyId}/members")
	MemberView addMember(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String companyId, @RequestBody AddMemberRequest request) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		Company company = companies.company(companyId);
		companies.requireRole(company, user.id().value(), CompanyRole.ADMIN);
		CompanyMember member = companies.addMember(companyId, request.userId(),
			request.role() == null ? CompanyRole.EDITOR : request.role());
		return MemberView.from(member);
	}

	// --- microprojetos ---

	@GetMapping("/microprojetos")
	List<MicroProjectView> openProjects(@RequestParam(defaultValue = "30") int limit) {
		return microProjects.openProjects(publicListLimit(limit)).stream().map(MicroProjectView::from).toList();
	}

	/** Teto das listas publicas: sem ele, um `limit` enorme trazia a tabela inteira sem sessao. */
	private static int publicListLimit(int limit) {
		return Math.min(Math.max(limit, 1), 100);
	}

	@GetMapping("/microprojetos/{projectId}")
	Map<String, Object> project(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String projectId) {
		CurrentUser viewer = resolveSafely(userId, sessionToken);
		MicroProject project = microProjects.project(projectId);
		List<MicroProjectProposal> proposals = viewer == null ? List.of()
			: microProjects.proposals(projectId, viewer.id().value());
		return Map.of("project", MicroProjectView.from(project), "proposals",
			proposals.stream().map(ProposalView::from).toList());
	}

	@PostMapping("/empresas/{companyId}/microprojetos")
	Map<String, Object> publishProject(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String companyId, @RequestBody PublishProjectRequest request) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		MicroProject created = microProjects.publish(companyId, user.id().value(), request.title(), request.problem(),
			request.scope(), request.deliverables(), request.acceptanceCriteria(), request.budget(), request.deadline());
		return Map.of("id", created.id(), "status", created.status().name());
	}

	@PostMapping("/microprojetos/{projectId}/proposals")
	Map<String, Object> propose(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String projectId, @RequestBody ProposeRequest request) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		MicroProjectProposal proposal = microProjects.propose(projectId, user.id().value(), request.message());
		return Map.of("id", proposal.id(), "status", proposal.status().name());
	}

	@PostMapping("/microprojetos/{projectId}/proposals/{proposalId}/select")
	Map<String, Object> selectProposal(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String projectId, @PathVariable String proposalId) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		MicroProject selected = microProjects.selectProposal(projectId, proposalId, user.id().value());
		return Map.of("status", selected.status().name());
	}

	@PostMapping("/microprojetos/{projectId}/complete")
	Map<String, Object> complete(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String projectId) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		MicroProject completed = microProjects.complete(projectId, user.id().value());
		return Map.of("status", completed.status().name());
	}

	private CurrentUser resolveSafely(String userId, String sessionToken) {
		try {
			return currentUserResolver.resolve(userId, sessionToken);
		} catch (Exception notAuthenticated) {
			return null;
		}
	}

	record CreateCompanyRequest(String name, String description, String sector, String size, String location,
			String website) {
	}

	record UpdateCompanyRequest(String name, String description, String sector, String size, String location,
			String website, String logo, String cover) {
	}

	record AddMemberRequest(String userId, CompanyRole role) {
	}

	record PublishProjectRequest(String title, String problem, String scope, String deliverables,
			String acceptanceCriteria, String budget, java.time.LocalDate deadline) {
	}

	record ProposeRequest(String message) {
	}

	record CompanySummaryView(String id, String slug, String name, String description, String sector, String location,
			String logo, String cover) {
		static CompanySummaryView from(Company c) {
			return new CompanySummaryView(c.id(), c.slug(), c.name(), c.description(), c.sector(), c.location(),
				c.logo(), c.cover());
		}
	}

	record CompanyDetailView(String id, String slug, String name, String description, String sector, String size,
			String location, String website, String logo, String cover, String myRole, List<MicroProjectView> projects) {
	}

	record MemberView(String userId, String role, String createdAt) {
		static MemberView from(CompanyMember m) {
			return new MemberView(m.userId(), m.role().name(), m.createdAt().toString());
		}
	}

	record MicroProjectView(String id, String companyId, String title, String problem, String scope,
			String deliverables, String acceptanceCriteria, String budget, String deadline, String status,
			String createdAt) {
		static MicroProjectView from(MicroProject p) {
			return new MicroProjectView(p.id(), p.companyId(), p.title(), p.problem(), p.scope(), p.deliverables(),
				p.acceptanceCriteria(), p.budget(), p.deadline() != null ? p.deadline().toString() : null,
				p.status().name(), p.createdAt().toString());
		}
	}

	record ProposalView(String id, String userId, String message, String status, String proposedAt) {
		static ProposalView from(MicroProjectProposal p) {
			return new ProposalView(p.id(), p.userId(), p.message(), p.status().name(), p.proposedAt().toString());
		}
	}
}
