package com.socialcoisa.rede.companies.adapters.in.web;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.companies.application.RecruitmentTeamService;
import com.socialcoisa.rede.companies.domain.RecruitmentMember;
import com.socialcoisa.rede.companies.domain.RecruitmentRole;
import com.socialcoisa.rede.corporate.application.CorporateProfileService;
import com.socialcoisa.rede.corporate.domain.CorporateProfile;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * Equipe de recrutamento da página: quem conduz a seleção das vagas dela e com qual papel.
 *
 * <p>Todas as operações de mudança pedem MANAGE_TEAM (dono da página ou ADMIN da organização),
 * decidido no backend por {@link com.socialcoisa.rede.companies.domain.RecruitmentPermissions};
 * o front apenas mostra. O papel de cada um vale para as vagas da organização, não para editar
 * a página — esse segue sendo o papel de membros em <code>/empresas/{id}/members</code>.
 */
@RestController
class RecruitmentTeamController {

	private final RecruitmentTeamService team;
	private final CurrentUserResolver currentUserResolver;
	private final UserProfileRepository profiles;
	private final CorporateProfileService corporateProfiles;

	RecruitmentTeamController(RecruitmentTeamService team, CurrentUserResolver currentUserResolver,
			UserProfileRepository profiles, CorporateProfileService corporateProfiles) {
		this.team = team;
		this.currentUserResolver = currentUserResolver;
		this.profiles = profiles;
		this.corporateProfiles = corporateProfiles;
	}

	@GetMapping("/empresas/{companyId}/recrutamento/equipe")
	List<TeamMemberView> members(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String companyId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		List<RecruitmentTeamService.MemberView> members = team.members(companyId, user.id().value());
		Map<String, CompanyController.PersonView> people = peopleOf(members.stream()
			.map(RecruitmentTeamService.MemberView::userId).toList());
		return members.stream()
			.map(m -> new TeamMemberView(m.userId(), m.role(), m.createdAt(), people.get(m.userId())))
			.toList();
	}

	@PostMapping("/empresas/{companyId}/recrutamento/equipe")
	TeamMemberView addMember(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String companyId, @RequestBody AddTeamMemberRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		RecruitmentMember member = team.addMember(companyId, user.id().value(), request.userId(), request.role());
		return new TeamMemberView(member.userId(), member.role().name(), member.createdAt().toString(),
			peopleOf(List.of(member.userId())).get(member.userId()));
	}

	@PatchMapping("/empresas/{companyId}/recrutamento/equipe/{userId}")
	TeamMemberView changeRole(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String companyId, @PathVariable String userId, @RequestBody ChangeRoleRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		RecruitmentMember member = team.changeRole(companyId, user.id().value(), userId, request.role());
		return new TeamMemberView(member.userId(), member.role().name(), member.createdAt().toString(),
			peopleOf(List.of(member.userId())).get(member.userId()));
	}

	@DeleteMapping("/empresas/{companyId}/recrutamento/equipe/{userId}")
	void removeMember(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String companyId, @PathVariable String userId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		team.removeMember(companyId, user.id().value(), userId);
	}

	/**
	 * O papel de quem pede na organização — ORG_ADMIN para o dono da página, o papel gravado
	 * para membro da equipe, null para quem não faz parte. Leitura sem capability: é o que o
	 * front usa para saber o que mostrar.
	 */
	@GetMapping("/empresas/{companyId}/recrutamento/eu")
	MyRoleView myRole(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String companyId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		RecruitmentRole role = team.effectiveRole(companyId, user.id().value());
		return new MyRoleView(user.id().value(), role == null ? null : role.name());
	}

	private Map<String, CompanyController.PersonView> peopleOf(Collection<String> userIds) {
		Map<String, CorporateProfile> professional = corporateProfiles.activeByUserIds(userIds);
		Map<String, CompanyController.PersonView> people = new HashMap<>();
		professional.forEach((id, p) -> people.put(id, CompanyController.PersonView.from(p)));
		List<String> social = userIds.stream().filter(id -> !professional.containsKey(id)).distinct().toList();
		if (!social.isEmpty()) {
			profiles.findAllByIds(social).forEach(p -> people.put(p.id(), CompanyController.PersonView.from(p)));
		}
		return people;
	}

	record AddTeamMemberRequest(String userId, RecruitmentRole role) {
	}

	record ChangeRoleRequest(RecruitmentRole role) {
	}

	record TeamMemberView(String userId, String role, String createdAt, CompanyController.PersonView person) {
	}

	record MyRoleView(String userId, String role) {
	}
}
