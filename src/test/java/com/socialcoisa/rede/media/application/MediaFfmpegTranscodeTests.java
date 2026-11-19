package com.socialcoisa.rede.media.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.socialcoisa.rede.shared.web.ApiException;

/**
 * O que a issue #652 tranca: o vídeo subia no tamanho original porque o ffmpeg só remuxava
 * ({@code -c copy}). Depois desta etapa ele é reencodado no tamanho que o feed mostra, e o arquivo
 * menor é o que vai para o bucket.
 *
 * <p>A prova usa um launcher nativo gerado pelo JDK no lugar do ffmpeg (o CI não o instala).
 * A JVM do dublê registra a invocação e grava um arquivo do tamanho que o teste manda. Assim dá
 * para afirmar as três coisas que são o contrato desta etapa — quais argumentos chegam, qual saída
 * fica em disco e o que acontece quando a compressão não vale a pena.</p>
 */
class MediaFfmpegTranscodeTests {

	/** 8 MB de entrada, acima do mínimo de 4 MB: o corte de 10% fica em 7,2 MB, então 7 MB salva e 7,9 MB não. */
	private static final int INPUT_BYTES = 8_000_000;

	@TempDir
	static Path launchers;

	private static FfmpegProcessoDeTeste processos;

	@BeforeAll
	static void prepararLaunchers() throws Exception {
		processos = FfmpegProcessoDeTeste.criar(launchers);
	}

	@TempDir
	Path dir;

	/**
	 * Antes: o único comando era o remux com {@code -c copy} e o arquivo gravado tinha exatamente o
	 * tamanho do enviado. Agora: o transcode roda primeiro e a saída menor é a que fica.
	 */
	@Test
	void compressesTheVideoAndKeepsTheSmallerFile() throws Exception {
		Path args = dir.resolve("args.txt");
		Path ffmpeg = processos.ffmpeg(dir, args, 1_000_000, 1_000_000);
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
		Path ffmpeg = processos.ffmpeg(dir, args, 7_900_000, 7_000_000);
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
		Path ffmpeg = processos.ffmpeg(dir, args, -1, 7_000_000);
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
		Path ffmpeg = processos.ffmpeg(dir, args, 7_000_000, 7_000_000);
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
		Path ffmpeg = processos.ffmpeg(dir, args, 7_000_000, 7_000_000);
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
		Path ffmpeg = processos.ffmpeg(dir, args, 7_000_000, 7_000_000);
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
		Path ffmpeg = processos.ffmpeg(dir, args, 1_000_000, 1_000_000);
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
			.contains("min(854,iw)")
			.contains("min(854,ih)")
			.isEqualTo("scale=w='if(gt(iw,ih),min(854,iw),-2)':h='if(gt(iw,ih),-2,min(854,ih))'");
	}

