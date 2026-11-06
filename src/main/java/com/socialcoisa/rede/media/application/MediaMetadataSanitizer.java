package com.socialcoisa.rede.media.application;

import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Remove metadados (EXIF/GPS/XMP/IPTC, comentários, info de PDF) de mídia antes de gravar no S3 e,
 * oportunisticamente, ao ler um objeto antigo.
 *
 * <p>Formatos tratados sem reencodificar pixels: JPEG (segmentos APP1/APP13/COM), PNG (chunks de
 * texto/eXIf/tIME), GIF (comentário/XMP), WebP (chunks EXIF/XMP) e PDF (info + XMP). JPEG com
 * orientação EXIF é fisicamente rotacionado antes de remover o bloco — senão a foto de celular
 * apareceria deitada. Vídeo/áudio usam ffmpeg (`-map_metadata -1 -c copy`), sem transcodificar.</p>
 *
 * <p>Falha de parsing não vira erro de upload: devolve o original e registra o motivo. O limite de
 * memória é explícito; arquivos maiores passam direto (o proxy não pode carregar um vídeo inteiro).</p>
 */
@Component
public class MediaMetadataSanitizer {

	private static final Logger LOG = LoggerFactory.getLogger(MediaMetadataSanitizer.class);

	/** Teto para sanitizar em memória; acima disso o objeto é transmitido como veio. */
	public static final int MAX_IN_MEMORY = 40 * 1024 * 1024;

	private static final Set<String> PNG_DROP = Set.of("tEXt", "zTXt", "iTXt", "eXIf", "tIME");

	private final String ffmpegPath;
	private final MediaTemporaryFiles temporaryFiles;

	public MediaMetadataSanitizer(@Value("${rede.media.ffmpeg-path:ffmpeg}") String ffmpegPath,
			MediaTemporaryFiles temporaryFiles) {
		this.ffmpegPath = ffmpegPath;
		this.temporaryFiles = temporaryFiles;
	}

	/** Imagem/PDF: dá para limpar em memória sem perder animação/streaming. */
	public boolean supportsInMemory(String contentType) {
		String type = normalize(contentType);
		return type.startsWith("image/") || "application/pdf".equals(type);
	}

	public boolean isMedia(String contentType) {
		String type = normalize(contentType);
		return type.startsWith("image/") || type.startsWith("video/") || type.startsWith("audio/")
			|| "application/pdf".equals(type);
	}

