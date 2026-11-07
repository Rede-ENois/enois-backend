package com.socialcoisa.rede.jobs.adapters.out.imports;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;


/** Sem a API key do JobRadar, a fonte fica indisponivel e nao busca por caminhos nao autorizados. */
class JobRadarJobSourceTests {

	private final JobRadarJobSource source = new JobRadarJobSource(
		"https://jobradar.com.br/sitemap-vagas.xml", "EnoisBot/1.0", true);

	@Test
	void theSitemapSourceIsPublicAndAvailable() {
		assertThat(source.name()).isEqualTo("JobRadar");
		assertThat(source.available()).isTrue();
	}

	@Test
	void parsesJobsFromSitemapDerivingTitleFromSlug() {
		String xml = """
				<?xml version="1.0" encoding="UTF-8"?>
				<urlset>
				  <url><loc>https://jobradar.com.br/vaga/dev-java-senior-remoto-123456</loc></url>
				  <url><loc>https://jobradar.com.br/vaga/designer-de-produto-987654</loc></url>
				  <url><loc>https://jobradar.com.br/pagina-institucional</loc></url>
				</urlset>""";

		var jobs = source.parse(xml, 10);

		// A pagina institucional (sem /vaga/) e ignorada; nada de dado inventado.
		assertThat(jobs).hasSize(2);
		assertThat(jobs.get(0).title()).isEqualTo("Dev Java Senior Remoto");
		assertThat(jobs.get(0).externalId()).isEqualTo("123456");
		assertThat(jobs.get(0).url()).isEqualTo("https://jobradar.com.br/vaga/dev-java-senior-remoto-123456");
		assertThat(jobs.get(0).platform()).isEqualTo("JobRadar");
		// Empresa/descricao nao estao no sitemap: ficam nulos (a candidatura ocorre no JobRadar).
		assertThat(jobs.get(0).company()).isNull();
		assertThat(jobs.get(0).description()).isNull();
	}

	@Test
	void cleansRealSlugs() {
		// Exemplos reais do sitemap do JobRadar (2026-10-16).
		assertThat(JobRadarJobSource.humanize(
			"professor-a-autor-a-curadoria-e-validacao-de-conteudo-sommelieria-vinhos-do-mundo-e-terroir-edtech-vitru-vitru-educacao"))
			.isEqualTo("Professor (a) Autor (a) Curadoria e Validacao de Conteudo Sommelieria Vinhos do Mundo e Terroir Vitru Educacao");
		assertThat(JobRadarJobSource.humanize("estagiario-a-de-educacao-fisica-campo-grande-silva-gym"))
			.isEqualTo("Estagiario (a) de Educacao Fisica Campo Grande Silva Gym");
	}

	@Test
	void respectsTheLimit() {
		String xml = """
				<urlset>
				  <url><loc>https://jobradar.com.br/vaga/a-1</loc></url>
				  <url><loc>https://jobradar.com.br/vaga/b-2</loc></url>
				  <url><loc>https://jobradar.com.br/vaga/c-3</loc></url>
				</urlset>""";
		assertThat(source.parse(xml, 2)).hasSize(2);
	}
}
