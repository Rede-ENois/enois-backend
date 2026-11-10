package com.socialcoisa.rede.profile.application.port.out;

import java.util.List;

import com.socialcoisa.rede.profile.domain.BadgeKit;

/**
 * Histórico de kits de emblemas: os últimos conjuntos que o perfil exibiu.
 *
 * <p>Gravar é snapshot do conjunto ANTERIOR no momento de uma troca (não do novo); o kit só
 * nasce quando a lista muda de verdade. Mantém os 3 mais recentes por pessoa.</p>
 */
public interface BadgeKitRepository {

	/** Guarda um snapshot e poda o histórico para os 3 mais recentes da pessoa. */
	void record(String userId, BadgeKit kit);

	/** Os kits da pessoa, do mais recente para o mais antigo (máximo 3). */
	List<BadgeKit> history(String userId);

	/** Busca um kit pelo id; só devolve se for da pessoa (o dono pode reaproveitar). */
	java.util.Optional<BadgeKit> find(String userId, String kitId);

	/** Remove um kit do histórico (o aplicado sai: virou o conjunto atual). */
	void delete(String userId, String kitId);
}
