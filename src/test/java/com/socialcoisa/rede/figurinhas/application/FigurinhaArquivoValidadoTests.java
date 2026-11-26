package com.socialcoisa.rede.figurinhas.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.metadata.IIOMetadata;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.shared.web.ApiException;

/** O que sai do arquivo recebido: só uma imagem inteira, dentro do tamanho, sem sobra. */
class FigurinhaArquivoValidadoTests {

	/** Figurinha desenhada com script, evento e link para fora: só o desenho sobrevive. */
	private static final String SVG_SUJO = """
		<?xml version="1.0" encoding="UTF-8"?>
		<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64">
		  <script>alert(1)</script>
		  <circle cx="32" cy="32" r="30" fill="red" onclick="alert(2)"/>
		  <a href="https://fora.exemplo/x"><text x="4" y="16">oi</text></a>
		</svg>
		""";

	@Test
	void pngValidoPassaComAsDimensoesReais() throws Exception {
		FigurinhaArquivoValidado.Validado validado = FigurinhaArquivoValidado.conferir(png(80, 40), "image/png");
		assertThat(validado.mimeType()).isEqualTo("image/png");
		assertThat(validado.largura()).isEqualTo(80);
		assertThat(validado.altura()).isEqualTo(40);
		assertThat(ImageIO.read(new ByteArrayInputStream(validado.bytes()))).isNotNull();
	}

	@Test
	void tipoDeclaradoPrecisaBaterComAAssinatura() throws Exception {
		esperarRecusa(png(32, 32), "image/jpeg", "FIGURINHA_IMAGEM_INVALIDA");
		// GIF e SVG também não podem vir declarados como PNG só por causa do nome do arquivo.
		esperarRecusa(gif(32, 32, 1), "image/png", "FIGURINHA_IMAGEM_INVALIDA");
		esperarRecusa(SVG_SUJO.getBytes(StandardCharsets.UTF_8), "image/gif", "FIGURINHA_IMAGEM_INVALIDA");
	}

	@Test
	void htmlSvgPdfEZipSaoRecusados() {
		for (String corpo : new String[] { "<html><body>x</body></html>", "<?xml version=\"1.0\"?><svg/>",
				"%PDF-1.7", "\u0000PK\u0003\u0004arquivo" }) {
			esperarRecusa(corpo.getBytes(StandardCharsets.ISO_8859_1), "image/png", "FIGURINHA_IMAGEM_INVALIDA");
		}
	}

	@Test
	void pngComHtmlColadoNoFimEhRecusado() throws Exception {
		byte[] png = png(32, 32);
		byte[] html = "<script>alert(1)</script>".getBytes(StandardCharsets.US_ASCII);
		byte[] poliglota = new byte[png.length + html.length];
		System.arraycopy(png, 0, poliglota, 0, png.length);
		System.arraycopy(html, 0, poliglota, png.length, html.length);
		esperarRecusa(poliglota, "image/png", "FIGURINHA_IMAGEM_INVALIDA");
	}

	@Test
	void imagemAcimaDoLadoMaximoEhRecusada() throws Exception {
		esperarRecusa(png(401, 32), "image/png", "FIGURINHA_MUITO_GRANDE");
		esperarRecusa(gif(401, 32, 1), "image/gif", "FIGURINHA_MUITO_GRANDE");
		esperarRecusa(SVG_SUJO.replace("0 0 64 64", "0 0 401 64").getBytes(StandardCharsets.UTF_8),
			"image/svg+xml", "FIGURINHA_MUITO_GRANDE");
	}

	@Test
	void imagemPequenaDemaisEhRecusada() throws Exception {
		esperarRecusa(png(8, 8), "image/png", "FIGURINHA_MUITO_PEQUENA");
	}

	@Test
	void arquivoAcimaDoTetoEhRecusadoAntesDeLer() {
		esperarRecusa(new byte[600_000], "image/png", "FIGURINHA_ARQUIVO_GRANDE");
	}

	@Test
	void arquivoVazioEhRecusado() {
		esperarRecusa(new byte[0], "image/png", "FIGURINHA_IMAGEM_INVALIDA");
	}

	// ------------------------------------------------------------------ GIF

	@Test
	void gifAnimadoPassaInteiroComAsDimensoesDoPrimeiroQuadro() throws Exception {
		byte[] gif = gif(64, 48, 2);

		FigurinhaArquivoValidado.Validado validado = FigurinhaArquivoValidado.conferir(gif, "image/gif");

		assertThat(validado.mimeType()).isEqualTo("image/gif");
		assertThat(validado.largura()).isEqualTo(64);
		assertThat(validado.altura()).isEqualTo(48);
		// Os quadros são o conteúdo: o arquivo gravado é o mesmo GIF, com o fim próprio (0x3B).
		assertThat(validado.bytes()).isEqualTo(gif);
	}

