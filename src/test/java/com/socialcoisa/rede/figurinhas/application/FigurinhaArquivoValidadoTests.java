package com.socialcoisa.rede.figurinhas.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.shared.web.ApiException;

/** O que sai do arquivo recebido: só uma imagem inteira, dentro do tamanho, sem sobra. */
class FigurinhaArquivoValidadoTests {

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

	private static void esperarRecusa(byte[] bytes, String mime, String codigo) {
		assertThatThrownBy(() -> FigurinhaArquivoValidado.conferir(bytes, mime))
			.isInstanceOf(ApiException.class)
			.satisfies(erro -> assertThat(((ApiException) erro).code()).isEqualTo(codigo));
	}

	private static byte[] png(int largura, int altura) throws Exception {
		BufferedImage imagem = new BufferedImage(largura, altura, BufferedImage.TYPE_INT_ARGB);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(imagem, "png", out);
		return out.toByteArray();
	}
}
