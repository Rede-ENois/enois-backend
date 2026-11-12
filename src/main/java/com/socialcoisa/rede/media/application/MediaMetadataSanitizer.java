package com.socialcoisa.rede.media.application;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Remove metadados (EXIF/GPS/XMP/IPTC, comentários, info de PDF) de mídia antes de gravar no S3.
 *
 * <p>Formatos tratados sem reencodificar pixels: JPEG ({@link JpegMetadata}), PNG (chunks de
 * texto/eXIf/tIME), GIF (comentário/XMP), WebP (chunks EXIF/XMP) e PDF (info + XMP). Vídeo/áudio
 * usam ffmpeg ({@link MediaFfmpegRunner}), sem transcodificar.</p>
 *
 * <p>Falha na limpeza recusa o upload com erro nomeado e fica no log: gravar o original publicaria
 * os metadados que esta etapa existe para tirar.</p>
 */
@Component
public class MediaMetadataSanitizer {

	private static final Logger LOG = LoggerFactory.getLogger(MediaMetadataSanitizer.class);

	/** Teto para sanitizar em memória; acima disso só vídeo/áudio, via arquivo temporário. */
	public static final int MAX_IN_MEMORY = 40 * 1024 * 1024;

	static final Duration IN_MEMORY_FFMPEG_TIMEOUT = Duration.ofSeconds(30);
	static final Duration FILE_FFMPEG_TIMEOUT = Duration.ofSeconds(120);

	private static final Set<String> PNG_DROP = Set.of("tEXt", "zTXt", "iTXt", "eXIf", "tIME");

	private final MediaFfmpegRunner ffmpeg;
	private final MediaTemporaryFiles temporaryFiles;

	public MediaMetadataSanitizer(MediaFfmpegRunner ffmpeg, MediaTemporaryFiles temporaryFiles) {
		this.ffmpeg = ffmpeg;
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
	 * (vídeo/áudio) e devolve o caminho do conteúdo limpo. O chamador é dono do arquivo. Qualquer
	 * falha apaga os temporários e propaga.
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
			Path output = temporaryFiles.create("media-clean-", suffix(fileName));
			try {
				// Limpa e, quando vale a pena, comprime: o vídeo do feed não precisa subir em 1080p.
				ffmpeg.clean(original, output, type, FILE_FFMPEG_TIMEOUT);
			}
			catch (RuntimeException failure) {
				Files.deleteIfExists(output);
				throw failure;
			}
			Files.deleteIfExists(original);
			return output;
		}
		catch (IOException | RuntimeException failure) {
			// Cliente caiu no meio do envio, disco cheio ou ffmpeg recusou: o temporario nao fica para tras.
			Files.deleteIfExists(original);
			throw failure;
		}
	}

	/**
	 * Devolve o conteúdo limpo. Tipo sem limpeza definida volta como veio; falha na limpeza recusa o
	 * upload ({@code MEDIA_PROCESSING_*}). Sem pasta temporária para o vídeo/áudio, propaga
	 * {@link MediaTemporaryFiles.UnavailableException}.
	 */
	public byte[] sanitize(byte[] data, String contentType, String fileName)
			throws MediaTemporaryFiles.UnavailableException {
		if (data == null || data.length == 0 || data.length > MAX_IN_MEMORY) {
			return data;
		}
		String type = normalize(contentType);
		try {
			if (type.startsWith("image/jpeg") || type.startsWith("image/jpg")) {
				return JpegMetadata.sanitize(data);
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
				return withFfmpeg(data, type, fileName);
			}
		}
		catch (MediaTemporaryFiles.UnavailableException | ApiException failure) {
			throw failure;
		}
		catch (IOException | RuntimeException failure) {
			LOG.warn("media_metadata_sanitize_failed type={} error={}", type, failure.getClass().getSimpleName());
			throw MediaFfmpegRunner.rejected(failure);
		}
		return data;
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

	private byte[] withFfmpeg(byte[] data, String type, String fileName) throws IOException {
		Path input = temporaryFiles.create("media-in-", suffix(fileName));
		Path output = temporaryFiles.create("media-out-", suffix(fileName));
		try {
			Files.write(input, data);
			ffmpeg.clean(input, output, type, IN_MEMORY_FFMPEG_TIMEOUT);
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
