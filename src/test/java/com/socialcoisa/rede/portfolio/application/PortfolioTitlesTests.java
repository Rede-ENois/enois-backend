package com.socialcoisa.rede.portfolio.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * O título que a lista pública mostra: o primeiro h1 (ou o primeiro h2), sem tags e sem entidades,
 * e nada além disso — a página é do dono e continua sendo HTML.
 */
class PortfolioTitlesTests {

	@Test
	void semPaginaNaoHaTitulo() {
		assertThat(PortfolioTitles.titleOf(null)).isNull();
		assertThat(PortfolioTitles.titleOf("  ")).isNull();
		assertThat(PortfolioTitles.titleOf("<p>sem cabecalho</p>")).isNull();
	}

	@Test
	void oH1VemAntesDoH2() {
		assertThat(PortfolioTitles.titleOf("<h2>Sub</h2><h1>Principal</h1>")).isEqualTo("Principal");
	}

	@Test
	void semH1OH2EOTitulo() {
		assertThat(PortfolioTitles.titleOf("<div><h2 class=\"x\">Meu trabalho</h2></div>"))
			.isEqualTo("Meu trabalho");
	}

	@Test
	void tagsSaoRemovidasEEspacosColapsam() {
		assertThat(PortfolioTitles.titleOf("<h1>  Ana   <strong>Silva</strong>  </h1>")).isEqualTo("Ana Silva");
	}

	@Test
	void entidadesViramSeusCaracteresNumaPassadaSo() {
		// `&amp;lt;` vira o texto `&lt;`, não uma tag: desfazer em duas passadas inventaria marcação.
		assertThat(PortfolioTitles.titleOf("<h1>Design &amp; dev &amp;lt;b&amp;gt;</h1>"))
			.isEqualTo("Design & dev &lt;b&gt;");
		assertThat(PortfolioTitles.titleOf("<h1>Top&nbsp;3 &#39;casos&#39;</h1>"))
			.isEqualTo("Top 3 'casos'");
	}

	@Test
	void tituloLongoECortadoComReticencias() {
		String title = PortfolioTitles.titleOf("<h1>" + "a".repeat(300) + "</h1>");

		assertThat(title).hasSize(PortfolioTitles.MAX_LENGTH);
		assertThat(title).endsWith("…");
	}

	@Test
	void h1VazioNaoInventaTitulo() {
		assertThat(PortfolioTitles.titleOf("<h1> </h1><p>x</p>")).isNull();
	}
}