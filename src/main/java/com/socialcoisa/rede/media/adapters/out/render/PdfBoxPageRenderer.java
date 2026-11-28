package com.socialcoisa.rede.media.adapters.out.render;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.rendering.PDFRenderer;

import com.socialcoisa.rede.media.application.port.out.MediaPageRenderer;

/**
 * Renderiza páginas de PDF com o PDFBox, já declarado no projeto (ele também limpa os metadados do
 * PDF na subida).
 *
 * <p>O desenho vai para uma imagem RGB opaca de propósito: PDF transparente virava PNG com fundo
 * preto, e o classificador olha a imagem renderizada, não o arquivo original — o que ele vê tem de
 * ser o que a pessoa veria na tela.</p>
 */
public class PdfBoxPageRenderer implements MediaPageRenderer {

	/** Lado maior de cada página renderizada, em pixels. */
	static final int MAX_SIDE = 1000;

	@Override
	public List<byte[]> renderPages(byte[] pdf) throws PageRenderException {
		try (PDDocument document = Loader.loadPDF(pdf)) {
			PDFRenderer renderer = new PDFRenderer(document);
			int total = Math.min(document.getNumberOfPages(), MAX_PAGES);
			List<byte[]> pages = new ArrayList<>(total);
			for (int page = 0; page < total; page++) {
				pages.add(render(renderer, page));
			}
			return pages;
		}
		catch (InvalidPasswordException failure) {
			throw new PageRenderException("pdf protegido por senha", failure);
		}
		catch (IOException | RuntimeException failure) {
			throw new PageRenderException("pdf nao abriu", failure);
		}
	}

	private static byte[] render(PDFRenderer renderer, int page) {
		try {
			BufferedImage drawn = renderer.renderImageWithDPI(page, 96);
			double factor = Math.min(1.0, (double) MAX_SIDE / Math.max(drawn.getWidth(), drawn.getHeight()));
			int width = Math.max(1, (int) Math.round(drawn.getWidth() * factor));
			int height = Math.max(1, (int) Math.round(drawn.getHeight() * factor));
			BufferedImage opaque = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
			Graphics2D graphics = opaque.createGraphics();
			try {
				graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
					RenderingHints.VALUE_INTERPOLATION_BILINEAR);
				graphics.setColor(java.awt.Color.WHITE);
				graphics.fillRect(0, 0, width, height);
				graphics.drawImage(drawn, 0, 0, width, height, null);
			}
			finally {
				graphics.dispose();
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			if (!ImageIO.write(opaque, "png", out)) {
				throw new IOException("png indisponivel");
			}
			return out.toByteArray();
		}
		catch (IOException failure) {
			throw new IllegalStateException(failure);
		}
	}
}
