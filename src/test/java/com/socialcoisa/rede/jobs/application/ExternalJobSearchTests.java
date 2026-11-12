package com.socialcoisa.rede.jobs.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.TestPropertySource;

import com.socialcoisa.rede.jobs.application.port.out.ExternalJobRepository;
import com.socialcoisa.rede.jobs.application.port.out.ExternalJobSearch;
import com.socialcoisa.rede.jobs.application.port.out.JobImportSource;
import com.socialcoisa.rede.jobs.domain.ExternalJob;

/**
 * Busca das vagas externas com filtros combinados (nível, modelo, localidade, salário), ordenação
 * e cursor — e o ciclo de expiração: vaga que sumiu da fonte sai das buscas sem perder a linha.
 */
@SpringBootTest
@TestPropertySource(properties = {
	"rede.jobs.import.jobradar.enabled=false",
	"rede.jobs.import.external-refresh-enabled=false"
})
class ExternalJobSearchTests {

	private static final String PLATFORM = "StructuredStub";
	private static final Instant START = Instant.parse("2026-11-10T12:00:00Z");

	/** Relógio que o teste avança: a expiração depende de tempo passar entre dois ciclos. */
	static final class MutableClock extends Clock {
		private Instant now = START;

		void reset() {
			now = START;
		}

		void advance(Duration duration) {
			now = now.plus(duration);
		}

		@Override public ZoneId getZone() { return ZoneOffset.UTC; }
		@Override public Clock withZone(ZoneId zone) { return this; }
		@Override public Instant instant() { return now; }
	}

	/** O que a fonte "publica" no ciclo atual — o teste troca entre ciclos. */
	static final class StubFeed {
		volatile List<JobImportSource.ExternalJob> jobs = List.of();
	}

	@TestConfiguration
	static class StubSourceConfig {
		@Bean
		@org.springframework.context.annotation.Primary
		MutableClock mutableClock() {
			return new MutableClock();
		}

		@Bean
		StubFeed stubFeed() {
			return new StubFeed();
		}

		@Bean
		JobImportSource structuredSource(StubFeed feed) {
			return new JobImportSource() {
				@Override public String name() { return PLATFORM; }
				@Override public boolean available() { return true; }
				@Override public List<ExternalJob> discover(int limit) { return feed.jobs; }
			};
		}
	}

	@Autowired
	private ExternalJobImportService importService;

	@Autowired
	private ExternalJobRepository repository;

	@Autowired
	private MutableClock clock;

	@Autowired
	private StubFeed feed;

	private static JobImportSource.ExternalJob senior() {
		return new JobImportSource.ExternalJob("s-1", "Desenvolvedor Java Sênior", "Acme", "São Paulo, SP",
			null, null, null, "https://stub.test/vaga/1", "https://stub.test/vaga/1", PLATFORM,
			Instant.parse("2026-11-08T10:00:00Z"),
			new JobImportSource.Meta("SENIOR", "HYBRID", "São Paulo", "SP", "Brasil",
				new BigDecimal("8000"), new BigDecimal("12000"), "BRL", "MONTH", "https://stub.test", null));
	}

	private static JobImportSource.ExternalJob junior() {
		return new JobImportSource.ExternalJob("s-2", "Frontend Júnior", "Beta", "Recife, PE", null, null,
			null, "https://stub.test/vaga/2", "https://stub.test/vaga/2", PLATFORM,
			Instant.parse("2026-11-09T09:00:00Z"),
			new JobImportSource.Meta("JUNIOR", "ONSITE", "Recife", "PE", "Brasil",
				null, null, null, null, null, null));
	}

	private static JobImportSource.ExternalJob devops() {
		return new JobImportSource.ExternalJob("s-3", "DevOps Remoto", "Gama", "Remote", null, null, null,
			"https://stub.test/vaga/3", "https://stub.test/vaga/3", PLATFORM,
			Instant.parse("2026-11-10T08:00:00Z"),
			new JobImportSource.Meta(null, "REMOTE", null, null, null,
				null, new BigDecimal("15000"), "USD", "MONTH", null, null));
	}

	/** O banco de teste é compartilhado entre os testes: cada um começa do zero nesta fonte. */
	@BeforeEach
	void reset() {
		clock.reset();
		feed.jobs = List.of(senior(), junior(), devops());
		repository.findByPlatform(PLATFORM, null, 100).forEach(job -> repository.delete(job.id()));
	}

	private List<ExternalJob> search(ExternalJobSearch search) {
		return repository.search(search);
	}

	private static ExternalJobSearch recent(int limit) {
		return new ExternalJobSearch(null, PLATFORM, null, null, null, null, null, null, null, null, null, null,
			ExternalJobSearch.SORT_RECENT, null, limit);
	}

