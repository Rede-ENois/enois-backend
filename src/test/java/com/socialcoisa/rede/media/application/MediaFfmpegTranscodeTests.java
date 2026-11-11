package com.socialcoisa.rede.media.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * O que a issue #652 tranca: o vídeo subia no tamanho original porque o ffmpeg só remuxava
 * ({@code -c copy}). Depois desta etapa ele é reencodado no tamanho que o feed mostra, e o arquivo
 * menor é o que vai para o bucket.
 *
 * <p>A prova usa um script no lugar do binário, para rodar em todo ambiente (o CI não instala
 * ffmpeg): o script registra a invocação e grava um arquivo do tamanho que o teste manda. Assim dá
 * para afirmar as três coisas que são o contrato desta etapa — quais argumentos chegam, qual saída
 * fica em disco e o que acontece quando a compressão não vale a pena.</p>
 */
class MediaFfmpegTranscodeTests {

	/** 8 MB de entrada, acima do mínimo de 4 MB: o corte de 10% fica em 7,2 MB, então 7 MB salva e 7,9 MB não. */
	private static final int INPUT_BYTES = 8_000_000;

	@TempDir
	Path dir;

	/**
	 * Antes: o único comando era o remux com {@code -c copy} e o arquivo gravado tinha exatamente o
	 * tamanho do enviado. Agora: o transcode roda primeiro e a saída menor é a que fica.
	 */
	@Test
	void compressesTheVideoAndKeepsTheSmallerFile() throws Exception {
		Path args = dir.resolve("args.txt");
		Path ffmpeg = script("""
			printf '%%s\\n' "$@" > '%s'
			for last; do :; done
			head -c 1000000 /dev/zero > "${last#file:}"
			""".formatted(args));
		Path input = Files.write(dir.resolve("in.bin"), new byte[INPUT_BYTES]);
		Path output = Files.createFile(dir.resolve("out.bin"));

		runner(ffmpeg, MediaFfmpegRunner.DEFAULT_TRANSCODE_MAX_BYTES)
			.clean(input, output, "video/mp4", Duration.ofSeconds(120));

		String invocation = String.join(" ", Files.readAllLines(args));
		assertThat(invocation).as("o transcode reencoda em vez de copiar o bitstream")
			.contains("-vf " + MediaFfmpegRunner.SCALE_FILTER)
			.doesNotContain("-c copy")
			.contains("-c:v libx264")
			.contains("-c:a aac")
			.contains("-movflags +faststart")
			.contains("-map_metadata -1")
			.contains("-map 0:v:0 -map 0:a?");
		assertThat(Files.size(output)).as("o arquivo gravado é o comprimido, não o original").isEqualTo(1_000_000);
	}

	/**
	 * O remux continua sendo o caminho quando a compressão não rende: um vídeo já pequeno ou já
	 * comprimido não pode terminar MAIOR por causa desta etapa.
	 */
	@Test
	void keepsTheRemuxWhenTheTranscodeDoesNotShrinkEnough() throws Exception {
		Path args = dir.resolve("args.txt");
		Path ffmpeg = script("""
			printf '%%s\\n' "$@" >> '%s'
			for last; do :; done
			out="${last#file:}"
			case "$*" in
				*scale=*) head -c 7900000 /dev/zero > "$out" ;;
				*) head -c 7000000 /dev/zero > "$out" ;;
			esac
			""".formatted(args));
		Path input = Files.write(dir.resolve("in.bin"), new byte[INPUT_BYTES]);
		Path output = Files.createFile(dir.resolve("out.bin"));

		runner(ffmpeg, MediaFfmpegRunner.DEFAULT_TRANSCODE_MAX_BYTES)
			.clean(input, output, "video/mp4", Duration.ofSeconds(120));

		assertThat(String.join(" ", Files.readAllLines(args))).as("os dois passes rodaram")
			.contains(MediaFfmpegRunner.SCALE_FILTER).contains("-c copy");
		assertThat(Files.size(output)).as("ficou o remux: 7,9 MB é só 1,25% menor, abaixo do corte de 10%")
			.isEqualTo(7_000_000);
	}

	/** Transcode que falha não pode derrubar o upload: o remux roda depois e decide o resultado. */
	@Test
	void fallsBackToTheRemuxWhenTheTranscodeFails() throws Exception {
		Path args = dir.resolve("args.txt");
		Path ffmpeg = script("""
			printf '%%s\\n' "$@" >> '%s'
			for last; do :; done
			out="${last#file:}"
			case "$*" in
				*scale=*) exit 1 ;;
				*) head -c 7000000 /dev/zero > "$out" ;;
			esac
			""".formatted(args));
		Path input = Files.write(dir.resolve("in.bin"), new byte[INPUT_BYTES]);
		Path output = Files.createFile(dir.resolve("out.bin"));

		runner(ffmpeg, MediaFfmpegRunner.DEFAULT_TRANSCODE_MAX_BYTES)
			.clean(input, output, "video/mp4", Duration.ofSeconds(120));

		assertThat(String.join(" ", Files.readAllLines(args))).contains("-c copy");
		assertThat(Files.size(output)).isEqualTo(7_000_000);
	}

	/** Áudio não é vídeo: não há escala a aplicar e o remux é o caminho, como antes. */
	@Test
	void doesNotTryToScaleAudio() throws Exception {
		Path args = dir.resolve("args.txt");
		Path ffmpeg = script("""
			printf '%%s\\n' "$@" > '%s'
			for last; do :; done
			head -c 7000000 /dev/zero > "${last#file:}"
			""".formatted(args));
		Path input = Files.write(dir.resolve("in.bin"), new byte[INPUT_BYTES]);
		Path output = Files.createFile(dir.resolve("out.bin"));

		runner(ffmpeg, MediaFfmpegRunner.DEFAULT_TRANSCODE_MAX_BYTES)
			.clean(input, output, "audio/mpeg", Duration.ofSeconds(120));

		assertThat(String.join(" ", Files.readAllLines(args))).contains("-c copy")
			.doesNotContain(MediaFfmpegRunner.SCALE_FILTER);
	}

