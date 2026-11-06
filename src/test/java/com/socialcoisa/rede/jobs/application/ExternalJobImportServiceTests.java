package com.socialcoisa.rede.jobs.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.TestPropertySource;

import com.socialcoisa.rede.jobs.application.port.out.ExternalJobRepository;
import com.socialcoisa.rede.jobs.application.port.out.JobImportSource;

/**
 * Importa as vagas externas para a tabela PROPRIA (external_jobs) e nao duplica ao rodar de novo
 * (dedupe por plataforma + id externo).
 */
@SpringBootTest
@TestPropertySource(properties = {
	"rede.jobs.import.jobradar.enabled=false",
	// Desliga o seed/cron: no teste o refresh e chamado explicitamente (evita corrida paralela).
	"rede.jobs.import.external-refresh-enabled=false"
})
class ExternalJobImportServiceTests {

	@TestConfiguration
	static class StubSourceConfig {
		@Bean
		JobImportSource stubImportSource() {
			return new JobImportSource() {
				@Override public String name() { return "StubSource"; }
				@Override public boolean available() { return true; }
				@Override public List<ExternalJob> discover(int limit) {
					return List.of(
						new ExternalJob("ext-1", "Vaga Externa Um", "Empresa X", "Remoto", null, null, null,
							"https://exemplo.test/vaga/1", "https://exemplo.test/vaga/1", name(), null),
						new ExternalJob("ext-2", "Vaga Externa Dois", null, null, null, null, null,
							"https://exemplo.test/vaga/2", "https://exemplo.test/vaga/2", name(), null),
						// Some feeds send HTML-escaped text.
						new ExternalJob("ext-3", "Dev &amp; Ops", "hims &#038; hers", "Remoto", null, null, null,
							"https://exemplo.test/vaga/3", "https://exemplo.test/vaga/3", name(), null),
						// Agregador manda a lista de paises inteira no campo de localizacao: mais
						// caracteres do que a coluna aceita.
						new ExternalJob("ext-4", "Vaga Global", "Empresa Y", "Argentina, Australia, Austria, "
							+ "Belgium, Brazil, Bulgaria, Canada, China, Colombia, Croatia, Czechia, Denmark, "
							+ "Egypt, Estonia, Finland, France, Germany, Greece, Hungary, Iceland, India, "
							+ "Indonesia, Ireland, Israel, Italy, Japan, Kenya, Latvia, Lithuania, Luxembourg, "
							+ "Malaysia, Mexico, Netherlands, New Zealand, Nigeria, Norway, Pakistan, Peru, "
							+ "Philippines, Poland, Portugal, Romania, Serbia, Singapore, Slovakia, Slovenia, "
							+ "South Africa, Spain, Sweden, Switzerland, Thailand, Turkey, Ukraine, Vietnam",
							null, null, null, "https://exemplo.test/vaga/4", "https://exemplo.test/vaga/4",
							name(), null));
				}
			};
		}
	}

	@Autowired
	private ExternalJobImportService importService;

	@Autowired
	private ExternalJobRepository repository;

	@Test
	void importsIntoItsOwnTableAndDoesNotDuplicateOnRerun() {
		assertThat(importService.refresh()).isGreaterThanOrEqualTo(3);

		var imported = repository.findBySource("StubSource", "ext-1");
		assertThat(imported).isPresent();
		assertThat(imported.get().url()).isEqualTo("https://exemplo.test/vaga/1");
		String id = imported.get().id();

		importService.refresh();

		// Reimportar nao cria outra linha (mesmo id) e nada foi duplicado.
		assertThat(repository.findBySource("StubSource", "ext-1").orElseThrow().id()).isEqualTo(id);
		// O banco de teste e compartilhado: conta so as vagas DESTA fonte (o stub), nao o total.
		long fromStub = repository.findRecent(200).stream()
			.filter(j -> "StubSource".equals(j.platform())).count();
		assertThat(fromStub).isEqualTo(4);
	}

	@Test
	void localizacaoMaiorQueAColunaNaoDerrubaOLote() {
		importService.refresh();

		var job = repository.findBySource("StubSource", "ext-4").orElseThrow();
		assertThat(job.location()).hasSize(255);
		assertThat(job.location()).startsWith("Argentina, Australia");
	}

	@Test
	void storesTheTextAsPeopleReadIt() {
		importService.refresh();

		var job = repository.findBySource("StubSource", "ext-3").orElseThrow();
		assertThat(job.company()).isEqualTo("hims & hers");
		assertThat(job.title()).isEqualTo("Dev & Ops");
	}
}
