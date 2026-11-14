package com.socialcoisa.rede.network.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.network.application.port.out.ContentVersionRepository;

/**
 * Poda do feed de mudanças do conteúdo público (V298).
 *
 * <p>O feed é janela, não histórico: cada linha diz "esta versão foi cunhada agora", e depois que
 * todos os nós de borda passaram por ela a linha não serve para mais nada. Sem poda, a tabela
 * cresce com toda edição de perfil e de comunidade da plataforma, para sempre.</p>
 *
 * <p><b>A poda é o que cria o caso do {@code reset}.</b> Um nó que ficou parado além da janela não
 * tem mais como saber o que perdeu, e é por isso que a rota do feed responde {@code reset=true} em
 * vez de "nada mudou" para um cursor atrás do menor {@code seq} retido. A janela é generosa de
 * propósito: ela precisa cobrir o tempo em que um nó pode ficar fora (deploy, queda, manutenção)
 * sem obrigar recarga completa.</p>
 *
 * <p>Lote limitado e transação por lote, como a retenção da outbox: apagar tudo de uma vez numa
 * tabela grande seguraria o banco por minutos.</p>
 */
@Component
public class ContentChangeRetentionWorker {

	private static final Logger LOG = LoggerFactory.getLogger(ContentChangeRetentionWorker.class);

	private final ContentVersionRepository versions;
	private final Clock clock;
	private final boolean enabled;
	private final Duration retention;
	private final int batchSize;

	ContentChangeRetentionWorker(ContentVersionRepository versions, Clock clock,
			@Value("${rede.network.content.changes.retention.enabled:true}") boolean enabled,
			@Value("${rede.network.content.changes.retention-hours:72}") long retentionHours,
			@Value("${rede.network.content.changes.retention.batch-size:1000}") int batchSize) {
		this.versions = versions;
		this.clock = clock;
		this.enabled = enabled;
		this.retention = Duration.ofHours(Math.max(1, retentionHours));
		this.batchSize = Math.max(1, batchSize);
	}

	@Scheduled(initialDelayString = "${rede.network.content.changes.retention.initial-delay-ms:600000}",
			fixedDelayString = "${rede.network.content.changes.retention.delay-ms:3600000}")
	void sweep() {
		if (!enabled) {
			return;
		}
		try {
			int deleted = runOnce();
			if (deleted > 0) {
				LOG.info("Feed de mudancas de conteudo: {} posicoes podadas", deleted);
			}
		}
		catch (RuntimeException failure) {
			LOG.warn("Falha ao podar o feed de mudancas de conteudo: {}", failure.getMessage());
		}
	}

	/** Remove um lote de posições vencidas. Devolve quantas saíram. */
	@Transactional
	public int runOnce() {
		Instant before = clock.instant().minus(retention);
		List<Long> expired = versions.expiredChangeSeqs(before, batchSize);
		if (expired.isEmpty()) {
			return 0;
		}
		return versions.deleteChanges(expired);
	}
}