	/** Acima do teto configurado o transcode é pulado: o upload não espera minutos por um arquivo enorme. */
	@Test
	void skipsTheTranscodeAboveTheConfiguredLimit() throws Exception {
		Path args = dir.resolve("args.txt");
		Path ffmpeg = script("""
			printf '%%s\\n' "$@" > '%s'
			for last; do :; done
			head -c 7000000 /dev/zero > "${last#file:}"
			""".formatted(args));
		Path input = Files.write(dir.resolve("in.bin"), new byte[INPUT_BYTES]);
		Path output = Files.createFile(dir.resolve("out.bin"));

		runner(ffmpeg, 4096).clean(input, output, "video/mp4", Duration.ofSeconds(120));

		assertThat(String.join(" ", Files.readAllLines(args))).contains("-c copy")
			.doesNotContain(MediaFfmpegRunner.SCALE_FILTER);
	}

	/** Teto zero desliga a compressão sem mudar o código de chamada. */
	@Test
	void aZeroLimitDisablesCompression() throws Exception {
		Path args = dir.resolve("args.txt");
		Path ffmpeg = script("""
			printf '%%s\\n' "$@" > '%s'
			for last; do :; done
			head -c 7000000 /dev/zero > "${last#file:}"
			""".formatted(args));
		Path input = Files.write(dir.resolve("in.bin"), new byte[INPUT_BYTES]);
		Path output = Files.createFile(dir.resolve("out.bin"));

		runner(ffmpeg, 0).clean(input, output, "video/mp4", Duration.ofSeconds(120));

		assertThat(String.join(" ", Files.readAllLines(args))).contains("-c copy")
			.doesNotContain(MediaFfmpegRunner.SCALE_FILTER);
	}

	/** Arquivo abaixo do mínimo de compressão não paga o custo de reencodar. */
	@Test
	void skipsFilesAlreadySmallEnough() throws Exception {
		Path args = dir.resolve("args.txt");
		Path ffmpeg = script("""
			printf '%%s\\n' "$@" > '%s'
			for last; do :; done
			head -c 1000000 /dev/zero > "${last#file:}"
			""".formatted(args));
		Path input = Files.write(dir.resolve("in.bin"), new byte[1024]);
		Path output = Files.createFile(dir.resolve("out.bin"));

		runner(ffmpeg, MediaFfmpegRunner.DEFAULT_TRANSCODE_MAX_BYTES)
			.clean(input, output, "video/mp4", Duration.ofSeconds(120));

		assertThat(String.join(" ", Files.readAllLines(args))).contains("-c copy")
			.doesNotContain(MediaFfmpegRunner.SCALE_FILTER);
	}

	/**
	 * O filtro carrega aspas porque as vírgulas de {@code if(...)} separam opções do filtro. As aspas
	 * são do argumento entregue ao ffmpeg — {@link ProcessBuilder} não interpreta string, então quem
	 * remove é o próprio ffmpeg.
	 */
	@Test
	void theScaleFilterKeepsTheAspectRatioAndNeverUpscales() {
		assertThat(MediaFfmpegRunner.SCALE_FILTER)
			.contains("min(1280,iw)")
			.contains("min(1280,ih)")
			.isEqualTo("scale=w='if(gt(iw,ih),min(1280,iw),-2)':h='if(gt(iw,ih),-2,min(1280,ih))'");
	}

	/** O teto de tempo reserva o remux: um transcode que estoura o orçamento não pode zerar o fallback. */
	@Test
	void theTranscodeBudgetLeavesRoomForTheRemux() throws Exception {
		Path args = dir.resolve("args.txt");
		Path ffmpeg = script("""
			printf '%%s\\n' "$@" >> '%s'
			for last; do :; done
			out="${last#file:}"
			case "$*" in
				*scale=*) sleep 30; head -c 1000000 /dev/zero > "$out" ;;
				*) head -c 7000000 /dev/zero > "$out" ;;
			esac
			""".formatted(args));
		Path input = Files.write(dir.resolve("in.bin"), new byte[INPUT_BYTES]);
		Path output = Files.createFile(dir.resolve("out.bin"));

		long startedAt = System.nanoTime();
		// Orçamento total de 12s: o transcode fica com 2s e o remux com o resto.
		runner(ffmpeg, MediaFfmpegRunner.DEFAULT_TRANSCODE_MAX_BYTES)
			.clean(input, output, "video/mp4", Duration.ofSeconds(12));
		long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

		assertThat(String.join(" ", Files.readAllLines(args))).contains("-c copy");
		assertThat(Files.size(output)).isEqualTo(7_000_000);
		assertThat(elapsedMs).as("o upload termina perto do orçamento, não com 30s de transcode")
			.isLessThan(20_000L);
	}

	private static MediaFfmpegRunner runner(Path ffmpeg, long transcodeMaxBytes) {
		return new MediaFfmpegRunner(ffmpeg.toString(), 1, transcodeMaxBytes, Duration.ofSeconds(90));
	}

	private Path script(String body) throws Exception {
		Path script = dir.resolve("ffmpeg-" + System.nanoTime() + ".sh");
		Files.writeString(script, "#!/bin/sh\n" + body, StandardCharsets.UTF_8);
		Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
		return script;
	}
}