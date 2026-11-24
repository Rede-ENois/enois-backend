package com.socialcoisa.rede.media.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.socialcoisa.rede.media.application.port.out.MediaStorageGateway;
import com.socialcoisa.rede.media.domain.MediaAsset;
import com.socialcoisa.rede.shared.web.TestDatabaseSupport;

/**
 * O worker de exclusao de midia nao pode segurar uma conexao do banco enquanto conversa com o CDN e
 * com o object storage.
 *
 * <p>Antes a rodada inteira era {@code @Transactional} — 50 jobs, cada um com DUAS chamadas HTTP
 * (purge de CDN e delete do objeto), ambas com {@code @Retry}/{@code @CircuitBreaker}/{@code @Bulkhead}.
 * A conexao do pool ficava presa durante todo esse I/O: com o CDN lento, poucas rodadas esgotavam o
 * Hikari e o app parava de atender.</p>
 *
 * <p>A assercao e de comportamento, nao de tempo: nenhuma chamada de rede pode acontecer com uma
 * transacao aberta neste thread. Protege contra a volta do {@code @Transactional} no metodo inteiro.</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
		"spring.datasource.url=jdbc:h2:mem:media_deletion_transaction;MODE=MySQL;DB_CLOSE_DELAY=-1",
		"rede.media.deletion-worker-delay-ms=3600000",
		"rede.media.deletion-worker-initial-delay-ms=3600000" })
@Tag("critico")
class MediaDeletionWorkerTransactionTests {

	/** Evita a associação no boot; o listener vincula o helper somente ao contexto do teste atual. */
	@MockitoBean private TestDatabaseSupport testDatabaseSupport;

	/** Conta, por chamada, se havia transacao ativa no thread. */
	static final List<Boolean> INSIDE_TRANSACTION = new ArrayList<>();
	static final List<String> CALLS = new ArrayList<>();

	@TestConfiguration
	static class InspectingGatewayConfig {

		@Bean
		@Primary
		java.time.Clock relogioDoCenario() {
			return java.time.Clock.fixed(java.time.Instant.parse("2026-11-01T12:00:00.123456789Z"),
				java.time.ZoneOffset.UTC);
		}

		@Bean
		@Primary
		MediaStorageGateway inspectingGateway() {
			return new MediaStorageGateway() {
				@Override
				public void purgeCdn(MediaAsset asset) {
					CALLS.add("purge:" + asset.id());
					INSIDE_TRANSACTION.add(TransactionSynchronizationManager.isActualTransactionActive());
				}

				@Override
				public void deleteObject(MediaAsset asset) {
					CALLS.add("delete:" + asset.id());
					INSIDE_TRANSACTION.add(TransactionSynchronizationManager.isActualTransactionActive());
				}
			};
		}
	}

	@Autowired
	private MediaDeletionWorker worker;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private java.time.Clock clock;

	@Test
	void networkCallsRunOutsideTheDatabaseTransaction() {
		String mediaId = "med_tx_" + UUID.randomUUID().toString().substring(0, 8);
		seedPendingJob(mediaId);
		verificarElegibilidade(mediaId);
		INSIDE_TRANSACTION.clear();
		CALLS.clear();

		worker.processPendingJobs();

		assertThat(CALLS).as("o job semeado precisa ter sido processado").isNotEmpty();
		assertThat(INSIDE_TRANSACTION)
			.as("toda chamada de rede do worker deve correr FORA da transacao")
			.doesNotContain(true);
	}

	@Test
	void theJobStillCompletesAndPurgesItsObject() {
		String mediaId = "med_done_" + UUID.randomUUID().toString().substring(0, 8);
		seedPendingJob(mediaId);
		verificarElegibilidade(mediaId);
		CALLS.clear();

		worker.processPendingJobs();

		assertThat(CALLS).as("purge e delete acontecem, na ordem; estado: %s",
			jdbc.queryForMap("select status, error_message, next_attempt_at from media_deletion_jobs where media_id = ?", mediaId))
			.containsExactly("purge:" + mediaId, "delete:" + mediaId);
		assertThat(jobStatus(mediaId)).as("o job sai de REQUESTED").isEqualTo("COMPLETED");
		assertThat(deleteStatus(mediaId)).as("o asset fica marcado como apagado").isEqualTo("OBJECT_DELETED");
	}

	private String jobStatus(String mediaId) {
		return jdbc.queryForObject("select status from media_deletion_jobs where media_id = ?", String.class, mediaId);
	}

	private String deleteStatus(String mediaId) {
		return jdbc.queryForObject("select delete_status from media_assets where id = ?", String.class, mediaId);
	}

	private void verificarElegibilidade(String mediaId) {
		java.time.Instant tentativa = jdbc.queryForObject("select next_attempt_at from media_deletion_jobs where media_id = ?",
			java.sql.Timestamp.class, mediaId).toInstant();
		assertThat(tentativa).as("fixture deve estar elegivel antes de rodar o worker").isBeforeOrEqualTo(clock.instant());
	}

	private void seedPendingJob(String mediaId) {
		var elegivel = java.sql.Timestamp.from(clock.instant().minusSeconds(1));
		jdbc.update("""
			insert into media_assets (id, owner_id, file_name, mime_type, size_bytes, object_key, cdn_url,
				status, delete_status, created_at, expires_at)
			values (?, 'med_tx_owner', 'a.png', 'image/png', 10, ?, 'https://cdn.test/a.png',
				'READY', 'REQUESTED', now(), now())
			""", mediaId, "medias/" + mediaId);
		jdbc.update("""
			insert into media_deletion_jobs (id, media_id, owner_id, status, attempts, created_at, next_attempt_at)
			values (?, ?, 'med_tx_owner', 'REQUESTED', 0, ?, ?)
			""", mediaId + "_job", mediaId, elegivel, elegivel);
	}
}
