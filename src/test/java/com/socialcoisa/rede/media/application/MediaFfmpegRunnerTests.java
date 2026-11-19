package com.socialcoisa.rede.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import com.socialcoisa.rede.shared.web.ApiException;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/** The ffmpeg step either cleans the file or rejects the upload; it never hands back the original. */
class MediaFfmpegRunnerTests {

	@TempDir
	static Path launchers;

	private static FfmpegProcessoDeTeste processos;

	@BeforeAll
	static void prepararLaunchers() throws Exception {
		processos = FfmpegProcessoDeTeste.criar(Files.createDirectory(launchers.resolve("launchers com espaços")));
	}

	@TempDir
	Path dir;

	private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

	@BeforeEach
	void capturarLogDoRunner() {
		logs.start();
		((Logger) LoggerFactory.getLogger(MediaFfmpegRunner.class)).addAppender(logs);
	}

	@AfterEach
	void soltarLogDoRunner() {
		((Logger) LoggerFactory.getLogger(MediaFfmpegRunner.class)).detachAppender(logs);
		logs.stop();
	}

	/** Todas as linhas que o runner escreveu, juntas, para procurar a causa técnica. */
	private String linhasDoRunner() {
		return logs.list.stream().map(ILoggingEvent::getFormattedMessage)
			.reduce("", (acumulado, linha) -> acumulado + linha + "\n");
	}

	@Test
	void pinsTheFormatsAndTheProtocolFromTheValidatedType() throws Exception {
		Path args = dir.resolve("args.txt");
		Path cenario = Files.createDirectory(dir.resolve("cenário com espaços"));
		Path ffmpeg = processos.ffmpeg(cenario, args, 5, 5);
		Path input = Files.writeString(dir.resolve("entrada com espaços.bin"), "video");
		Path output = Files.createFile(dir.resolve("out.bin"));

		new MediaFfmpegRunner(ffmpeg.toString(), 1).strip(input, output, "video/mp4", Duration.ofSeconds(10));

		List<String> invocation = Files.readAllLines(args);
		assertThat(String.join(" ", invocation))
			.contains("-protocol_whitelist file -f mov -i file:" + input.toAbsolutePath())
			.contains("-map_metadata -1")
			.endsWith("-f mp4 file:" + output.toAbsolutePath());
		assertThat(Files.readString(output)).isEqualTo("clean");
	}

	/**
	 * O {@code moov} no fim do arquivo é o que trava o player: o navegador precisa baixar dezenas de
	 * MB até achar a duração, e o card do feed fica sem quadro e sem tempo (issue #537). O
	 * {@code +faststart} move o índice para o começo sem tocar no bitstream — é remux, não
	 * transcodificação, e o {@code -c copy} continua no comando.
	 */
	@Test
	void movesTheMp4IndexToTheFrontWithoutTouchingTheBitstream() throws Exception {
		Path args = dir.resolve("args.txt");
		Path ffmpeg = processos.ffmpeg(dir, args, 5, 5);
		Path input = Files.writeString(dir.resolve("in.bin"), "video");
		Path output = Files.createFile(dir.resolve("out.bin"));

		new MediaFfmpegRunner(ffmpeg.toString(), 1).strip(input, output, "video/mp4", Duration.ofSeconds(10));

		assertThat(String.join(" ", Files.readAllLines(args)))
			.contains("-c copy -movflags +faststart -f mp4")
			.as("o faststart vem depois do -c copy e antes do muxer");
	}

	@Test
	void leavesTheOtherContainersWithoutTheMp4OnlyFlag() throws Exception {
		Path args = dir.resolve("args.txt");
		Path ffmpeg = processos.ffmpeg(dir, args, 5, 5);
		Path input = Files.writeString(dir.resolve("in.bin"), "video");
		Path output = Files.createFile(dir.resolve("out.bin"));

		new MediaFfmpegRunner(ffmpeg.toString(), 1).strip(input, output, "video/webm", Duration.ofSeconds(10));

		assertThat(String.join(" ", Files.readAllLines(args))).doesNotContain("-movflags");
	}