	@Test
	void gifTruncadoEhRecusado() throws Exception {
		byte[] gif = gif(32, 32, 1);
		esperarRecusa(java.util.Arrays.copyOf(gif, gif.length / 2), "image/gif", "FIGURINHA_IMAGEM_INVALIDA");
	}

	@Test
	void gifSemVersaoNoCabecalhoEhRecusado() throws Exception {
		byte[] gif = gif(32, 32, 1);
		gif[4] = '4';
		esperarRecusa(gif, "image/gif", "FIGURINHA_IMAGEM_INVALIDA");
	}

	// ------------------------------------------------------------------ SVG

	@Test
	void svgPassaSanitizadoEComAsMedidasDoViewBox() {
		FigurinhaArquivoValidado.Validado validado = FigurinhaArquivoValidado.conferir(
			SVG_SUJO.getBytes(StandardCharsets.UTF_8), "image/svg+xml");

		String limpo = new String(validado.bytes(), StandardCharsets.UTF_8);
		assertThat(validado.mimeType()).isEqualTo("image/svg+xml");
		assertThat(validado.largura()).isEqualTo(64);
		assertThat(validado.altura()).isEqualTo(64);
		assertThat(limpo).contains("<svg").contains("<circle").contains("xmlns=\"http://www.w3.org/2000/svg\"");
		assertThat(limpo).doesNotContain("script").doesNotContain("onclick");
	}

	@Test
	void svgUsaWidthEHeightQuandoExistem() {
		String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"40\" height=\"24\" viewBox=\"0 0 400 240\">"
			+ "<rect width=\"40\" height=\"24\"/></svg>";
		FigurinhaArquivoValidado.Validado validado = FigurinhaArquivoValidado.conferir(
			svg.getBytes(StandardCharsets.UTF_8), "image/svg+xml");
		assertThat(validado.largura()).isEqualTo(40);
		assertThat(validado.altura()).isEqualTo(24);
	}

	@Test
	void svgSemMedidaNenhumaEhRecusado() {
		// Sem width/height nem viewBox não há como saber o tamanho na tela.
		String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><circle cx=\"8\" cy=\"8\" r=\"4\"/></svg>";
		esperarRecusa(svg.getBytes(StandardCharsets.UTF_8), "image/svg+xml", "FIGURINHA_IMAGEM_INVALIDA");
	}

	@Test
	void svgQueNaoEhSvgEhRecusado() {
		String html = "<html><body><svg viewBox=\"0 0 10 10\"></svg></body></html>";
		esperarRecusa(html.getBytes(StandardCharsets.UTF_8), "image/svg+xml", "FIGURINHA_IMAGEM_INVALIDA");
	}

	private static void esperarRecusa(byte[] bytes, String mime, String codigo) {
		assertThatThrownBy(() -> FigurinhaArquivoValidado.conferir(bytes, mime))
			.isInstanceOf(ApiException.class)
			.satisfies(erro -> assertThat(((ApiException) erro).code()).isEqualTo(codigo));
	}

	private static byte[] png(int largura, int altura) throws Exception {
		BufferedImage imagem = new BufferedImage(largura, altura, BufferedImage.TYPE_INT_RGB);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(imagem, "png", out);
		return out.toByteArray();
	}

	/**
	 * GIF de verdade, escrito pelo ImageIO: um quadro por imagem da sequência. Com mais de um quadro
	 * o arquivo sai como GIF89a animado, que é o caso que os leitores e o catálogo precisam aceitar.
	 */
	private static byte[] gif(int largura, int altura, int quadros) throws Exception {
		IIOMetadata metadata = null;
		javax.imageio.ImageWriter escritor = ImageIO.getImageWritersByFormatName("gif").next();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ImageOutputStream saida = ImageIO.createImageOutputStream(out)) {
			escritor.setOutput(saida);
			escritor.prepareWriteSequence(null);
			for (int quadro = 0; quadro < quadros; quadro++) {
				BufferedImage imagem = new BufferedImage(largura, altura, BufferedImage.TYPE_INT_RGB);
				imagem.getGraphics().fillRect(0, 0, largura, altura);
				escritor.writeToSequence(new IIOImage(imagem, null, metadata), null);
				if (metadata == null) {
					metadata = escritor.getDefaultImageMetadata(
						new javax.imageio.ImageTypeSpecifier(imagem), null);
				}
			}
			escritor.endWriteSequence();
		}
		finally {
			escritor.dispose();
		}
		return out.toByteArray();
	}
}
