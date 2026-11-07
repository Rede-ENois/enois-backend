package com.socialcoisa.rede.media.application;

import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/**
 * JPEG metadata removal: drops EXIF/XMP (APP1), IPTC (APP13), comments and exotic APP segments,
 * keeping JFIF/ICC/Adobe. A JPEG with an EXIF orientation is physically rotated before the block
 * goes away, otherwise a phone photo would display sideways.
 */
final class JpegMetadata {

	private JpegMetadata() {
	}

	static byte[] sanitize(byte[] data) throws IOException {
		int orientation = orientation(data);
		if (orientation > 1) {
			ImageDecoding.Dimensions size = ImageDecoding.displayDimensions(data);
			if (size != null && (long) size.width() * size.height() <= ImageDecoding.MAX_PIXELS) {
				byte[] rotated = rotate(data, orientation);
				if (rotated != null) {
					return rotated;
				}
			}
			// Too large to re-encode within the pixel budget: drop the metadata but keep a bare
			// orientation tag, so the photo still displays upright and no other EXIF field survives.
			return withOrientationOnly(stripSegments(data), orientation);
		}
		return stripSegments(data);
	}

	/**
	 * Lê a tag Orientation (0x0112) do bloco APP1/EXIF sem biblioteca externa. Se o JPEG não tiver
	 * EXIF válido, devolve 1 (sem rotação).
	 */
	static int orientation(byte[] data) {
		if (data.length < 4 || (data[0] & 0xFF) != 0xFF || (data[1] & 0xFF) != 0xD8) {
			return 1;
		}
		int index = 2;
		while (index + 4 < data.length) {
			if ((data[index] & 0xFF) != 0xFF) {
				return 1;
			}
			int marker = data[index + 1] & 0xFF;
			if (marker == 0xDA || marker == 0xD9) {
				return 1;
			}
			int length = ((data[index + 2] & 0xFF) << 8) | (data[index + 3] & 0xFF);
			if (length < 2 || index + 2 + length > data.length) {
				return 1;
			}
			int start = index + 4;
			if (marker == 0xE1 && length >= 8
					&& data[start] == 'E' && data[start + 1] == 'x' && data[start + 2] == 'i'
					&& data[start + 3] == 'f' && data[start + 4] == 0 && data[start + 5] == 0) {
				return exifOrientation(data, start + 6, index + 2 + length);
			}
			index += 2 + length;
		}
		return 1;
	}

	private static int exifOrientation(byte[] data, int tiff, int end) {
		if (tiff + 8 > end) {
			return 1;
		}
		boolean little = data[tiff] == 'I' && data[tiff + 1] == 'I';
		boolean big = data[tiff] == 'M' && data[tiff + 1] == 'M';
		if (!little && !big) {
			return 1;
		}
		int magic = readShort(data, tiff + 2, little);
		if (magic != 42) {
			return 1;
		}
		int ifd = tiff + readIntEndian(data, tiff + 4, little);
		if (ifd < tiff || ifd + 2 > end) {
			return 1;
		}
		int entries = readShort(data, ifd, little);
		for (int i = 0; i < entries; i++) {
			int entry = ifd + 2 + i * 12;
			if (entry + 12 > end) {
				return 1;
			}
			int tag = readShort(data, entry, little);
			if (tag == 0x0112) {
				// Tipo SHORT (3), COUNT 1: valor nos primeiros 2 bytes do campo de 4.
				return readShort(data, entry + 8, little);
			}
		}
		return 1;
	}

	private static int readShort(byte[] data, int offset, boolean little) {
		int a = data[offset] & 0xFF;
		int b = data[offset + 1] & 0xFF;
		return little ? (a | (b << 8)) : ((a << 8) | b);
	}

	private static int readIntEndian(byte[] data, int offset, boolean little) {
		int a = data[offset] & 0xFF;
		int b = data[offset + 1] & 0xFF;
		int c = data[offset + 2] & 0xFF;
		int d = data[offset + 3] & 0xFF;
		return little ? (a | (b << 8) | (c << 16) | (d << 24))
			: ((a << 24) | (b << 16) | (c << 8) | d);
	}

