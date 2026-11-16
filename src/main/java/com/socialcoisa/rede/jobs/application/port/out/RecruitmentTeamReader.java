package com.socialcoisa.rede.jobs.application.port.out;

import com.socialcoisa.rede.companies.domain.RecruitmentCapability;

/**
 * A equipe de recrutamento da organização da vaga, vista pelo lado das vagas.
 *
 * <p>Aporta: o módulo de vagas pergunta "essa pessoa pode fazer X na organização desta vaga?"
 * e decide a mensagem. A resposta de autorização vive no módulo de páginas; aqui é só a porta
 * de leitura. Vaga sem página (companyId null) nunca consulta — regra do dono único intacta.
 */
public interface RecruitmentTeamReader {

	/** A capability vale para o usuário na organização da vaga? Consulta sem erro. */
	boolean can(String companyId, String userId, RecruitmentCapability capability);
}
