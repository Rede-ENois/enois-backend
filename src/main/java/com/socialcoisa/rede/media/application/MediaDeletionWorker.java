package com.socialcoisa.rede.media.application;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.socialcoisa.rede.media.application.port.out.MediaAssetRepository;
import com.socialcoisa.rede.media.application.port.out.MediaDeletionJobRepository;
import com.socialcoisa.rede.media.application.port.out.MediaStorageGateway;
import com.socialcoisa.rede.media.domain.MediaAsset;
import com.socialcoisa.rede.media.domain.MediaDeletionJob;

@Component
class MediaDeletionWorker {

	private final MediaDeletionJobRepository jobRepository;
	private final MediaAssetRepository assetRepository;
	private final MediaStorageGateway storageGateway;
	private final Clock clock;
	private final TransactionTemplate transaction;

	MediaDeletionWorker(MediaDeletionJobRepository jobRepository, MediaAssetRepository assetRepository,
			MediaStorageGateway storageGateway, Clock clock, PlatformTransactionManager transactionManager) {
		this.jobRepository = jobRepository;
		this.assetRepository = assetRepository;
		this.storageGateway = storageGateway;
		this.clock = clock;
		this.transaction = new TransactionTemplate(transactionManager);
	}

	@Scheduled(fixedDelayString = "${rede.media.deletion-worker-delay-ms:5000}")
	void processPendingJobs() {
		List<MediaDeletionJob> jobs = jobRepository.findPending(clock.instant(), 50);
		for (MediaDeletionJob job : jobs) {
			try {
				purgeOne(job);
			}
			catch (RuntimeException exception) {
				jobRepository.fail(job.mediaId(), exception.getMessage(), clock.instant().plus(backoff(job.attempts())));
			}
		}
	}

	/**
	 * Um job, com a transacao ABERTA SO ENQUANTO HA TRABALHO DE BANCO.
	 *
	 * <p>O metodo inteiro era {@code @Transactional}: a conexao do pool ficava presa durante as duas
	 * chamadas HTTP de CADA job (purge de CDN e delete do objeto, ambas com retry/circuit
	 * breaker/bulkhead). Com o CDN lento, poucas rodadas esgotavam o Hikari e o app inteiro parava de
	 * atender — o worker de limpeza derrubava o trafego.</p>
	 *
	 * <p>Agora a leitura do asset numa transacao curta, o I/O de rede FORA de qualquer transacao, e
	 * a gravacao do resultado em outra transacao curta. Entre elas o asset pode ter mudado; a
	 * gravacao relê a entidade, entao nao sobrescreve estado alheio. Se o processo cair depois do
	 * delete e antes do {@code complete}, o job continua pendente e o proximo ciclo repete — o
	 * delete de objeto e idempotente por natureza.</p>
	 */
	void purgeOne(MediaDeletionJob job) {
		MediaAsset asset = inShortTransaction(() -> assetRepository.findById(job.mediaId()).orElse(null));
		if (asset == null) {
			inShortTransaction(() -> {
				jobRepository.complete(job.mediaId(), clock.instant());
				return null;
			});
			return;
		}
		// I/O de rede: nenhuma transacao aberta aqui, de proposito.
		storageGateway.purgeCdn(asset);
		storageGateway.deleteObject(asset);
		inShortTransaction(() -> {
			assetRepository.findById(asset.id()).ifPresent(fresh -> {
				fresh.markCdnPurged();
				fresh.markObjectDeleted(clock.instant());
				assetRepository.save(fresh);
			});
			jobRepository.complete(asset.id(), clock.instant());
			return null;
		});
	}

	/** Executa em transacao propria (curta); nada de rede dentro dela. */
	private <T> T inShortTransaction(java.util.function.Supplier<T> work) {
		return transaction.execute(status -> work.get());
	}

	private Duration backoff(int attempts) {
		int seconds = Math.min(300, Math.max(5, (attempts + 1) * 10));
		return Duration.ofSeconds(seconds);
	}
}
