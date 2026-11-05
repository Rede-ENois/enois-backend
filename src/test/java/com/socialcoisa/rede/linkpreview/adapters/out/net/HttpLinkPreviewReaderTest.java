package com.socialcoisa.rede.linkpreview.adapters.out.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.linkpreview.domain.ExternalLinkPreview;
import com.socialcoisa.rede.shared.security.OutboundUrlGuard;

/**
 * O leitor da miniatura externa: o que ele extrai do cabecalho da pagina e, principalmente, o que
 * ele se RECUSA a visitar. Quem escreve o post escolhe o endereco, entao o endereco e dado nao
 * confiavel.
 */
class HttpLinkPreviewReaderTest {

	private final OutboundUrlGuard guard = mock(OutboundUrlGuard.class);

	private final HttpLinkPreviewReader reader = new HttpLinkPreviewReader(guard);

	@Test
	void enderecoRecusadoPelaGuardaNaoChegaNaInternet() {
		when(guard.safe(anyString())).thenReturn(false);

		Optional<ExternalLinkPreview> preview = reader.read("http://169.254.169.254/latest/meta-data/");

		assertThat(preview).isEmpty();
		verify(guard).safe("http://169.254.169.254/latest/meta-data/");
		verify(guard, never()).safe("http://169.254.169.255/latest/meta-data/");
	}

	@Test
	void enderecoInvalidoNemPassaPelaGuarda() {
		assertThat(reader.read("nao e um endereco")).isEmpty();
		assertThat(reader.read(null)).isEmpty();
		verify(guard, never()).safe(anyString());
	}

	@Test
	void leTituloDescricaoImagemENomeDoSiteDoOpenGraph() {
		String html = """
			<html><head>
			<title>Titulo do HTML</title>
			<meta property="og:title" content="Um artigo bom">
			<meta property="og:description" content="A descricao publica &amp; util">
			<meta property="og:image" content="/capa.png">
			<meta property="og:site_name" content="Jornal">
			</head><body>corpo que nao interessa</body></html>
			""";

		ExternalLinkPreview preview = HttpLinkPreviewReader.parse(URI.create("https://jornal.com/artigo"), html);

		assertThat(preview.title()).isEqualTo("Um artigo bom");
		assertThat(preview.description()).isEqualTo("A descricao publica & util");
		assertThat(preview.imageUrl()).isEqualTo("https://jornal.com/capa.png");
		assertThat(preview.siteName()).isEqualTo("Jornal");
		assertThat(preview.url()).isEqualTo("https://jornal.com/artigo");
	}

	@Test
	void semOpenGraphCaiParaTituloEDescricaoDoHtml() {
		String html = "<html><head><title>So o titulo</title>"
			+ "<meta name=\"description\" content=\"Descricao comum\"></head></html>";

		ExternalLinkPreview preview = HttpLinkPreviewReader.parse(URI.create("https://exemplo.com/x"), html);

		assertThat(preview.title()).isEqualTo("So o titulo");
		assertThat(preview.description()).isEqualTo("Descricao comum");
		assertThat(preview.imageUrl()).isNull();
	}

	@Test
	void imagemDeOutroEsquemaEAbsolutaForaDoHttpSaoDescartadas() {
		String javascript = "<html><head><meta property=\"og:image\" content=\"javascript:alert(1)\"></head></html>";
		String data = "<html><head><meta property=\"og:image\" content=\"data:image/png;base64,AAAA\"></head></html>";

		assertThat(HttpLinkPreviewReader.parse(URI.create("https://exemplo.com/x"), javascript).imageUrl()).isNull();
		assertThat(HttpLinkPreviewReader.parse(URI.create("https://exemplo.com/x"), data).imageUrl()).isNull();
	}

	@Test
	void cabecalhoSemNadaNaoViraCartao() {
		ExternalLinkPreview preview = HttpLinkPreviewReader.parse(URI.create("https://exemplo.com/x"),
			"<html><head></head><body></body></html>");

		assertThat(preview.hasAnything()).isFalse();
	}

	@Test
	void textoLongoEDescricaoComEspacosSaoNormalizados() {
		String html = "<html><head><meta property=\"og:title\" content=\"Linha\n   quebrada\"></head></html>";

		ExternalLinkPreview preview = HttpLinkPreviewReader.parse(URI.create("https://exemplo.com/x"), html);

		assertThat(preview.title()).isEqualTo("Linha quebrada");
	}
}
