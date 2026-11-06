package com.socialcoisa.rede.media.application;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;

/** A limpeza de metadados na escrita/leitura não pode deixar EXIF/texto/autor no arquivo. */
class MediaMetadataSanitizerTests {

	private final MediaMetadataSanitizer sanitizer = new MediaMetadataSanitizer("ffmpeg",
		new MediaTemporaryFiles("", MediaTemporaryFiles.DEFAULT_DATA_DIR));

	@Test
	void largeVideoKeepsTheOriginalWhenFfmpegCannotRun() throws Exception {
		// ffmpeg ausente/nao executavel: a limpeza e melhor-esforco, o upload segue com o original.
		var semFfmpeg = new MediaMetadataSanitizer("ffmpeg-que-nao-existe-neste-host",
			new MediaTemporaryFiles("", MediaTemporaryFiles.DEFAULT_DATA_DIR));
		byte[] video = "ftypisom-corpo-de-video".getBytes(StandardCharsets.UTF_8);

		java.nio.file.Path resultado = semFfmpeg.sanitizeToFile(new java.io.ByteArrayInputStream(video),
			"video/mp4", "uploads/media_x/file.mp4");
		try {
			assertTrue(java.util.Arrays.equals(video, java.nio.file.Files.readAllBytes(resultado)));
		}
		finally {
			java.nio.file.Files.deleteIfExists(resultado);
		}
	}

	@Test
	void pngTextChunkIsRemoved() throws Exception {
		BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
		ByteArrayOutputStream base = new ByteArrayOutputStream();
		ImageIO.write(image, "png", base);
		byte[] png = withTextChunk(base.toByteArray());

		byte[] clean = sanitizer.sanitize(png, "image/png", "x.png");

		assertTrue(new String(clean, StandardCharsets.ISO_8859_1).contains("IEND"));
		assertFalse(new String(clean, StandardCharsets.ISO_8859_1).contains("tEXt"));
		assertFalse(new String(clean, StandardCharsets.ISO_8859_1).contains("segredo"));
	}

	@Test
	void pdfDocumentInformationIsCleared() throws Exception {
		byte[] pdf;
		try (PDDocument document = new PDDocument()) {
			document.addPage(new PDPage());
			document.getDocumentInformation().setAuthor("segredo");
			document.getDocumentInformation().setTitle("segredo");
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			document.save(out);
			pdf = out.toByteArray();
		}

		byte[] clean = sanitizer.sanitize(pdf, "application/pdf", "x.pdf");

		try (PDDocument document = Loader.loadPDF(clean)) {
			String author = document.getDocumentInformation().getAuthor();
			assertTrue(author == null || author.isBlank());
		}
	}

	/** Insere um chunk tEXt válido antes do IEND (mesmo CRC) para provar que ele some. */
	private static byte[] withTextChunk(byte[] png) throws java.io.IOException {
		int iend = indexOf(png, "IEND".getBytes(StandardCharsets.US_ASCII)) - 4;
		byte[] type = "tEXt".getBytes(StandardCharsets.US_ASCII);
		byte[] data = "Comment\u0000segredo".getBytes(StandardCharsets.ISO_8859_1);
		ByteArrayOutputStream chunk = new ByteArrayOutputStream();
		chunk.write((data.length >>> 24) & 0xFF);
		chunk.write((data.length >>> 16) & 0xFF);
		chunk.write((data.length >>> 8) & 0xFF);
		chunk.write(data.length & 0xFF);
		chunk.writeBytes(type);
		chunk.writeBytes(data);
		CRC32 crc = new CRC32();
		crc.update(type);
		crc.update(data);
		long value = crc.getValue();
		chunk.write((int) (value >>> 24) & 0xFF);
		chunk.write((int) (value >>> 16) & 0xFF);
		chunk.write((int) (value >>> 8) & 0xFF);
		chunk.write((int) value & 0xFF);

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(png, 0, iend);
		chunk.writeTo(out);
		out.write(png, iend, png.length - iend);
		return out.toByteArray();
	}

	private static int indexOf(byte[] data, byte[] needle) {
		outer:
		for (int i = 0; i <= data.length - needle.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (data[i + j] != needle[j]) {
					continue outer;
				}
			}
			return i;
		}
		return data.length;
	}
}
