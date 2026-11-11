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

	/**
	 * Janela de tolerância antes de considerar uma vaga desaparecida. A fonte pode falhar um ciclo
	 * ou publicar em lotes; marcar como removida no primeiro ciclo sem a vaga apagaria vaga viva.
	 */
	private static final java.time.Duration STALE_GRACE = java.time.Duration.ofDays(3);

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
			// Só expira quando a fonte entregou algo: lista vazia pode ser falha silenciosa, e
			// apagar tudo por causa dela seria pior do que manter vaga que talvez tenha saído.
			if (!found.isEmpty()) {
				expireStale(source.name());
			}
		}
		return touched;
	}

	/**
	 * Marca como removidas as vagas da fonte que não apareceram no ciclo atual. Não apaga a linha:
	 * o histórico de candidaturas e o link original continuam válidos, e a vaga some das buscas
	 * porque toda consulta filtra `removed_at is null`.
	 */
	private void expireStale(String platform) {
		java.time.Instant cutoff = clock.instant().minus(STALE_GRACE);
		List<ExternalJob> stale = repository.findStaleByPlatform(platform, cutoff, BATCH_SIZE);
		while (!stale.isEmpty()) {
			List<ExternalJob> batch = stale;
			transactionTemplate.executeWithoutResult(status -> {
				for (ExternalJob job : batch) {
					repository.save(withDetails(job, job.details().removedAt(clock.instant())));
				}
			});
			stale = repository.findStaleByPlatform(platform, cutoff, BATCH_SIZE);
		}
	}

	/** Copia a vaga trocando só os detalhes (o resto do registro fica como está). */
	private static ExternalJob withDetails(ExternalJob job,
			com.socialcoisa.rede.jobs.domain.ExternalJobDetails details) {
		return new ExternalJob(job.id(), job.platform(), job.externalId(), job.title(), job.company(),
			job.location(), job.salary(), job.currency(), job.url(), job.importedAt(), job.lastCheckedAt(),
			job.status(), job.canonicalUrl(), job.applyEmail(), job.submittedBy(), job.description(),
			job.salaryMin(), job.salaryMax(), job.salaryPeriod(), job.salaryNotInformed(), details);
	}

	/** Column sizes of external_jobs (V40): title 500, the other free-text columns 255. */
	static final int TITLE_MAX = 500;
	static final int TEXT_MAX = 255;

	private int saveChunk(String platform, List<JobImportSource.ExternalJob> chunk) {
		int saved = 0;
		for (JobImportSource.ExternalJob external : chunk) {
			if (external.externalId() == null || external.title() == null) {
				continue; // sem id/titulo nao da para deduplicar nem mostrar: ignora
			}
			// Some feeds send HTML-escaped text ("hims &#038; hers"): store it as people read it. A job
			// already imported escaped heals on the next run (same source = upsert below).
			String title = fit(org.springframework.web.util.HtmlUtils.htmlUnescape(external.title()), TITLE_MAX);
			String company = external.company() == null ? null
				: fit(org.springframework.web.util.HtmlUtils.htmlUnescape(external.company()), TEXT_MAX);
			String location = fit(external.location(), TEXT_MAX);
			String salary = fit(external.salary(), TEXT_MAX);
			String canonicalUrl = com.socialcoisa.rede.jobs.domain.UrlCanonicalizer
				.canonicalize(external.url());
			// E-mail de candidatura: sai da descricao que a propria fonte publica (nulo quando nao ha).
			String applyEmail = ExternalJob.extractApplyEmail(external.description());
			com.socialcoisa.rede.jobs.domain.ExternalJobDetails details = detailsOf(external);
			var sameSource = repository.findBySource(platform, external.externalId());
			if (sameSource.isPresent()) {
				// Mesma fonte e mesmo id: atualiza os dados e a verificacao (upsert).
				ExternalJob current = sameSource.get();
				repository.save(new ExternalJob(current.id(), current.platform(), current.externalId(),
					title, company, location, salary,
					current.currency(), external.url(), current.importedAt(), clock.instant(),
					ExternalJob.FOUND, canonicalUrl, applyEmail, current.submittedBy(), current.description(),
					current.salaryMin(), current.salaryMax(), current.salaryPeriod(), current.salaryNotInformed(),
					details));
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
				// A procedência continua a do registro original; só a verificação avança.
				repository.save(new ExternalJob(original.id(), original.platform(), original.externalId(),
					original.title(), original.company(), original.location(), original.salary(),
					original.currency(), original.url(), original.importedAt(), clock.instant(),
					original.status(), original.canonicalUrl(), original.applyEmail(), original.submittedBy(),
					original.description(), original.salaryMin(), original.salaryMax(), original.salaryPeriod(),
					original.salaryNotInformed(), original.details().seenAt(clock.instant())));
				saved++;
				continue;
			}
			repository.save(new ExternalJob("ext_" + platform + "_" + external.externalId(), platform,
				external.externalId(), title, company, location,
				salary, null, external.url(), clock.instant(), clock.instant(), ExternalJob.FOUND,
				canonicalUrl, applyEmail, null, null, null, null, null, false, details));
			saved++;
		}
		return saved;
	}

	/**
	 * Traduz os metadados da fonte para o modelo interno. `lastSeenAt` é agora: a vaga acabou de
	 * ser vista nesta passada, e é isso que a expiração usa como sinal de vida.
	 */
	private com.socialcoisa.rede.jobs.domain.ExternalJobDetails detailsOf(JobImportSource.ExternalJob external) {
		JobImportSource.Meta meta = external.meta() == null ? JobImportSource.Meta.empty() : external.meta();
		return new com.socialcoisa.rede.jobs.domain.ExternalJobDetails(external.publishedAt(), external.employmentType(),
			meta.level(), meta.workModel(), meta.city(), meta.state(), meta.country(), meta.salaryMinAmount(),
			meta.salaryMaxAmount(), meta.salaryCurrency(), meta.salaryPeriodCode(), meta.attributionUrl(),
			clock.instant(), meta.expiresAt(), null);
	}

	/**
	 * Feeds are not bound by our column sizes (a "location" listing every country of a remote job
	 * reached 434 characters): an oversized value would fail the insert and roll back the whole chunk.
	 * Cut at the column size instead; the full text is still at the source URL.
	 */
	static String fit(String value, int max) {
		if (value == null || value.codePointCount(0, value.length()) <= max) {
			return value;
		}
		// Corte por code point: `substring` conta unidades UTF-16 e parte um emoji ao meio.
		String cortado = value.substring(0, value.offsetByCodePoints(0, max - 1)).stripTrailing();
		return cortado + "…";
	}
}
