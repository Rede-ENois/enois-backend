package com.socialcoisa.rede.profile.application;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;

/**
 * Mantém a coluna `users.reputation` (usada pelo ranking) em dia.
 *
 * <p>A credibilidade é CALCULADA na leitura (fonte única), mas o ranking ordena por coluna.
 * Antes, o cálculo rodava dentro de transações `readOnly` e o valor persistido nunca era
 * atualizado — o ranking ficava congelado. Aqui a nota é recalculada e gravada periodicamente,
 * em páginas, sem tocar em mais nada.</p>
 */
@Component
class ReputationRefreshWorker {

	private static final Logger LOG = LoggerFactory.getLogger(ReputationRefreshWorker.class);
	private static final int PAGE = 500;

	private final UserProfileRepository profiles;
	private final ReputationService reputation;

	ReputationRefreshWorker(UserProfileRepository profiles, ReputationService reputation) {
		this.profiles = profiles;
		this.reputation = reputation;
	}

	@Scheduled(fixedDelayString = "${rede.reputation.refresh-delay-ms:1800000}",
		initialDelayString = "${rede.reputation.refresh-initial-delay-ms:60000}")
	void refreshRankingColumn() {
		refreshRankingNow();
	}

	/**
	 * Uma passada completa, exposta para teste. Duas fases por página: LEITURA em lote (uma
	 * consulta por métrica para os 500 ids) e ESCRITA um a um — cada `updateReputation` segue
	 * com transação própria e isolamento por usuário, como sempre foi.
	 */
	int refreshRankingNow() {
		int updated = 0;
		try {
			int offset = 0;
			while (true) {
				var ids = profiles.findAllIds(offset, PAGE);
				if (ids.isEmpty()) {
					break;
				}
				Map<String, Integer> reputations;
				try {
					reputations = reputation.reputationsOf(ids);
				}
				catch (RuntimeException batchError) {
					// a leitura em lote falhou para a página inteira: cai para o caminho
					// individual, que continua existindo para os demais chamadores
					LOG.warn("Leitura em lote falhou; usando caminho individual: {}", batchError.getMessage());
					reputations = Map.of();
				}
				for (String id : ids) {
					try {
						Integer value = reputations.get(id);
						if (value == null) {
							value = reputation.reputationOf(id);
						}
						profiles.updateReputation(id, value);
						updated++;
					}
					catch (RuntimeException perUser) {
						// um perfil problemático não pode parar a página inteira
					}
				}
				if (ids.size() < PAGE) {
					break;
				}
				offset += PAGE;
			}
		}
		catch (RuntimeException error) {
			LOG.warn("Falha ao atualizar a coluna de reputação do ranking: {}", error.getMessage());
			return updated;
		}
		if (updated > 0) {
			LOG.info("Ranking de credibilidade atualizado: {} perfis", updated);
		}
		return updated;
	}
}
