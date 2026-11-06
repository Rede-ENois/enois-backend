package com.socialcoisa.rede.media.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

import com.socialcoisa.rede.shared.web.ApiException;

/** A limpeza de metadados na escrita/leitura não pode deixar EXIF/texto/autor no arquivo. */
class MediaMetadataSanitizerTests {

	private final MediaMetadataSanitizer sanitizer = sanitizer("ffmpeg", temporarios());

	@Test
	void largeVideoIsRejectedAndNotKeptWhenFfmpegCannotRun() {
		// Uploading the original would publish the metadata this step exists to remove.
		var semFfmpeg = sanitizer("ffmpeg-que-nao-existe-neste-host", temporarios());
		byte[] video = "ftypisom-corpo-de-video".getBytes(StandardCharsets.UTF_8);

		ApiException failure = assertThrows(ApiException.class, () -> semFfmpeg.sanitizeToFile(
			new java.io.ByteArrayInputStream(video), "video/mp4", "uploads/media_x/file.mp4"));
		assertEquals("MEDIA_PROCESSING_UNAVAILABLE", failure.code());
	}

	@Test
	void smallVideoIsRejectedWhenFfmpegFails() {
		var semFfmpeg = sanitizer("ffmpeg-que-nao-existe-neste-host", temporarios());
		byte[] video = "ftypisom-corpo-de-video".getBytes(StandardCharsets.UTF_8);

		ApiException failure = assertThrows(ApiException.class,
			() -> semFfmpeg.sanitize(video, "video/mp4", "uploads/media_x/file.mp4"));
		assertEquals("MEDIA_PROCESSING_UNAVAILABLE", failure.code());
	}

	@Test
	void unreadablePdfIsRejectedInsteadOfStoredAsIs() {
		byte[] notPdf = "%PDF-1.7 Author (segredo)".getBytes(StandardCharsets.ISO_8859_1);

		ApiException failure = assertThrows(ApiException.class,
			() -> sanitizer.sanitize(notPdf, "application/pdf", "x.pdf"));
		assertEquals("MEDIA_PROCESSING_FAILED", failure.code());
	}

	@Test
	void jpegTooLargeToRotateLosesItsMetadataButKeepsTheOrientation() throws Exception {
		BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
		ByteArrayOutputStream base = new ByteArrayOutputStream();
		ImageIO.write(image, "jpg", base);
		byte[] jpeg = withExif(declareSize(base.toByteArray(), 5000, 5000), 6, "segredo-gps");

		byte[] clean = sanitizer.sanitize(jpeg, "image/jpeg", "x.jpg");

		assertFalse(new String(clean, StandardCharsets.ISO_8859_1).contains("segredo-gps"));
		assertEquals(6, JpegMetadata.orientation(clean));
		assertEquals(new ImageDecoding.Dimensions(5000, 5000), ImageDecoding.displayDimensions(clean));
	}

	@Test
	void videoSemPastaTemporariaNaoSobeComOsMetadados() {
		// Sem onde gravar o temporario, o video pequeno nao pode voltar "como veio": isso publicaria
		// os metadados que a limpeza existe para tirar.
		var semPasta = sanitizer("ffmpeg", new MediaTemporaryFiles("/dev/null/nao-e-pasta", "/dev/null/nao-e-dados",
			"/dev/null/nao-e-tmp", null));
		byte[] video = "ftypisom-corpo-de-video".getBytes(StandardCharsets.UTF_8);

		MediaTemporaryFiles.UnavailableException failure = assertThrows(
			MediaTemporaryFiles.UnavailableException.class,
			() -> semPasta.sanitize(video, "video/mp4", "uploads/media_x/file.mp4"));
		assertSame(MediaTemporaryFiles.UnavailableException.class, failure.getClass());
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

	private static MediaTemporaryFiles temporarios() {
		return new MediaTemporaryFiles("", MediaTemporaryFiles.DEFAULT_DATA_DIR, System.getProperty("java.io.tmpdir"),
			System.getProperty("user.dir"));
	}

	private static MediaMetadataSanitizer sanitizer(String ffmpegPath, MediaTemporaryFiles temporarios) {
		return new MediaMetadataSanitizer(new MediaFfmpegRunner(ffmpegPath, 2), temporarios);
	}

	/** Rewrites the SOF0 height/width: the header claims a size the pixel data does not have. */
	private static byte[] declareSize(byte[] jpeg, int width, int height) {
		byte[] copy = jpeg.clone();
		for (int i = 2; i + 8 < copy.length; i++) {
			if ((copy[i] & 0xFF) == 0xFF && (copy[i + 1] & 0xFF) == 0xC0) {
				copy[i + 5] = (byte) (height >>> 8);
				copy[i + 6] = (byte) height;
				copy[i + 7] = (byte) (width >>> 8);
				copy[i + 8] = (byte) width;
				return copy;
			}
		}
		throw new IllegalStateException("SOF0 not found");
	}

	/** Adds an APP1/EXIF block with the orientation and an extra field right after SOI. */
	private static byte[] withExif(byte[] jpeg, int orientation, String extra) {
		byte[] text = extra.getBytes(StandardCharsets.US_ASCII);
		ByteArrayOutputStream tiff = new ByteArrayOutputStream();
		tiff.writeBytes(new byte[] { 'M', 'M', 0, 0x2A, 0, 0, 0, 8, 0, 1, 0x01, 0x12, 0, 3, 0, 0, 0, 1, 0,
			(byte) orientation, 0, 0, 0, 0, 0, 0 });
		tiff.writeBytes(text);
		int length = 2 + 6 + tiff.size();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(jpeg, 0, 2);
		out.writeBytes(new byte[] { (byte) 0xFF, (byte) 0xE1, (byte) (length >>> 8), (byte) length, 'E', 'x', 'i', 'f',
			0, 0 });
		out.writeBytes(tiff.toByteArray());
		out.write(jpeg, 2, jpeg.length - 2);
		return out.toByteArray();
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