	@Test
	void persistsTheStructuredMetadataTheSourceInformed() {
		importService.refresh();

		var job = repository.findBySource(PLATFORM, "s-1").orElseThrow();
		assertThat(job.details().level()).isEqualTo("SENIOR");
		assertThat(job.details().workModel()).isEqualTo("HYBRID");
		assertThat(job.details().city()).isEqualTo("São Paulo");
		assertThat(job.details().salaryMinAmount()).isEqualByComparingTo("8000");
		assertThat(job.details().salaryCurrency()).isEqualTo("BRL");
		assertThat(job.details().attributionUrl()).isEqualTo("https://stub.test");
		// A verificação de vida é o instante da importação.
		assertThat(job.details().lastSeenAt()).isEqualTo(clock.instant());
	}

	@Test
	void filtersByWorkModelSalaryFloorAndLevel() {
		importService.refresh();

		// Piso de 13000: só a vaga cuja faixa o atinge (sem salário informado não casa).
		var rich = search(new ExternalJobSearch(null, PLATFORM, null, null, null, null, null, null,
			null, new BigDecimal("13000"), null, null, ExternalJobSearch.SORT_RECENT, null, 20));
		assertThat(rich).extracting(ExternalJob::externalId).containsExactly("s-3");

		var remota = search(new ExternalJobSearch(null, PLATFORM, null, null, null, null, null, null,
			null, null, true, null, ExternalJobSearch.SORT_RECENT, null, 20));
		assertThat(remota).extracting(ExternalJob::externalId).containsExactly("s-3");

		var senior = search(new ExternalJobSearch(null, PLATFORM, null, "SENIOR", null, null, null,
			null, null, null, null, null, ExternalJobSearch.SORT_RECENT, null, 20));
		assertThat(senior).extracting(ExternalJob::externalId).containsExactly("s-1");
	}

	@Test
	void ordersBySalaryAndByRecency() {
		importService.refresh();

		var porSalario = search(new ExternalJobSearch(null, PLATFORM, null, null, null, null, null,
			null, null, null, null, null, ExternalJobSearch.SORT_SALARY, null, 20));
		// Maior faixa primeiro; as sem salário ficam depois.
		assertThat(porSalario).extracting(ExternalJob::externalId).containsExactly("s-3", "s-1", "s-2");

		var recentes = search(recent(20));
		assertThat(recentes).extracting(ExternalJob::externalId).containsExactly("s-3", "s-2", "s-1");
	}

	@Test
	void cursorPagesThroughRecentResults() {
		importService.refresh();

		var firstPage = search(recent(2));
		assertThat(firstPage).hasSize(2);

		Instant lastSeen = firstPage.getLast().details().publishedAt();
		var secondPage = search(new ExternalJobSearch(null, PLATFORM, null, null, null, null, null,
			null, null, null, null, null, ExternalJobSearch.SORT_RECENT, lastSeen, 2));
		assertThat(secondPage).extracting(ExternalJob::externalId).containsExactly("s-1");
	}

	@Test
	void jobsThatDisappearFromTheSourceLeaveTheSearchButKeepTheRow() {
		importService.refresh();
		assertThat(search(recent(20))).hasSize(3);

		// Cinco dias depois a fonte só publica a vaga 3: as outras duas passaram da janela de graça.
		clock.advance(Duration.ofDays(5));
		feed.jobs = List.of(devops());
		importService.refresh();

		assertThat(search(recent(20))).extracting(ExternalJob::externalId).containsExactly("s-3");
		// A linha continua lá, com a data em que foi removida — o histórico não se perde.
		var removed = repository.findBySource(PLATFORM, "s-1").orElseThrow();
		assertThat(removed.details().removedAt()).isEqualTo(clock.instant());
		assertThat(removed.active(clock.instant())).isFalse();
	}

	@Test
	void aJobSeenAgainComesBackToTheSearch() {
		importService.refresh();
		clock.advance(Duration.ofDays(5));
		feed.jobs = List.of(devops());
		importService.refresh();
		assertThat(search(recent(20))).extracting(ExternalJob::externalId).containsExactly("s-3");

		// A fonte volta a publicar a vaga 1: o upsert limpa a remoção e ela reaparece.
		clock.advance(Duration.ofDays(1));
		feed.jobs = List.of(senior(), devops());
		importService.refresh();
		assertThat(search(recent(20))).extracting(ExternalJob::externalId).contains("s-1");
		assertThat(repository.findBySource(PLATFORM, "s-1").orElseThrow().details().removedAt()).isNull();
	}
}
