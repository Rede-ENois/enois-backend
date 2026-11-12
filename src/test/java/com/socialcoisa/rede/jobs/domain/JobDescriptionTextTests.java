package com.socialcoisa.rede.jobs.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * A descrição da fonte vira texto legível: o HTML de terceiro não chega à tela, e o que a fonte
 * escreveu não se perde.
 */
class JobDescriptionTextTests {

	@Test
	void turnsBlockHtmlIntoReadableLines() {
		String text = JobDescriptionText.fromHtml(
			"<p>Buscamos <b>dev</b> backend.</p><ul><li>Java</li><li>Spring</li></ul>");

		assertThat(text).isEqualTo("Buscamos dev backend.\nJava\nSpring");
	}

	@Test
	void keepsLineBreaksTheSourceAlreadyHad() {
		assertThat(JobDescriptionText.fromHtml("Linha um\nLinha dois")).isEqualTo("Linha um\nLinha dois");
	}

	@Test
	void dropsScriptAndStyleContent() {
		String text = JobDescriptionText.fromHtml(
			"<p>Vaga real</p><script>alert('x')</script><style>.a{color:red}</style>");

		assertThat(text).isEqualTo("Vaga real");
	}

	@Test
	void decodesEntitiesAsPeopleReadThem() {
		assertThat(JobDescriptionText.fromHtml("Pesquisa &amp; desenvolvimento")).isEqualTo("Pesquisa & desenvolvimento");
	}

	@Test
	void returnsNullWhenTheSourcePublishedNothing() {
		assertThat(JobDescriptionText.fromHtml(null)).isNull();
		assertThat(JobDescriptionText.fromHtml("   ")).isNull();
		assertThat(JobDescriptionText.fromHtml("<p> </p>")).isNull();
	}
}
