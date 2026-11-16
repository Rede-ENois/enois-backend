package com.socialcoisa.rede.companies.application;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.companies.application.port.out.RecruitmentTeamRepository;
import com.socialcoisa.rede.companies.domain.Company;
import com.socialcoisa.rede.companies.domain.RecruitmentCapability;
import com.socialcoisa.rede.companies.domain.RecruitmentMember;
import com.socialcoisa.rede.companies.domain.RecruitmentPermissions;
import com.socialcoisa.rede.companies.domain.RecruitmentRole;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Equipe de recrutamento de uma organizacao (a pagina empresarial): quem conduz a selecao das
 * vagas dela e com qual papel.
 *
 * <p>Autorizacao decidida AQUI, no backend, pela tabela de {@link RecruitmentPermissions} — o
 * front apenas mostra o que o papel permite. O dono da pagina comanda sem registro; quem nao
 * esta na equipe nao ve o processo de nenhuma vaga da organizacao.
 */
@Service
public class RecruitmentTeamService {

	private final RecruitmentTeamRepository team;
	private final CompanyService companies;
	private final Clock clock;

	public RecruitmentTeamService(RecruitmentTeamRepository team, CompanyService companies, Clock clock) {
		this.team = team;
		this.companies = companies;
		this.clock = clock;
	}

	public record MemberView(String userId, String role, String createdAt) {
	}

	/** A equipe da organizacao; so quem tem poder de equipe (ou o dono) lista. */
	@Transactional(readOnly = true)
	public List<MemberView> members(String companyId, String requesterId) {
		requireCapability(companyId, requesterId, RecruitmentCapability.MANAGE_TEAM);
		return team.membersOf(companyId).stream()
			.map(m -> new MemberView(m.userId(), m.role().name(), m.createdAt().toString()))
			.toList();
	}

	/** Convida uma pessoa para a equipe com papel explicito; OWNER da pagina nunca e convidavel. */
	@Transactional
	public RecruitmentMember addMember(String companyId, String requesterId, String userId,
			RecruitmentRole role) {
		requireCapability(companyId, requesterId, RecruitmentCapability.MANAGE_TEAM);
		if (userId == null || userId.isBlank()) {
			throw new ApiException(400, "INVALID_MEMBER", "Diga quem entra na equipe.");
		}
		if (role == null) {
			throw new ApiException(400, "INVALID_ROLE", "Papel obrigatorio para entrar na equipe.");
		}
		companies.company(companyId); // 404 para pagina inexistente antes de qualquer gravacao
		if (companies.company(companyId).ownerUserId().equals(userId)) {
			throw new ApiException(409, "ALREADY_MEMBER", "O dono da pagina ja comanda a equipe.");
		}
		if (team.findMember(companyId, userId).isPresent()) {
			throw new ApiException(409, "ALREADY_MEMBER", "Esta pessoa ja faz parte da equipe.");
		}
		return team.save(RecruitmentMember.create(
			"rteam_" + UUID.randomUUID().toString().replace("-", ""), companyId, userId, role, clock.instant()));
	}

	/** Troca o papel de um membro da equipe. */
	@Transactional
	public RecruitmentMember changeRole(String companyId, String requesterId, String userId,
			RecruitmentRole newRole) {
		requireCapability(companyId, requesterId, RecruitmentCapability.MANAGE_TEAM);
		if (newRole == null) {
			throw new ApiException(400, "INVALID_ROLE", "Papel novo obrigatorio.");
		}
		RecruitmentMember member = team.findMember(companyId, userId)
			.orElseThrow(() -> new ApiException(404, "MEMBER_NOT_FOUND", "Esta pessoa nao faz parte da equipe."));
		return team.updateRole(companyId, userId, newRole);
	}

	/** Remove um membro da equipe. O dono da pagina nao sai por aqui. */
	@Transactional
	public void removeMember(String companyId, String requesterId, String userId) {
		requireCapability(companyId, requesterId, RecruitmentCapability.MANAGE_TEAM);
		Company page = companies.company(companyId);
		if (page.ownerUserId().equals(userId)) {
			throw new ApiException(409, "OWNER_NOT_REMOVABLE", "O dono da pagina nao sai da equipe por aqui.");
		}
		team.findMember(companyId, userId)
			.orElseThrow(() -> new ApiException(404, "MEMBER_NOT_FOUND", "Esta pessoa nao faz parte da equipe."));
		team.removeMember(companyId, userId);
	}

	/** O papel efetivo do usuario na organizacao: dono da pagina vale ORG_ADMIN. */
	@Transactional(readOnly = true)
	public RecruitmentRole effectiveRole(String companyId, String userId) {
		return companies.findById(companyId)
			.map(page -> page.ownerUserId().equals(userId) ? RecruitmentRole.ORG_ADMIN
				: team.findMember(companyId, userId).map(RecruitmentMember::role).orElse(null))
			.orElse(null);
	}

	/** A capability vale? Erro explicito (403) quando nao vale — nunca silencio. */
	@Transactional(readOnly = true)
	public void requireCapability(String companyId, String userId, RecruitmentCapability capability) {
		Company page = companies.company(companyId);
		RecruitmentRole stored = team.findMember(companyId, userId)
			.map(RecruitmentMember::role).orElse(null);
		boolean owner = page.ownerUserId().equals(userId);
		if (!RecruitmentPermissions.can(stored, owner, capability)) {
			throw new ApiException(403, "RECRUITMENT_FORBIDDEN",
				"Seu papel na equipe nao permite esta acao.");
		}
	}

	/** Consulta sem erro, para o lado de vagas perguntar antes de decidir a mensagem. */
	@Transactional(readOnly = true)
	public boolean can(String companyId, String userId, RecruitmentCapability capability) {
		return companies.findById(companyId)
			.map(page -> {
				RecruitmentRole stored = team.findMember(companyId, userId)
					.map(RecruitmentMember::role).orElse(null);
				return RecruitmentPermissions.can(stored, page.ownerUserId().equals(userId), capability);
			})
			.orElse(false);
	}

	/** O papel gravado, sem considerar o dono. Para exibicao. */
	@Transactional(readOnly = true)
	public Optional<RecruitmentRole> storedRole(String companyId, String userId) {
		return team.findMember(companyId, userId).map(RecruitmentMember::role);
	}

}