	/** O teto de tempo reserva o remux: um transcode que estoura o orçamento não pode zerar o fallback. */
	@Test
	void theTranscodeBudgetLeavesRoomForTheRemux() throws Exception {
		Path args = dir.resolve("args.txt");
		Path ffmpeg = processos.demorado(dir, args, null, true);
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

	private MediaFfmpegRunner runner(Path ffmpeg, long transcodeMaxBytes) throws Exception {
		Path probeOutput = Files.writeString(dir.resolve("probe.txt"), "60.0");
		return runner(ffmpeg, probeOutput, transcodeMaxBytes);
	}

	/**
	 * Runner com o ffprobe apontando para {@code probeOutput}: o processo Java devolve a
	 * duração que o teste escreveu lá, simulando o ffprobe real sem depender de binário.
	 */
	private MediaFfmpegRunner runner(Path ffmpeg, Path probeOutput, long transcodeMaxBytes) throws Exception {
		Path probe = processos.probe(dir, null, probeOutput);
		return new MediaFfmpegRunner(ffmpeg.toString(), probe.toString(), 1, transcodeMaxBytes,
			Duration.ofSeconds(90));
	}

	/**
	 * Vídeo acima de 3 minutos é recusado com erro nomeado antes de qualquer processamento (issue
	 * #656): o ffprobe roda primeiro, o ffmpeg nem chega a ser chamado.
	 */
	@Test
	void rejectsAVideoLongerThanThreeMinutesBeforeProcessing() throws Exception {
		Path args = dir.resolve("args-too-long.txt");
		Path probeOutput = dir.resolve("probe-too-long.txt");
		Files.writeString(probeOutput, "181.0");
		Path ffmpeg = processos.ffmpeg(dir, args, 7_000_000, 7_000_000);
		Path input = Files.write(dir.resolve("in-too-long.bin"), new byte[INPUT_BYTES]);
		Path output = Files.createFile(dir.resolve("out-too-long.bin"));

		org.assertj.core.api.Assertions.assertThatThrownBy(
				() -> runner(ffmpeg, probeOutput, MediaFfmpegRunner.DEFAULT_TRANSCODE_MAX_BYTES).clean(input, output,
					"video/mp4", Duration.ofSeconds(120)))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("code", "MEDIA_VIDEO_TOO_LONG");
		assertThat(Files.exists(args)).as("o ffmpeg nem rodou: a recusa vem antes do processamento").isFalse();
	}

	/** Exatamente 3 minutos passa; a margem de meio segundo cobre só o arredondamento do contêiner. */
	@Test
	void acceptsAVideoAtExactlyThreeMinutes() throws Exception {
		Path probeOutput = dir.resolve("probe-at-limit.txt");
		Files.writeString(probeOutput, "180.0");
		Path input = Files.write(dir.resolve("in-at-limit.bin"), new byte[INPUT_BYTES]);
		Path output = Files.createFile(dir.resolve("out-at-limit.bin"));

		runner(script(), probeOutput, MediaFfmpegRunner.DEFAULT_TRANSCODE_MAX_BYTES).clean(input, output, "video/mp4",
			Duration.ofSeconds(120));

		assertThat(Files.size(output)).isGreaterThan(0);
	}

	/** Um probe que falha não pode recusar arquivo bom: duração desconhecida segue o fluxo antigo. */
	@Test
	void aFailingProbeDoesNotRejectTheUpload() throws Exception {
		Path probeOutput = dir.resolve("probe-broken.txt");
		Files.writeString(probeOutput, "nao-e-numero");
		Path input = Files.write(dir.resolve("in-broken.bin"), new byte[INPUT_BYTES]);
		Path output = Files.createFile(dir.resolve("out-broken.bin"));

		runner(script(), probeOutput, MediaFfmpegRunner.DEFAULT_TRANSCODE_MAX_BYTES).clean(input, output, "video/mp4",
			Duration.ofSeconds(120));

		assertThat(Files.size(output)).isGreaterThan(0);
	}

	/** Áudio não é vídeo: o probe de duração nem roda para ele. */
	@Test
	void audioIsNeverProbedForDuration() throws Exception {
		Path probeArgs = dir.resolve("probe-args.txt");
		Path probeOutput = dir.resolve("probe-audio.txt");
		Files.writeString(probeOutput, "99999.0");
		Path probe = processos.probe(dir, probeArgs, probeOutput);
		Path input = Files.write(dir.resolve("in-audio.bin"), new byte[INPUT_BYTES]);
		Path output = Files.createFile(dir.resolve("out-audio.bin"));

		new MediaFfmpegRunner(script().toString(), probe.toString(), 1, MediaFfmpegRunner.DEFAULT_TRANSCODE_MAX_BYTES,
			Duration.ofSeconds(90)).clean(input, output, "audio/mpeg", Duration.ofSeconds(120));

		assertThat(Files.exists(probeArgs)).as("o probe não roda para áudio").isFalse();
	}


	/** ffmpeg dos testes de fluxo: grava 7MB no último argumento, como o remux faria. */
	private Path script() throws Exception {
		return processos.ffmpeg(dir, null, 7_000_000, 7_000_000);
	}
}