	/** Remove EXIF/XMP (APP1), IPTC (APP13), comentários e APP exóticos; mantém JFIF/ICC/Adobe. */
	private static byte[] stripSegments(byte[] data) throws IOException {
		if (data.length < 4 || (data[0] & 0xFF) != 0xFF || (data[1] & 0xFF) != 0xD8) {
			return data;
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
		out.write(data, 0, 2);
		int index = 2;
		while (index + 3 < data.length) {
			if ((data[index] & 0xFF) != 0xFF) {
				break;
			}
			int marker = data[index + 1] & 0xFF;
			int start = index;
			index += 2;
			if (marker == 0xD9) {
				out.write(data, start, 2);
				return out.toByteArray();
			}
			if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
				out.write(data, start, 2);
				continue;
			}
			if (index + 1 >= data.length) {
				break;
			}
			int length = ((data[index] & 0xFF) << 8) | (data[index + 1] & 0xFF);
			if (length < 2 || index + length > data.length) {
				out.write(data, start, data.length - start);
				return out.toByteArray();
			}
			boolean drop = marker == 0xE1 || marker == 0xED || marker == 0xFE
				|| (marker >= 0xE3 && marker <= 0xEC) || marker == 0xEF;
			if (!drop) {
				out.write(data, start, length + 2);
			}
			index += length;
			if (marker == 0xDA) {
				// SOS: o resto é o fluxo comprimido, copiado sem tocar.
				out.write(data, index, data.length - index);
				return out.toByteArray();
			}
		}
		out.write(data, index, data.length - index);
		return out.toByteArray();
	}

	/**
	 * Inserts a minimal big-endian EXIF block holding only the Orientation tag, right after SOI and
	 * the JFIF APP0 segment when there is one.
	 */
	static byte[] withOrientationOnly(byte[] data, int orientation) {
		byte[] exif = { (byte) 0xFF, (byte) 0xE1, 0x00, 0x22, 'E', 'x', 'i', 'f', 0, 0, 'M', 'M', 0, 0x2A, 0, 0, 0,
			0x08, 0, 0x01, 0x01, 0x12, 0, 0x03, 0, 0, 0, 0x01, 0, (byte) orientation, 0, 0, 0, 0, 0, 0 };
		int insertAt = 2;
		if (data.length > 5 && (data[2] & 0xFF) == 0xFF && (data[3] & 0xFF) == 0xE0) {
			int length = ((data[4] & 0xFF) << 8) | (data[5] & 0xFF);
			if (4 + length <= data.length) {
				insertAt = 4 + length;
			}
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream(data.length + exif.length);
		out.write(data, 0, insertAt);
		out.writeBytes(exif);
		out.write(data, insertAt, data.length - insertAt);
		return out.toByteArray();
	}

	private static byte[] rotate(byte[] data, int orientation) throws IOException {
		BufferedImage image = ImageDecoding.read(new ByteArrayInputStream(data));
		if (image == null) {
			return null;
		}
		BufferedImage transformed = transform(image, orientation);
		ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
		ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
		try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
			writer.setOutput(stream);
			ImageWriteParam param = writer.getDefaultWriteParam();
			param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			param.setCompressionQuality(0.92f);
			writer.write(null, new IIOImage(transformed, null, null), param);
		}
		finally {
			writer.dispose();
		}
		return out.toByteArray();
	}

	private static BufferedImage transform(BufferedImage image, int orientation) {
		int width = image.getWidth();
		int height = image.getHeight();
		boolean swap = orientation >= 5 && orientation <= 8;
		BufferedImage out = new BufferedImage(swap ? height : width, swap ? width : height,
			image.getType() == 0 ? BufferedImage.TYPE_INT_RGB : image.getType());
		Graphics2D graphics = out.createGraphics();
		try {
			AffineTransform transform = new AffineTransform();
			switch (orientation) {
				case 2 -> {
					transform.scale(-1, 1);
					transform.translate(-width, 0);
				}
				case 3 -> {
					transform.translate(width, height);
					transform.rotate(Math.PI);
				}
				case 4 -> {
					transform.scale(1, -1);
					transform.translate(0, -height);
				}
				case 5 -> {
					transform.rotate(Math.PI / 2);
					transform.scale(1, -1);
				}
				case 6 -> {
					transform.translate(height, 0);
					transform.rotate(Math.PI / 2);
				}
				case 7 -> {
					transform.scale(-1, 1);
					transform.translate(-height, 0);
					transform.translate(0, width);
					transform.rotate(-Math.PI / 2);
				}
				case 8 -> {
					transform.translate(0, width);
					transform.rotate(-Math.PI / 2);
				}
				default -> {
				}
			}
			graphics.drawImage(image, transform, null);
		}
		finally {
			graphics.dispose();
		}
		return out;
	}
}
