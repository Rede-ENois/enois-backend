package com.socialcoisa.rede.moderation.adapters.out.net;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.moderation.domain.PageSummary;

/**
 * O leitor de destino: o que ele extrai da página e, principalmente, o que ele se RECUSA a visitar.
 * Quem publica escolhe o endereço, então o endereço é dado não confiável.
 */
class HttpLinkedPageReaderTest {

	private final HttpLinkedPageReader reader = new HttpLinkedPageReader();

	@Test
	void naoVisitaEnderecoDaPropriaRede() {
		assertThat(reader.isPublicDestination(URI.create("http://localhost:8080/admin"))).isFalse();
		assertThat(reader.isPublicDestination(URI.create("http://127.0.0.1/x"))).isFalse();
		assertThat(reader.isPublicDestination(URI.create("http://10.0.0.5/x"))).isFalse();
		assertThat(reader.isPublicDestination(URI.create("http://192.168.1.1/x"))).isFalse();
		assertThat(reader.isPublicDestination(URI.create("http://172.16.0.1/x"))).isFalse();
		assertThat(reader.isPublicDestination(URI.create("http://[::1]/x"))).isFalse();
	}

	@Test
	void naoVisitaOCaminhoDeMetadadoDaNuvem() {
		// 169.254.169.254 é o endereço de metadado das nuvens — o alvo clássico de SSRF.
		assertThat(reader.isPublicDestination(URI.create("http://169.254.169.254/latest/meta-data/"))).isFalse();
	}

	@Test
	void naoVisitaEsquemaQueNaoSejaHttp() {
		assertThat(reader.isPublicDestination(URI.create("file:///etc/passwd"))).isFalse();
		assertThat(reader.isPublicDestination(URI.create("ftp://exemplo.com/a"))).isFalse();
		assertThat(reader.isPublicDestination(URI.create("gopher://exemplo.com/a"))).isFalse();
	}

	@Test
	void naoVisitaPortaForaDeHttpEHttps() {
		assertThat(reader.isPublicDestination(URI.create("http://exemplo.com:22/x"))).isFalse();
		assertThat(reader.isPublicDestination(URI.create("http://exemplo.com:6379/x"))).isFalse();
	}

	@Test
	void aceitaDestinoPublicoDeVerdade() {
		assertThat(reader.isPublicDestination(URI.create("https://example.com/artigo"))).isTrue();
		assertThat(reader.isPublicDestination(URI.create("https://www.youtube.com/watch?v=abc"))).isTrue();
	}

	@Test
	void leTituloEDescricaoDoOpenGraph() {
		String html = """
			<html><head>
			<title>Titulo do HTML</title>
			<meta property="og:title" content="Titulo do video">
			<meta property="og:description" content="A descricao publica">
			<meta name="description" content="descricao comum">
			</head><body>corpo</body></html>
			""";

		PageSummary resumo = HttpLinkedPageReader.summaryOf(URI.create("https://exemplo.com/v"), html);

		assertThat(resumo.title()).isEqualTo("Titulo do video");
		assertThat(resumo.description()).isEqualTo("A descricao publica");
	}

	@Test
	void caiParaOTituloDoHtmlQuandoNaoHaOpenGraph() {
		String html = "<html><head><title>  Um   artigo  </title>"
			+ "<meta name=\"description\" content=\"resumo do artigo\"></head></html>";

		PageSummary resumo = HttpLinkedPageReader.summaryOf(URI.create("https://exemplo.com/a"), html);

		assertThat(resumo.title()).isEqualTo("Um artigo");
		assertThat(resumo.description()).isEqualTo("resumo do artigo");
	}

	@Test
	void paginaSemTituloNemDescricaoNaoTemResumo() {
		PageSummary resumo = HttpLinkedPageReader.summaryOf(URI.create("https://exemplo.com/x"),
			"<html><body>nada</body></html>");

		assertThat(resumo.hasAnything()).isFalse();
	}

	@Test
	void decodificaEntidadesDoHtml() {
		String html = "<title>A &amp; B &lt;teste&gt;</title>";

		assertThat(HttpLinkedPageReader.summaryOf(URI.create("https://exemplo.com/x"), html).title())
			.isEqualTo("A & B <teste>");
	}

}
