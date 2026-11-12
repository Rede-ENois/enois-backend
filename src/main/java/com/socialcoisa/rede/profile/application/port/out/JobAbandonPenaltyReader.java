package com.socialcoisa.rede.profile.application.port.out;

/**
 * Vagas excluídas com candidatos sem resposta (item 7 do backlog). Vive no perfil porque o
 * componente de integridade da credibilidade desconta por ele — a implementação vem do módulo
 * de jobs, onde a vaga vive.
 */
public interface JobAbandonPenaltyReader {

	/** Quantas exclusões de vaga sem resposta o usuário tem (com teto aplicado no serviço). */
	int countByUser(String userId);

	/** Exclusões de vaga sem resposta de cada conta do conjunto, em uma consulta. */
	java.util.Map<String, Integer> countsByUsers(java.util.Collection<String> userIds);
}