	@Test
	void rejectsAFailedRunInsteadOfKeepingTheOriginal() throws Exception {
		Path ffmpeg = processos.ffmpeg(dir, null, -1, -1);
		Path input = Files.writeString(dir.resolve("in.bin"), "video");
		Path output = Files.createFile(dir.resolve("out.bin"));

		assertThatThrownBy(() -> new MediaFfmpegRunner(ffmpeg.toString(), 1).strip(input, output, "video/webm",
			Duration.ofSeconds(10))).isInstanceOf(ApiException.class).extracting("code")
			.isEqualTo("MEDIA_PROCESSING_FAILED");
	}

	/**
	 * A causa técnica sai no log mesmo quando a resposta ao cliente é a mesma (#960): sem isso, o
	 * timeout de um arquivo que o ffmpeg recusou é indistinguível do processo que não subiu, e o
	 * suporte fica sem o que ler no evento.
	 */
	@Test
	void recordsTheFfmpegOutputWhenTheRunRefusesTheFile() throws Exception {
		Path ffmpeg = processos.falhaComMensagem(dir, "Invalid data found when processing input");
		Path input = Files.writeString(dir.resolve("in.bin"), "video");
		Path output = Files.createFile(dir.resolve("out.bin"));

		assertThatThrownBy(() -> new MediaFfmpegRunner(ffmpeg.toString(), 1).strip(input, output, "video/mp4",
			Duration.ofSeconds(10))).isInstanceOf(ApiException.class).extracting("code")
			.isEqualTo("MEDIA_PROCESSING_FAILED");
		assertThat(linhasDoRunner()).contains("reason=exit").contains("Invalid data found when processing input");
	}

	/** Sem saída nenhuma, o log diz isso em vez de fingir uma causa que o processo não escreveu. */
	@Test
	void recordsThatTheRunProducedNoOutputAtAll() throws Exception {
		Path ffmpeg = processos.ffmpeg(dir, null, -1, -1);
		Path input = Files.writeString(dir.resolve("in.bin"), "video");
		Path output = Files.createFile(dir.resolve("out.bin"));

		assertThatThrownBy(() -> new MediaFfmpegRunner(ffmpeg.toString(), 1).strip(input, output, "audio/ogg",
			Duration.ofSeconds(10))).isInstanceOf(ApiException.class).extracting("code")
			.isEqualTo("MEDIA_PROCESSING_FAILED");
		assertThat(linhasDoRunner()).contains("reason=exit").contains("output=(sem saida)");
	}

	/**
	 * Timeout legítimo continua recusando com o mesmo código (#960): a captura da saída é
	 * diagnóstico, não uma porta nova para o upload passar.
	 */
	@Test
	void keepsTimingOutAsARejectedUploadEvenWithPartialOutput() throws Exception {
		Path ffmpeg = processos.demoradoComMensagem(dir, "frame= 1 fps=0.0 q=0.0");
		Path input = Files.writeString(dir.resolve("in.bin"), "video");
		Path output = Files.createFile(dir.resolve("out.bin"));

		assertThatThrownBy(() -> new MediaFfmpegRunner(ffmpeg.toString(), 1).strip(input, output, "video/mp4",
			Duration.ofSeconds(1))).isInstanceOf(ApiException.class).extracting("code")
			.isEqualTo("MEDIA_PROCESSING_FAILED");
		assertThat(linhasDoRunner()).contains("reason=timeout");
	}

	@Test
	void reportsAMissingBinaryAsUnavailable() throws Exception {
		Path input = Files.writeString(dir.resolve("in.bin"), "audio");
		Path output = Files.createFile(dir.resolve("out.bin"));

		assertThatThrownBy(() -> new MediaFfmpegRunner(dir.resolve("missing-ffmpeg").toString(), 1).strip(input,
			output, "audio/mpeg", Duration.ofSeconds(10))).isInstanceOf(ApiException.class).extracting("code")
			.isEqualTo("MEDIA_PROCESSING_UNAVAILABLE");
	}

