package com.socialcoisa.rede.contributions.application;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sincronização incremental de PRs e issues. Nasce desligada (`rede.github.sync-enabled=false`): o
 * primeiro backfill é manual e revisado. Quando ligada, roda a cada 6h, recalcula os níveis e
 * nunca derruba o app se o GitHub estiver fora ou o limite da API estourar.
 */
@Component
public class GitHubContributionSyncWorker {

	private static final Logger LOG = LoggerFactory.getLogger(GitHubContributionSyncWorker.class);

	private final GitHubContributionSyncService sync;
	private final boolean enabled;

	public GitHubContributionSyncWorker(GitHubContributionSyncService sync,
			@Value("${rede.github.sync-enabled:false}") boolean enabled) {
		this.sync = sync;
		this.enabled = enabled;
	}

	@Scheduled(cron = "${rede.github.sync-cron:0 0 */6 * * *}")
	public void run() {
		if (!enabled) {
			return;
		}
		try {
			var report = sync.sync(false, false, List.of());
			LOG.info(
				"github_sync_worker prsFetched={} prsCreated={} prsUpdated={} issuesFetched={} "
					+ "issuesCreated={} issuesUpdated={} sealsRecalculated={} rateLimited={}",
				report.fetched(), report.created(), report.updated(), report.issuesFetched(),
				report.issuesCreated(), report.issuesUpdated(), report.sealsRecalculated(),
				report.rateLimited());
		}
		catch (RuntimeException failure) {
			LOG.warn("github_sync_worker_failed error={}", failure.getMessage());
		}
	}
}
