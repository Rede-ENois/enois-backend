package com.socialcoisa.rede.jobs.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Expurga as linhas retiradas do banco de talentos depois do prazo de guarda.
 *
 * <p>A retirada já tira a pessoa da leitura do recrutamento no mesmo instante; o que resta é a
 * linha que prova que houve consentimento e que ele foi retirado. Guardar isso para sempre seria
 * reter dado pessoal sem finalidade, então a rotina apaga em lotes o que passou de
 * {@link TalentPoolService#RETENTION} depois da retirada. Mesmo formato das outras retenções:
 * agendada, silenciosa no sucesso sem trabalho, e nunca derruba o boot se o banco recusar.
 */
@Component
public class TalentPoolRetentionWorker {

	private static final Logger LOG = LoggerFactory.getLogger(TalentPoolRetentionWorker.class);

	private final TalentPoolService talentPool;
	private final boolean enabled;

	public TalentPoolRetentionWorker(TalentPoolService talentPool,
			@Value("${rede.jobs.talent-pool.retention-enabled:true}") boolean enabled) {
		this.talentPool = talentPool;
		this.enabled = enabled;
	}

	@Scheduled(initialDelayString = "${rede.jobs.talent-pool.retention-initial-delay-ms:900000}",
			fixedDelayString = "${rede.jobs.talent-pool.retention-interval-ms:21600000}")
	public void run() {
		if (!enabled) {
			return;
		}
		try {
			int purged = talentPool.purgeWithdrawn();
			if (purged > 0) {
				LOG.info("Consentimentos retirados do banco de talentos expurgados: {}", purged);
			}
		}
		catch (RuntimeException failure) {
			LOG.warn("Falha ao expurgar o banco de talentos: {}", failure.getMessage());
		}
	}
}