	@Test
	void rejectsATypeWithoutPinnedFormats() throws Exception {
		Path ffmpeg = processos.ffmpeg(dir, null, 0, 0);
		Path input = Files.writeString(dir.resolve("in.bin"), "video");

		assertThatThrownBy(() -> new MediaFfmpegRunner(ffmpeg.toString(), 1).strip(input, input, "video/x-flv",
			Duration.ofSeconds(10))).isInstanceOf(ApiException.class).extracting("code")
			.isEqualTo("MEDIA_PROCESSING_FAILED");
	}

	@Test
	void interruptionKillsTheProcessFreesTheSlotAndKeepsTheInterruptFlag() throws Exception {
		Path pids = dir.resolve("pids.txt");
		Path ffmpeg = processos.demorado(dir, null, pids, false);
		MediaFfmpegRunner runner = new MediaFfmpegRunner(ffmpeg.toString(), 1);
		Path input = Files.writeString(dir.resolve("in.bin"), "video");

		Attempt first = start(runner, input, dir.resolve("out-1.bin"));
		long pid = awaitPid(pids, 1);
		// The only slot is taken: the second upload waits for it and is interrupted while waiting.
		Attempt second = start(runner, input, dir.resolve("out-2.bin"));
		Thread.sleep(200);
		second.thread().interrupt();
		second.done().get(5, TimeUnit.SECONDS);
		assertThat(second.failure().get()).isInstanceOf(ApiException.class).extracting("code")
			.isEqualTo("MEDIA_PROCESSING_UNAVAILABLE");
		assertThat(second.interruptKept()).isTrue();
		assertThat(Files.readAllLines(pids)).hasSize(1);

		first.thread().interrupt();
		first.done().get(5, TimeUnit.SECONDS);
		assertThat(first.failure().get()).isInstanceOf(ApiException.class).extracting("code")
			.isEqualTo("MEDIA_PROCESSING_UNAVAILABLE");
		assertThat(first.interruptKept()).isTrue();
		assertThat(ProcessHandle.of(pid).map(handle -> {
			handle.onExit().orTimeout(5, TimeUnit.SECONDS).join();
			return handle.isAlive();
		}).orElse(false)).isFalse();

		// The slot was released: a new run starts right away.
		Attempt third = start(runner, input, dir.resolve("out-3.bin"));
		long thirdPid = awaitPid(pids, 2);
		third.thread().interrupt();
		third.done().get(5, TimeUnit.SECONDS);
		assertThat(third.failure().get()).isInstanceOf(ApiException.class).extracting("code")
			.isEqualTo("MEDIA_PROCESSING_UNAVAILABLE");
		assertThat(third.interruptKept()).isTrue();
		assertThat(ProcessHandle.of(thirdPid).map(handle -> {
			handle.onExit().orTimeout(5, TimeUnit.SECONDS).join();
			return handle.isAlive();
		}).orElse(false)).isFalse();
	}

	private record Attempt(Thread thread, CompletableFuture<Void> done, AtomicReference<Throwable> failure,
			AtomicBoolean interruptFlag) {

		boolean interruptKept() {
			return interruptFlag.get();
		}
	}

	private static Attempt start(MediaFfmpegRunner runner, Path input, Path output) {
		CompletableFuture<Void> done = new CompletableFuture<>();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		AtomicBoolean interrupted = new AtomicBoolean();
		Thread thread = new Thread(() -> {
			try {
				runner.strip(input, output, "video/mp4", Duration.ofSeconds(30));
			}
			catch (RuntimeException error) {
				failure.set(error);
			}
			interrupted.set(Thread.currentThread().isInterrupted());
			done.complete(null);
		});
		thread.start();
		return new Attempt(thread, done, failure, interrupted);
	}

	private static long awaitPid(Path pids, int count) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (System.nanoTime() < deadline) {
			if (Files.exists(pids)) {
				List<String> lines = Files.readAllLines(pids);
				if (lines.size() >= count) {
					return Long.parseLong(lines.get(count - 1).trim());
				}
			}
			Thread.sleep(20);
		}
		throw new AssertionError("ffmpeg stand-in did not start");
	}

}
