package com.socialcoisa.rede.identity.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Chama a rodada automatica de liberacao.
 *
 * <p>A varredura e frequente mas barata: {@link IdentityReleaseService#runAutomaticRound()} so
 * libera quando o intervalo configurado pela administracao venceu, entao aumentar a frequencia do
 * worker nao acelera a liberacao nem multiplica consultas ao banco.</p>
 *
 * <p>Desligado por padrao: a liberacao so comeca quando a administracao decidir, sem sobrescrever
 * a politica gravada. Erro na rodada (banco fora, credencial ausente) nao derruba a aplicacao —
 * fica no log e a proxima varredura tenta de novo.</p>
 */
@Component
public class IdentityReleaseWorker {

	private static final Logger LOG = LoggerFactory.getLogger(IdentityReleaseWorker.class);

	private final IdentityReleaseService releases;
	private final boolean enabled;

	public IdentityReleaseWorker(IdentityReleaseService releases,
			@Value("${rede.identity.didit.release-worker-enabled:false}") boolean enabled) {
		this.releases = releases;
		this.enabled = enabled;
	}

	@Scheduled(initialDelayString = "${rede.identity.didit.release-initial-delay-ms:300000}",
		fixedDelayString = "${rede.identity.didit.release-scan-delay-ms:900000}")
	public void tick() {
		if (!enabled) {
			return;
		}
		try {
			var round = releases.runAutomaticRound();
			if (round != null) {
				LOG.info("Rodada automatica de identidade: estado={} liberados={}", round.state().name(),
					round.releasedCount());
			}
		}
		catch (RuntimeException failure) {
			LOG.warn("Rodada automatica de identidade falhou: {}", failure.getMessage());
		}
	}
}
