package com.socialcoisa.rede.companies.adapters.in.web;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.companies.application.CompanyDirectoryService;
import com.socialcoisa.rede.companies.application.CompanyService;
import com.socialcoisa.rede.companies.application.MicroProjectService;
import com.socialcoisa.rede.companies.domain.Company;
import com.socialcoisa.rede.companies.domain.CompanyMember;
import com.socialcoisa.rede.companies.domain.CompanyRole;
import com.socialcoisa.rede.companies.domain.MicroProject;
import com.socialcoisa.rede.companies.domain.MicroProjectProposal;
import com.socialcoisa.rede.companies.domain.MicroProjectReview;
import com.socialcoisa.rede.companies.domain.MicroProjectStatus;
import com.socialcoisa.rede.corporate.application.CorporateProfileService;
import com.socialcoisa.rede.corporate.domain.CorporateProfile;
import com.socialcoisa.rede.jobs.application.port.out.CompanyDirectoryReader;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
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
	private final UserProfileRepository profiles;
	private final CorporateProfileService corporateProfiles;
	private final CompanyDirectoryService directory;
	private final CompanyDirectoryReader jobsDirectory;

	CompanyController(CompanyService companies, MicroProjectService microProjects,
			CurrentUserResolver currentUserResolver, UserProfileRepository profiles,
			CorporateProfileService corporateProfiles, CompanyDirectoryService directory,
			CompanyDirectoryReader jobsDirectory) {
		this.profiles = profiles;
		this.companies = companies;
		this.microProjects = microProjects;
		this.currentUserResolver = currentUserResolver;
		this.corporateProfiles = corporateProfiles;
		this.directory = directory;
		this.jobsDirectory = jobsDirectory;
	}

	// --- leitura publica ---

	@GetMapping("/empresas")
	List<CompanySummaryView> list(@RequestParam(defaultValue = "30") int limit) {
		// Listagem por enquanto via paginas de quem publica: o diretorio derivado de vagas
		// (/jobs/companies) cobre descoberta; aqui ficam as paginas empresariais criadas.
		return companies.listAll(publicListLimit(limit)).stream().map(CompanySummaryView::from).toList();
	}

	/** Pages, then names that only exist on internal or external jobs; most opportunities first. */
	@GetMapping("/empresas/diretorio")
	List<DirectoryEntryView> directory(@RequestParam(required = false) String query,
			@RequestParam(required = false) String sector, @RequestParam(defaultValue = "60") int limit) {
		return directory.directory(query, sector, Math.min(Math.max(limit, 1), 200)).stream()
			.map(e -> new DirectoryEntryView(e.kind().name(), e.slug(), e.name(), e.logo(), e.sector(), e.location(),
				e.openJobs(), e.openProjects(), e.followers()))
			.toList();
	}

	@GetMapping("/empresas/setores")
	List<String> sectors() {
		return companies.sectors();
	}

	@PostMapping("/empresas/{companyId}/follow")
	FollowView follow(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String companyId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return new FollowView(true, companies.setFollowing(companyId, user.id().value(), true));
	}

	@DeleteMapping("/empresas/{companyId}/follow")
	FollowView unfollow(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String companyId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return new FollowView(false, companies.setFollowing(companyId, user.id().value(), false));
	}

	@GetMapping("/empresa/{slug}")
	CompanyDetailView bySlug(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String slug) {
		Company company = companies.bySlug(slug);
		CurrentUser viewer = resolveSafely(sessionToken);
		CompanyRole role = viewer == null ? null : companies.roleOf(company.id(), viewer.id().value());
		// Where the viewer stands on each project (a sent proposal) and, for the team, how many
		// proposals each one has. Visitors get neither.
		Map<String, String> mine = viewer == null ? Map.of()
			: microProjects.proposalsOf(viewer.id().value()).stream()
				.collect(Collectors.toMap(MicroProjectProposal::projectId, p -> p.status().name(), (a, b) -> a));
		List<MicroProjectView> projects = microProjects.byCompany(company.id()).stream()
			.map(p -> MicroProjectView.from(p, mine.get(p.id()),
				// ponytail: one count query per project; a page lists at most 50.
				seesProposals(role) ? microProjects.proposals(p.id(), viewer.id().value()).size() : null))
			.toList();
		List<PageJobView> jobs = jobsDirectory.openJobsOf(company.id(), 20).stream()
			.map(j -> new PageJobView(j.id(), j.title(), j.location(), j.type(), j.level(), j.postedAt().toString()))
			.toList();
		StatsView stats = new StatsView(microProjects.countByStatus(company.id(), MicroProjectStatus.OPEN),
			microProjects.countByStatus(company.id(), MicroProjectStatus.COMPLETED),
			jobsDirectory.openJobsByCompanyIds(List.of(company.id())).getOrDefault(company.id(), 0L),
			companies.followerCount(company.id()));
		String viewerId = viewer == null ? null : viewer.id().value();
		return new CompanyDetailView(company.id(), company.slug(), company.name(), company.description(),
			company.sector(), company.size(), company.location(), company.website(), company.logo(), company.cover(),
			role == null ? null : role.name(), projects, jobs, stats, companies.isFollowing(company.id(), viewerId),
			reviews(company.id()), canPropose(viewerId, role));
	}

	/** What the viewer can do on the page's microprojects. */
	private String canPropose(String viewerId, CompanyRole role) {
		if (viewerId == null) {
			return "SIGN_IN";
		}
		if (role != null) {
			return "TEAM";
		}
		return corporateProfiles.activeByUserIds(List.of(viewerId)).containsKey(viewerId) ? "OK"
			: "PROFESSIONAL_REQUIRED";
	}

	private List<ReviewView> reviews(String companyId) {
		List<MicroProjectReview> latest = microProjects.latestReviews(companyId);
		if (latest.isEmpty()) {
			return List.of();
		}
		Map<String, PersonView> people = peopleOf(latest.stream().map(MicroProjectReview::userId).toList());
		return latest.stream()
			.map(r -> new ReviewView(r.projectId(), microProjects.project(r.projectId()).title(), r.rating(), r.text(),
				r.createdAt().toString(), people.get(r.userId())))
			.toList();
	}

	// --- equipe ---

	@PostMapping("/empresas")
	Map<String, Object> create(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestBody CreateCompanyRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		Company created = companies.create(user.id().value(), request.name(), request.description(),
			request.sector(), request.size(), request.location(), request.website());
		return Map.of("id", created.id(), "slug", created.slug(), "name", created.name());
	}

	@PatchMapping("/empresas/{companyId}")
	CompanySummaryView update(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String companyId, @RequestBody UpdateCompanyRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		Company updated = companies.update(companyId, user.id().value(), request.name(), request.description(),
			request.sector(), request.size(), request.location(), request.website(), request.logo(),
			request.cover());
		return CompanySummaryView.from(updated);
	}

	@GetMapping("/empresas/mine")
	List<CompanySummaryView> mine(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return companies.memberOf(user.id().value()).stream().map(CompanySummaryView::from).toList();
	}

	@GetMapping("/empresas/{companyId}/members")
	List<MemberView> members(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String companyId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		List<CompanyMember> members = companies.members(companyId, user.id().value());
		Map<String, PersonView> people = peopleOf(members.stream().map(CompanyMember::userId).toList());
		return members.stream().map(m -> MemberView.from(m, people.get(m.userId()))).toList();
	}

	@PostMapping("/empresas/{companyId}/members")
	MemberView addMember(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String companyId, @RequestBody AddMemberRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		Company company = companies.company(companyId);
		companies.requireRole(company, user.id().value(), CompanyRole.ADMIN);
		CompanyMember member = companies.addMember(companyId, request.userId(),
			request.role() == null ? CompanyRole.EDITOR : request.role());
		return MemberView.from(member, peopleOf(List.of(member.userId())).get(member.userId()));
	}

	// --- microprojetos ---

	@GetMapping("/microprojetos")
	List<MicroProjectView> openProjects(@RequestParam(defaultValue = "30") int limit) {
		return microProjects.openProjects(publicListLimit(limit)).stream().map(p -> MicroProjectView.from(p, null, null))
			.toList();
	}

	/** Teto das listas publicas: sem ele, um `limit` enorme trazia a tabela inteira sem sessao. */
	private static int publicListLimit(int limit) {
		return Math.min(Math.max(limit, 1), 100);
	}

	@GetMapping("/microprojetos/{projectId}")
	Map<String, Object> project(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String projectId) {
		CurrentUser viewer = resolveSafely(sessionToken);
		MicroProject project = microProjects.project(projectId);
		if (project.status() == MicroProjectStatus.CANCELLED) {
			throw new com.socialcoisa.rede.shared.web.ApiException(404, "PROJECT_NOT_FOUND", "Microprojeto nao encontrado.");
		}
		// Proposals are for whoever manages the page; anyone else (logged in or not) reads the project alone.
		boolean team = viewer != null && seesProposals(companies.roleOf(project.companyId(), viewer.id().value()));
		List<MicroProjectProposal> proposals = team ? microProjects.proposals(projectId, viewer.id().value())
			: List.of();
		Map<String, PersonView> people = peopleOf(proposals.stream().map(MicroProjectProposal::userId).toList());
		return Map.of("project", MicroProjectView.from(project, null, team ? proposals.size() : null), "proposals",
			proposals.stream().map(p -> ProposalView.from(p, people.get(p.userId()))).toList());
	}

	@PostMapping("/empresas/{companyId}/microprojetos")
	Map<String, Object> publishProject(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String companyId, @RequestBody PublishProjectRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		MicroProject created = microProjects.publish(companyId, user.id().value(), request.title(), request.problem(),
			request.scope(), request.deliverables(), request.acceptanceCriteria(), request.budget(), request.deadline());
		return Map.of("id", created.id(), "status", created.status().name());
	}

	@org.springframework.web.bind.annotation.PutMapping("/microprojetos/{projectId}")
	Map<String, Object> editProject(@RequestHeader(value = "X-Session-Token", required = false) String token,
			@PathVariable String projectId, @RequestBody PublishProjectRequest request) {
		CurrentUser user = currentUserResolver.resolve(token);
		MicroProject edited = microProjects.edit(projectId, user.id().value(), request.title(), request.problem(),
			request.scope(), request.deliverables(), request.acceptanceCriteria(), request.budget(), request.deadline());
		return Map.of("id", edited.id(), "status", edited.status().name());
	}

	@DeleteMapping("/microprojetos/{projectId}")
	Map<String, Object> removeProject(@RequestHeader(value = "X-Session-Token", required = false) String token,
			@PathVariable String projectId) {
		CurrentUser user = currentUserResolver.resolve(token);
		MicroProject removed = microProjects.remove(projectId, user.id().value());
		return Map.of("id", removed.id(), "status", removed.status().name());
	}

	@PostMapping("/microprojetos/{projectId}/proposals")
	Map<String, Object> propose(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String projectId, @RequestBody ProposeRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		MicroProjectProposal proposal = microProjects.propose(projectId, user.id().value(), request.message());
		return Map.of("id", proposal.id(), "status", proposal.status().name(), "conversationId",
			proposal.conversationId());
	}

	@PostMapping("/microprojetos/{projectId}/proposals/{proposalId}/select")
	Map<String, Object> selectProposal(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String projectId, @PathVariable String proposalId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		MicroProject selected = microProjects.selectProposal(projectId, proposalId, user.id().value());
		return Map.of("status", selected.status().name());
	}

	@PostMapping("/microprojetos/{projectId}/complete")
	Map<String, Object> complete(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String projectId, @RequestBody(required = false) CompleteRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		MicroProject completed = microProjects.complete(projectId, user.id().value(),
			request == null ? null : request.rating(), request == null ? null : request.text());
		return Map.of("status", completed.status().name());
	}

	/** Same bar as the service (EDITOR and up): a recruiter is on the team but does not pick proposals. */
	private static boolean seesProposals(CompanyRole role) {
		return role == CompanyRole.OWNER || role == CompanyRole.ADMIN || role == CompanyRole.EDITOR;
	}

	/**
	 * Who each account is on a page: the ACTIVE professional identity when there is one, the
	 * social profile otherwise (older accounts that never turned it on).
	 */
	private Map<String, PersonView> peopleOf(Collection<String> userIds) {
		Map<String, CorporateProfile> professional = corporateProfiles.activeByUserIds(userIds);
		Map<String, PersonView> people = new HashMap<>();
		professional.forEach((id, p) -> people.put(id, PersonView.from(p)));
		List<String> social = userIds.stream().filter(id -> !professional.containsKey(id)).distinct().toList();
		if (!social.isEmpty()) {
			profiles.findAllByIds(social).forEach(p -> people.put(p.id(), PersonView.from(p)));
		}
		return people;
	}

	private CurrentUser resolveSafely(String sessionToken) {
		try {
			return currentUserResolver.resolve(sessionToken);
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

	/** Optional review of the selected professional: rating 1..5, text up to 500. */
	record CompleteRequest(Integer rating, String text) {
	}

	record DirectoryEntryView(String kind, String slug, String name, String logo, String sector, String location,
			long openJobs, long openProjects, long followers) {
	}

	record FollowView(boolean following, long followers) {
	}

	record PageJobView(String id, String title, String location, String type, String level, String createdAt) {
	}

	record StatsView(long openProjects, long completedProjects, long openJobs, long followers) {
	}

	record ReviewView(String projectId, String projectTitle, int rating, String text, String createdAt,
			PersonView person) {
	}

	record CompanySummaryView(String id, String slug, String name, String description, String sector, String location,
			String logo, String cover) {
		static CompanySummaryView from(Company c) {
			return new CompanySummaryView(c.id(), c.slug(), c.name(), c.description(), c.sector(), c.location(),
				c.logo(), c.cover());
		}
	}

	record CompanyDetailView(String id, String slug, String name, String description, String sector, String size,
			String location, String website, String logo, String cover, String myRole, List<MicroProjectView> projects,
			List<PageJobView> jobs, StatsView stats, boolean following, List<ReviewView> reviews, String canPropose) {
	}

	/**
	 * The person behind a member, a proposal or a review: enough to draw a name, an avatar and a
	 * link. {@code identity} CORPORATE = the active professional profile; SOCIAL = the fallback.
	 */
	record PersonView(String id, String username, String name, String headline, String avatar, String identity,
			String href) {
		static PersonView from(CorporateProfile p) {
			return new PersonView(p.userId(), p.username(), p.name(), p.headline(), p.avatar(), "CORPORATE",
				"/profissional/" + p.username());
		}

		static PersonView from(UserProfile p) {
			return new PersonView(p.id(), p.username(), p.name(), p.headline(), p.avatar(), "SOCIAL",
				"/perfil/" + p.username());
		}
	}

	record MemberView(String userId, String role, String createdAt, PersonView person) {
		static MemberView from(CompanyMember m, PersonView person) {
			return new MemberView(m.userId(), m.role().name(), m.createdAt().toString(), person);
		}
	}

	/**
	 * {@code myProposal}: the viewer's proposal status on this project (null when none);
	 * {@code proposalCount}: only for the company team.
	 */
	record MicroProjectView(String id, String companyId, String title, String problem, String scope,
			String deliverables, String acceptanceCriteria, String budget, String deadline, String status,
			String createdAt, String myProposal, Integer proposalCount) {
		static MicroProjectView from(MicroProject p, String myProposal, Integer proposalCount) {
			return new MicroProjectView(p.id(), p.companyId(), p.title(), p.problem(), p.scope(), p.deliverables(),
				p.acceptanceCriteria(), p.budget(), p.deadline() != null ? p.deadline().toString() : null,
				p.status().name(), p.createdAt().toString(), myProposal, proposalCount);
		}
	}

	record ProposalView(String id, String userId, String message, String status, String proposedAt,
			PersonView person, String conversationId) {
		static ProposalView from(MicroProjectProposal p, PersonView person) {
			return new ProposalView(p.id(), p.userId(), p.message(), p.status().name(), p.proposedAt().toString(),
				person, p.conversationId());
		}
	}
}