	/**
	 * Para arquivos maiores que {@link #MAX_IN_MEMORY}: grava em arquivo temporário, roda o ffmpeg
	 * (vídeo/áudio) e devolve o caminho do conteúdo limpo. O chamador é dono do arquivo.
	 */
	public Path sanitizeToFile(java.io.InputStream input, String contentType, String fileName)
			throws IOException {
		Path original = temporaryFiles.create("media-raw-", suffix(fileName));
		try {
			Files.copy(input, original, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			String type = normalize(contentType);
			if (!type.startsWith("video/") && !type.startsWith("audio/")) {
				return original;
			}
			return stripWithFfmpeg(original, fileName);
		}
		catch (IOException failure) {
			// Cliente caiu no meio do envio (ou disco cheio): o temporario nao fica para tras.
			Files.deleteIfExists(original);
			throw failure;
		}
		catch (RuntimeException failure) {
			return original;
		}
	}

	/**
	 * Limpa o vídeo/áudio já gravado em disco. Limpeza é melhor-esforço: qualquer falha do ffmpeg
	 * (ausente, travado, arquivo que ele não lê) devolve o original e fica no log — nunca vira erro
	 * de upload. A saída do processo é descartada: ninguém a lê, e um ffmpeg verboso encheria o pipe
	 * e travaria até o timeout.
	 */
	private Path stripWithFfmpeg(Path original, String fileName) throws IOException {
		Path output = temporaryFiles.create("media-clean-", suffix(fileName));
		long startedAt = System.nanoTime();
		try {
			Process process = new ProcessBuilder(ffmpegPath, "-hide_banner", "-loglevel", "error", "-y",
				"-i", original.toString(), "-map_metadata", "-1", "-map_chapters", "-1", "-c", "copy",
				output.toString()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
			boolean finished = process.waitFor(120, TimeUnit.SECONDS);
			if (!finished || process.exitValue() != 0 || Files.size(output) == 0) {
				process.destroyForcibly();
				LOG.warn("media_ffmpeg_skipped finished={} exit={} elapsedMs={} originalBytes={}", finished,
					finished ? process.exitValue() : -1, (System.nanoTime() - startedAt) / 1_000_000L,
					Files.size(original));
				Files.deleteIfExists(output);
				return original;
			}
			Files.deleteIfExists(original);
			return output;
		}
		catch (IOException failure) {
			LOG.warn("media_ffmpeg_unavailable error={}", failure.getClass().getSimpleName());
			Files.deleteIfExists(output);
			return original;
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			Files.deleteIfExists(output);
			return original;
		}
	}

	/** Devolve o conteúdo limpo; em erro/desconhecido, devolve o original. */
	public byte[] sanitize(byte[] data, String contentType, String fileName) {
		if (data == null || data.length == 0 || data.length > MAX_IN_MEMORY) {
			return data;
		}
		String type = normalize(contentType);
		try {
			if (type.startsWith("image/jpeg") || type.startsWith("image/jpg")) {
				return jpeg(data);
			}
			if (type.startsWith("image/png")) {
				return png(data);
			}
			if (type.startsWith("image/gif")) {
				return gif(data);
			}
			if (type.startsWith("image/webp")) {
				return webp(data);
			}
			if ("application/pdf".equals(type)) {
				return pdf(data);
			}
			if (type.startsWith("video/") || type.startsWith("audio/")) {
				return ffmpeg(data, fileName);
			}
		}
		catch (Exception failure) {
			LOG.warn("media_metadata_sanitize_failed type={} error={}", type,
				failure.getClass().getSimpleName());
		}
		return data;
	}

	// ------------------------------------------------------------------ JPEG

	private byte[] jpeg(byte[] data) throws IOException {
		int orientation = jpegOrientation(data);
		if (orientation > 1) {
			byte[] rotated = rotateJpeg(data, orientation);
			if (rotated != null) {
				return rotated;
			}
		}
		return stripJpegSegments(data);
	}

	/**
	 * Lê a tag Orientation (0x0112) do bloco APP1/EXIF sem biblioteca externa. Se o JPEG não tiver
	 * EXIF válido, devolve 1 (sem rotação).
	 */
	static int jpegOrientation(byte[] data) {
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
	private byte[] stripJpegSegments(byte[] data) throws IOException {
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

	private byte[] rotateJpeg(byte[] data, int orientation) throws IOException {
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

	private BufferedImage transform(BufferedImage image, int orientation) {
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

	// ------------------------------------------------------------------ PNG

	private byte[] png(byte[] data) throws IOException {
		byte[] signature = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' };
		if (data.length < 8 || !Arrays.equals(Arrays.copyOf(data, 8), signature)) {
			return data;
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
		out.write(data, 0, 8);
		int index = 8;
		while (index + 12 <= data.length) {
			int length = readInt(data, index);
			if (length < 0 || index + 12 + length > data.length) {
				out.write(data, index, data.length - index);
				break;
			}
			String type = new String(data, index + 4, 4, StandardCharsets.US_ASCII);
			int total = 12 + length;
			if (!PNG_DROP.contains(type)) {
				out.write(data, index, total);
			}
			index += total;
			if ("IEND".equals(type)) {
				break;
			}
		}
		if (index < data.length) {
			out.write(data, index, data.length - index);
		}
		return out.toByteArray();
	}

	// ------------------------------------------------------------------ GIF

	private byte[] gif(byte[] data) throws IOException {
		if (data.length < 13 || !new String(data, 0, 3, StandardCharsets.US_ASCII).equals("GIF")) {
			return data;
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
		int packed = data[10] & 0xFF;
		int headerEnd = 13;
		if ((packed & 0x80) != 0) {
			headerEnd += 3 * (1 << ((packed & 0x07) + 1));
		}
		if (headerEnd > data.length) {
			return data;
		}
		out.write(data, 0, headerEnd);
		int index = headerEnd;
		while (index < data.length) {
			int block = data[index] & 0xFF;
			if (block == 0x3B) {
				out.write(data, index, data.length - index);
				break;
			}
			if (block == 0x21 && index + 2 < data.length) {
				int label = data[index + 1] & 0xFF;
				int start = index;
				index += 2;
				if (label == 0xFF && index < data.length) {
					int size = data[index] & 0xFF;
					String id = index + 1 + size <= data.length
						? new String(data, index + 1, Math.min(size, 11), StandardCharsets.US_ASCII) : "";
					index = skipSubBlocks(data, index);
					if (!id.startsWith("XMP")) {
						out.write(data, start, index - start);
					}
					continue;
				}
				index = skipSubBlocks(data, index);
				if (label != 0xFE) {
					out.write(data, start, index - start);
				}
				continue;
			}
			if (block == 0x2C && index + 10 <= data.length) {
				int start = index;
				int localPacked = data[index + 9] & 0xFF;
				index += 10;
				if ((localPacked & 0x80) != 0) {
					index += 3 * (1 << ((localPacked & 0x07) + 1));
				}
				// Um byte de "LZW minimum code size" antes dos sub-blocos da imagem.
				index += 1;
				index = skipSubBlocks(data, index);
				if (index > data.length) {
					return data;
				}
				out.write(data, start, index - start);
				continue;
			}
			return data;
		}
		return out.toByteArray();
	}

	private int skipSubBlocks(byte[] data, int index) {
		while (index < data.length) {
			int size = data[index] & 0xFF;
			index++;
			if (size == 0) {
				return index;
			}
			index += size;
		}
		return data.length;
	}

	// ------------------------------------------------------------------ WebP

	private byte[] webp(byte[] data) throws IOException {
		if (data.length < 12 || !new String(data, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
			|| !new String(data, 8, 4, StandardCharsets.US_ASCII).equals("WEBP")) {
			return data;
		}
		ByteArrayOutputStream payload = new ByteArrayOutputStream(data.length);
		int index = 12;
		while (index + 8 <= data.length) {
			String type = new String(data, index, 4, StandardCharsets.US_ASCII);
			int size = readLittleInt(data, index + 4);
			int total = 8 + size + (size & 1);
			if (index + total > data.length) {
				break;
			}
			if (!"EXIF".equals(type) && !"XMP ".equals(type)) {
				payload.write(data, index, total);
			}
			index += total;
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
		out.write("RIFF".getBytes(StandardCharsets.US_ASCII));
		writeLittleInt(out, payload.size() + 4);
		out.write("WEBP".getBytes(StandardCharsets.US_ASCII));
		payload.writeTo(out);
		return out.toByteArray();
	}

	// ------------------------------------------------------------------ PDF

	private byte[] pdf(byte[] data) throws IOException {
		try (PDDocument document = Loader.loadPDF(data)) {
			document.setDocumentInformation(new PDDocumentInformation());
			document.getDocumentCatalog().setMetadata(null);
			ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
			document.save(out);
			return out.toByteArray();
		}
	}

	// ------------------------------------------------------------------ vídeo/áudio

	private byte[] ffmpeg(byte[] data, String fileName) throws IOException, InterruptedException {
		Path input = temporaryFiles.create("media-in-", suffix(fileName));
		Path output = temporaryFiles.create("media-out-", suffix(fileName));
		try {
			Files.write(input, data);
			Process process = new ProcessBuilder(ffmpegPath, "-hide_banner", "-loglevel", "error", "-y",
				"-i", input.toString(), "-map_metadata", "-1", "-map_chapters", "-1", "-c", "copy",
				output.toString()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
			if (!process.waitFor(30, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				LOG.warn("media_ffmpeg_skipped finished=false elapsedMs=30000 originalBytes={}", data.length);
				return data;
			}
			if (process.exitValue() != 0 || Files.size(output) == 0) {
				LOG.warn("media_ffmpeg_skipped finished=true exit={} originalBytes={}", process.exitValue(),
					data.length);
				return data;
			}
			return Files.readAllBytes(output);
		}
		finally {
			Files.deleteIfExists(input);
			Files.deleteIfExists(output);
		}
	}

	// ------------------------------------------------------------------ util

	private static String normalize(String contentType) {
		if (contentType == null) {
			return "";
		}
		String type = contentType.trim().toLowerCase(Locale.ROOT);
		int semicolon = type.indexOf(';');
		return semicolon >= 0 ? type.substring(0, semicolon).trim() : type;
	}

	private static String suffix(String fileName) {
		if (fileName == null) {
			return ".bin";
		}
		int dot = fileName.lastIndexOf('.');
		return dot >= 0 && fileName.length() - dot <= 8 ? fileName.substring(dot) : ".bin";
	}

	private static int readInt(byte[] data, int offset) {
		return ((data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16)
			| ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
	}

	private static int readLittleInt(byte[] data, int offset) {
		return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8)
			| ((data[offset + 2] & 0xFF) << 16) | ((data[offset + 3] & 0xFF) << 24);
	}

	private static void writeLittleInt(ByteArrayOutputStream out, int value) {
		out.write(value & 0xFF);
		out.write((value >> 8) & 0xFF);
		out.write((value >> 16) & 0xFF);
		out.write((value >> 24) & 0xFF);
	}
}
