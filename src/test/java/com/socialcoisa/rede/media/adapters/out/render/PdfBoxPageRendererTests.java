package com.socialcoisa.rede.media.adapters.out.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import javax.imageio.ImageIO;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.media.application.port.out.MediaPageRenderer;
import com.socialcoisa.rede.media.application.port.out.MediaPageRenderer.PageRenderException;

/**
 * O render é a única coisa que o classificador de conteúdo consegue olhar de um PDF: se a página não
 * sai como imagem legível, o documento não tem como ser conferido.
 */
public class PdfBoxPageRendererTests {

	private final PdfBoxPageRenderer renderer = new PdfBoxPageRenderer();

	@Test
	void desenhaUmaImagemPngPorPagina() throws Exception {
		byte[] pdf = pdfCom(3, false);

		List<byte[]> pages = renderer.renderPages(pdf);

		assertThat(pages).hasSize(3);
		for (byte[] page : pages) {
			assertThat(ImageIO.read(new java.io.ByteArrayInputStream(page))).isNotNull();
			// PNG de verdade: assinatura fixa, reconhecida pelo classificador como imagem.
			assertThat(java.util.Arrays.copyOf(page, 8))
				.isEqualTo(new byte[] { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A });
		}
	}

	@Test
	void naoRenderizaMaisQueOTetoDePaginas() throws Exception {
		// O teto protege o pod de um PDF de 300 folhas; o excedente fica sem página de propósito.
		byte[] pdf = pdfCom(MediaPageRenderer.MAX_PAGES + 5, false);

		assertThat(renderer.renderPages(pdf)).hasSize(MediaPageRenderer.MAX_PAGES);
	}

	@Test
	void arquivoQueNaoEPdfFalhaEmVezDePassarVazio() {
		byte[] lixo = "%PDF-1.7 isto nao e um documento".getBytes(java.nio.charset.StandardCharsets.UTF_8);

		assertThatThrownBy(() -> renderer.renderPages(lixo)).isInstanceOf(PageRenderException.class);
	}

	@Test
	void pdfProtegidoPorSenhaFalha() throws Exception {
		byte[] pdf = pdfCom(1, true);

		assertThatThrownBy(() -> renderer.renderPages(pdf)).isInstanceOf(PageRenderException.class);
	}

	public static byte[] pdfCom(int paginas, boolean protegidoPorSenha) throws IOException {
		try (PDDocument document = new PDDocument()) {
			for (int page = 0; page < paginas; page++) {
				document.addPage(new PDPage(PDRectangle.A4));
			}
			if (protegidoPorSenha) {
				AccessPermission permission = new AccessPermission();
				permission.setCanPrint(false);
				StandardProtectionPolicy policy = new StandardProtectionPolicy("dono-da-senha",
					"senha-do-leitor", permission);
				document.protect(policy);
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			document.save(out);
			return out.toByteArray();
		}
	}
}
