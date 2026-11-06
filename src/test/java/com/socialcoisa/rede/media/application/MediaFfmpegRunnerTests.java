package com.socialcoisa.rede.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.socialcoisa.rede.shared.web.ApiException;

/** The ffmpeg step either cleans the file or rejects the upload; it never hands back the original. */
class MediaFfmpegRunnerTests {

	@TempDir
	Path dir;

	@Test
	void pinsTheFormatsAndTheProtocolFromTheValidatedType() throws Exception {
		Path args = dir.resolve("args.txt");
		Path ffmpeg = script("printf '%s\\n' \"$@\" > '" + args + "'\nfor last; do :; done\n"
			+ "printf clean > \"${last#file:}\"\n");
		Path input = Files.writeString(dir.resolve("in.bin"), "video");
		Path output = Files.createFile(dir.resolve("out.bin"));

		new MediaFfmpegRunner(ffmpeg.toString(), 1).strip(input, output, "video/mp4", Duration.ofSeconds(10));

		List<String> invocation = Files.readAllLines(args);
		assertThat(String.join(" ", invocation))
			.contains("-protocol_whitelist file -f mov -i file:" + input.toAbsolutePath())
			.contains("-map_metadata -1")
			.endsWith("-f mp4 file:" + output.toAbsolutePath());
		assertThat(Files.readString(output)).isEqualTo("clean");
	}

	@Test
	void rejectsAFailedRunInsteadOfKeepingTheOriginal() throws Exception {
		Path ffmpeg = script("exit 1\n");
		Path input = Files.writeString(dir.resolve("in.bin"), "video");
		Path output = Files.createFile(dir.resolve("out.bin"));

		assertThatThrownBy(() -> new MediaFfmpegRunner(ffmpeg.toString(), 1).strip(input, output, "video/webm",
			Duration.ofSeconds(10))).isInstanceOf(ApiException.class).extracting("code")
			.isEqualTo("MEDIA_PROCESSING_FAILED");
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
		Path ffmpeg = script("exit 0\n");
		Path input = Files.writeString(dir.resolve("in.bin"), "video");

		assertThatThrownBy(() -> new MediaFfmpegRunner(ffmpeg.toString(), 1).strip(input, input, "video/x-flv",
			Duration.ofSeconds(10))).isInstanceOf(ApiException.class).extracting("code")
			.isEqualTo("MEDIA_PROCESSING_FAILED");
	}

	@Test
	void interruptionKillsTheProcessFreesTheSlotAndKeepsTheInterruptFlag() throws Exception {
		Path pids = dir.resolve("pids.txt");
		Path ffmpeg = script("echo $$ >> '" + pids + "'\nexec sleep 30\n");
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
		awaitPid(pids, 2);
		third.thread().interrupt();
		third.done().get(5, TimeUnit.SECONDS);
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

	private Path script(String body) throws Exception {
		Path script = dir.resolve("ffmpeg-" + System.nanoTime() + ".sh");
		Files.writeString(script, "#!/bin/sh\n" + body, StandardCharsets.UTF_8);
		Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
		return script;
	}
}
