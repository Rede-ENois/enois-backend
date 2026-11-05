package com.socialcoisa.rede.jobs.application;

import java.time.Clock;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.socialcoisa.rede.jobs.application.port.out.ExternalJobRepository;
import com.socialcoisa.rede.jobs.application.port.out.JobImportSource;
import com.socialcoisa.rede.jobs.domain.ExternalJob;

/**
 * Importa/atualiza vagas de fontes externas (ex.: JobRadar) na tabela PROPRIA `external_jobs`.
 *
 * Carrega TUDO que a fonte publica (nao uma amostra). Para nao estourar memoria nem uma unica
 * transacao gigante, grava em LOTES, cada lote com sua transacao. Dedupe por (plataforma, id externo):
 * reimportar nao duplica. Falha de uma fonte nao derruba as demais nem as vagas internas.
 */
@Service
public class ExternalJobImportService {

	/** Tamanho do lote: transacao curta por bloco, em vez de uma transacao de milhares de linhas. */
	private static final int BATCH_SIZE = 200;

	private final List<JobImportSource> sources;
	private final ExternalJobRepository repository;
	private final Clock clock;
	private final int limit;
	private final TransactionTemplate transactionTemplate;

	public ExternalJobImportService(List<JobImportSource> sources, ExternalJobRepository repository, Clock clock,
			PlatformTransactionManager transactionManager,
			@Value("${rede.jobs.import.external-refresh-limit:0}") int limit) {
		this.sources = sources;
		this.repository = repository;
		this.clock = clock;
		this.limit = limit;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
	}

	/** Retorna quantas vagas externas foram importadas/atualizadas nesta passada. */
	public int refresh() {
		int touched = 0;
		for (JobImportSource source : sources) {
			if (!source.available()) {
				continue;
			}
			List<JobImportSource.ExternalJob> found;
			try {
				// O try/catch cobre SO a chamada externa (fonte fora do ar). Erro de banco NAO e
				// engolido, para nao deixar transacao em rollback-only.
				found = source.discover(limit);
			}
			catch (RuntimeException failure) {
				continue;
			}
			for (int i = 0; i < found.size(); i += BATCH_SIZE) {
				List<JobImportSource.ExternalJob> chunk = found.subList(i, Math.min(i + BATCH_SIZE, found.size()));
				Integer saved = transactionTemplate.execute(status -> saveChunk(source.name(), chunk));
				touched += saved == null ? 0 : saved;
			}
		}
		return touched;
	}

	private int saveChunk(String platform, List<JobImportSource.ExternalJob> chunk) {
		int saved = 0;
		for (JobImportSource.ExternalJob external : chunk) {
			if (external.externalId() == null || external.title() == null) {
				continue; // sem id/titulo nao da para deduplicar nem mostrar: ignora
			}
			// Some feeds send HTML-escaped text ("hims &#038; hers"): store it as people read it. A job
			// already imported escaped heals on the next run (same source = upsert below).
			String title = org.springframework.web.util.HtmlUtils.htmlUnescape(external.title());
			String company = external.company() == null ? null
				: org.springframework.web.util.HtmlUtils.htmlUnescape(external.company());
			String canonicalUrl = com.socialcoisa.rede.jobs.domain.UrlCanonicalizer
				.canonicalize(external.url());
			// E-mail de candidatura: sai da descricao que a propria fonte publica (nulo quando nao ha).
			String applyEmail = ExternalJob.extractApplyEmail(external.description());
			var sameSource = repository.findBySource(platform, external.externalId());
			if (sameSource.isPresent()) {
				// Mesma fonte e mesmo id: atualiza os dados e a verificacao (upsert).
				ExternalJob current = sameSource.get();
				repository.save(new ExternalJob(current.id(), current.platform(), current.externalId(),
					title, company, external.location(), external.salary(),
					current.currency(), external.url(), current.importedAt(), clock.instant(),
					ExternalJob.FOUND, canonicalUrl, applyEmail, current.submittedBy(), current.description(),
					current.salaryMin(), current.salaryMax(), current.salaryPeriod(), current.salaryNotInformed()));
				saved++;
				continue;
			}
			// Dedupe GLOBAL: a MESMA vaga pode aparecer em outra plataforma (mesma URL canonica).
			// Nesse caso NAO criamos duplicata nem sobrescrevemos a procedencia original.
			var otherSource = canonicalUrl == null ? java.util.Optional.<ExternalJob>empty()
				: repository.findByCanonicalUrl(canonicalUrl);
			if (otherSource.isPresent()) {
				ExternalJob original = otherSource.get();
				// Vaga cadastrada pela COMUNIDADE: o registro do autor e a fonte da verdade — a
				// importacao nao reescreve titulo/empresa nem assume a procedencia dele.
				if (original.isCommunitySubmission()) {
					continue;
				}
				repository.save(new ExternalJob(original.id(), original.platform(), original.externalId(),
					original.title(), original.company(), original.location(), original.salary(),
					original.currency(), original.url(), original.importedAt(), clock.instant(),
					original.status(), original.canonicalUrl(), original.applyEmail(), original.submittedBy(),
					original.description(), original.salaryMin(), original.salaryMax(), original.salaryPeriod(),
					original.salaryNotInformed()));
				saved++;
				continue;
			}
			repository.save(new ExternalJob("ext_" + platform + "_" + external.externalId(), platform,
				external.externalId(), title, company, external.location(),
				external.salary(), null, external.url(), clock.instant(), clock.instant(), ExternalJob.FOUND,
				canonicalUrl, applyEmail, null, null, null, null, null, false));
			saved++;
		}
		return saved;
	}
}
