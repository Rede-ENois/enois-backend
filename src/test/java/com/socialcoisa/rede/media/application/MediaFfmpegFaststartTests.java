package com.socialcoisa.rede.media.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * O que a issue #537 tranca: o vídeo que sobe com o {@code moov} no fim não toca no card do feed,
 * porque o player precisa baixar o arquivo inteiro até achar a duração. Depois desta etapa o índice
 * tem de estar no começo.
 *
 * <p>Roda com o ffmpeg de verdade quando ele está no PATH; sem ele o teste é pulado com a razão
 * explícita, porque a mesma classe não pode falhar numa máquina de desenvolvimento que não tem o
 * binário. O CI não instala ffmpeg hoje — por isso a prova de que a flag chega ao comando fica em
 * {@code MediaFfmpegRunnerTests}, que usa um script no lugar do binário e roda em todo ambiente;
 * aqui fica a prova do efeito, para quem tem o ffmpeg.</p>
 */
class MediaFfmpegFaststartTests {

	@TempDir
	Path dir;

	@Test
	void theUploadedVideoComesBackWithTheIndexAtTheFront() throws Exception {
		org.junit.jupiter.api.Assumptions.assumeTrue(ffmpegAvailable(),
			"ffmpeg não está no PATH desta máquina; a prova do faststart exige o binário real");

		Path raw = mp4WithoutFaststart();
		assertThat(boxOrder(raw)).as("o arquivo de entrada é o cru do celular: moov no fim")
			.containsSubsequence("ftyp", "mdat", "moov");

		Path output = Files.createFile(dir.resolve("clean.mp4"));
		new MediaFfmpegRunner("ffmpeg", 1).strip(raw, output, "video/mp4", Duration.ofSeconds(60));

		assertThat(boxOrder(output)).as("o moov tem de vir antes do mdat")
			.containsSubsequence("ftyp", "moov")
			.doesNotContainSubsequence("mdat", "moov");
	}

	private Path mp4WithoutFaststart() throws Exception {
		Path raw = dir.resolve("raw.mp4");
		Process process = new ProcessBuilder("ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
			"-f", "lavfi", "-i", "testsrc=duration=2:size=160x120:rate=10",
			"-c:v", "libx264", "-pix_fmt", "yuv420p", raw.toString())
			.redirectErrorStream(true)
			.start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertThat(process.waitFor()).as("geração do arquivo de entrada: " + output).isZero();
		return raw;
	}

	/** A ordem dos boxes de topo do MP4 — é o que o player lê antes de decodificar. */
	private static java.util.List<String> boxOrder(Path file) throws IOException {
		java.util.List<String> boxes = new java.util.ArrayList<>();
		try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
			long position = 0;
			long length = raf.length();
			while (position + 8 <= length) {
				raf.seek(position);
				long size = raf.readInt() & 0xFFFFFFFFL;
				byte[] type = new byte[4];
				raf.readFully(type);
				boxes.add(new String(type, StandardCharsets.US_ASCII));
				if (size < 8) {
					break;
				}
				position += size;
			}
		}
		return boxes;
	}

	private static boolean ffmpegAvailable() {
		try {
			Process process = new ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start();
			process.getInputStream().readAllBytes();
			return process.waitFor() == 0;
		}
		catch (IOException | InterruptedException absent) {
			return false;
		}
	}

}
