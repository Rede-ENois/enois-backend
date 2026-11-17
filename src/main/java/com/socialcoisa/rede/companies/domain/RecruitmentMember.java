package com.socialcoisa.rede.companies.domain;

import java.time.Instant;

/**
 * Um membro da equipe de recrutamento de uma organizacao (a pagina empresarial).
 *
 * <p>O OWNER da pagina entra automaticamente como ORG_ADMIN — a organizacao nunca fica sem
 * administrador. O par (companyId, userId) e unico: uma pessoa, um papel por organizacao.
 */
public record RecruitmentMember(String id, String companyId, String userId, RecruitmentRole role,
		Instant createdAt) {

	public static RecruitmentMember create(String id, String companyId, String userId, RecruitmentRole role,
			Instant now) {
		return new RecruitmentMember(id, companyId, userId, role, now);
	}

	public RecruitmentMember withRole(RecruitmentRole newRole) {
		return new RecruitmentMember(id, companyId, userId, newRole, createdAt);
	}
}
