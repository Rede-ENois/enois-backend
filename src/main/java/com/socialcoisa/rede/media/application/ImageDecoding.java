package com.socialcoisa.rede.media.application;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * Decodifica imagem com teto de pixels.
 *
 * <p>O tamanho do arquivo nao limita a memoria do decode: um PNG pequeno pode declarar milhoes de
 * pixels e fazer o {@code ImageIO.read} alocar centenas de MB. O leitor e consultado antes de
 * decodificar, entao uma imagem acima do teto e recusada sem alocar o bitmap.</p>
 */
public final class ImageDecoding {

	/** Mesmo teto usado pelo classificador de conteudo: 16 megapixels. */
	public static final long MAX_PIXELS = 16_000_000L;

	private ImageDecoding() {
	}

	/** Pixel size of an image as it is displayed (EXIF orientation applied). */
	public record Dimensions(int width, int height) {
	}

	/**
	 * Reads the size from the image header only, without decoding the bitmap. A JPEG whose EXIF
	 * orientation rotates by 90 degrees (5 to 8) is displayed with width and height swapped, so the
	 * result is swapped too. Returns null when no reader understands the bytes.
	 */
	public static Dimensions displayDimensions(byte[] data) throws IOException {
		try (ImageInputStream input = ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(data))) {
			if (input == null) {
				return null;
			}
			Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
			if (!readers.hasNext()) {
				return null;
			}
			ImageReader reader = readers.next();
			try {
				reader.setInput(input, true, true);
				int width = reader.getWidth(0);
				int height = reader.getHeight(0);
				int orientation = MediaMetadataSanitizer.jpegOrientation(data);
				return orientation >= 5 && orientation <= 8 ? new Dimensions(height, width)
					: new Dimensions(width, height);
			}
			finally {
				reader.dispose();
			}
		}
	}

	public static BufferedImage read(File file) throws IOException {
		try (ImageInputStream input = ImageIO.createImageInputStream(file)) {
			return read(input);
		}
	}

	public static BufferedImage read(InputStream stream) throws IOException {
		try (ImageInputStream input = ImageIO.createImageInputStream(stream)) {
			return read(input);
		}
	}

	private static BufferedImage read(ImageInputStream input) throws IOException {
		if (input == null) {
			return null;
		}
		Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
		if (!readers.hasNext()) {
			return null;
		}
		ImageReader reader = readers.next();
		try {
			reader.setInput(input, true, true);
			int width = reader.getWidth(0);
			int height = reader.getHeight(0);
			if ((long) width * height > MAX_PIXELS) {
				throw new IOException("Imagem acima do limite de " + MAX_PIXELS + " pixels.");
			}
			return reader.read(0);
		}
		finally {
			reader.dispose();
		}
	}
}
