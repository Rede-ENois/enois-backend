package com.socialcoisa.rede.media.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Drops container metadata from video/audio with ffmpeg, without transcoding ({@code -c copy}).
 *
 * <p>The invocation is fixed: arguments go straight to {@link ProcessBuilder} (no shell), both paths
 * are server-generated temporary files, the demuxer and the muxer are pinned from the validated MIME
 * type and only the {@code file} protocol is allowed, so the file content alone decides nothing about
 * what ffmpeg opens. {@code rede.media.ffmpeg.max-concurrent} caps how many processes run at once.</p>
 *
 * <p>Every failure rejects the upload with a named error: storing the original would publish the
 * metadata this step exists to remove.</p>
 */
@Component
public class MediaFfmpegRunner {

	private static final Logger LOG = LoggerFactory.getLogger(MediaFfmpegRunner.class);

	/** How long an upload waits for a free ffmpeg slot before it is told to retry. */
	static final Duration PERMIT_WAIT = Duration.ofSeconds(30);

	private record Formats(String demuxer, String muxer) {
	}

	/** Keys are the video/audio types {@code MediaService} accepts. */
	private static final Map<String, Formats> FORMATS = Map.of(
		"video/mp4", new Formats("mov", "mp4"),
		"video/quicktime", new Formats("mov", "mov"),
		"video/webm", new Formats("matroska", "webm"),
		"audio/webm", new Formats("matroska", "webm"),
		"audio/mpeg", new Formats("mp3", "mp3"),
		"audio/ogg", new Formats("ogg", "ogg"),
		"audio/wav", new Formats("wav", "wav"));

	private final String ffmpegPath;
	private final Semaphore permits;

	public MediaFfmpegRunner(@Value("${rede.media.ffmpeg-path:ffmpeg}") String ffmpegPath,
			@Value("${rede.media.ffmpeg.max-concurrent:2}") int maxConcurrent) {
		if (maxConcurrent < 1) {
			throw new IllegalArgumentException("rede.media.ffmpeg.max-concurrent precisa ser pelo menos 1.");
		}
		this.ffmpegPath = ffmpegPath;
		this.permits = new Semaphore(maxConcurrent, true);
	}

	/** Writes {@code input} without metadata to {@code output}; throws {@link ApiException} on any failure. */
	void strip(Path input, Path output, String mimeType, Duration timeout) {
		Formats formats = FORMATS.get(mimeType);
		if (formats == null) {
			LOG.warn("media_ffmpeg_rejected reason=unsupported_type mime={}", mimeType);
			throw rejected(null);
		}
		acquirePermit(mimeType);
		try {
			run(command(formats, input, output), output, mimeType, timeout);
		}
		finally {
			permits.release();
		}
	}

	private List<String> command(Formats formats, Path input, Path output) {
		return List.of(ffmpegPath, "-hide_banner", "-nostdin", "-loglevel", "error", "-y",
			"-protocol_whitelist", "file", "-f", formats.demuxer(), "-i", "file:" + input.toAbsolutePath(),
			"-map_metadata", "-1", "-map_chapters", "-1", "-c", "copy",
			"-f", formats.muxer(), "file:" + output.toAbsolutePath());
	}

	private void acquirePermit(String mimeType) {
		try {
			if (!permits.tryAcquire(PERMIT_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
				LOG.warn("media_ffmpeg_busy mime={} waitedMs={}", mimeType, PERMIT_WAIT.toMillis());
				throw unavailable(null);
			}
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw unavailable(interrupted);
		}
	}

	private void run(List<String> command, Path output, String mimeType, Duration timeout) {
		long startedAt = System.nanoTime();
		Process process;
		try {
			// Output is discarded: nobody reads it, and a verbose ffmpeg would fill the pipe and stall.
			process = new ProcessBuilder(command).redirectErrorStream(true)
				.redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
		}
		catch (IOException failure) {
			LOG.error("media_ffmpeg_unavailable mime={} error={}", mimeType, failure.getClass().getSimpleName());
			throw unavailable(failure);
		}
		try {
			if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
				process.destroyForcibly();
				LOG.warn("media_ffmpeg_rejected reason=timeout mime={} elapsedMs={}", mimeType, elapsedMs(startedAt));
				throw rejected(null);
			}
			if (process.exitValue() != 0 || Files.size(output) == 0) {
				LOG.warn("media_ffmpeg_rejected reason=exit mime={} exit={} elapsedMs={}", mimeType,
					process.exitValue(), elapsedMs(startedAt));
				throw rejected(null);
			}
		}
		catch (InterruptedException interrupted) {
			process.destroyForcibly();
			Thread.currentThread().interrupt();
			LOG.warn("media_ffmpeg_interrupted mime={} elapsedMs={}", mimeType, elapsedMs(startedAt));
			throw unavailable(interrupted);
		}
		catch (IOException failure) {
			LOG.error("media_ffmpeg_output_unreadable mime={} error={}", mimeType, failure.getClass().getSimpleName());
			throw unavailable(failure);
		}
	}

	private static long elapsedMs(long startedAt) {
		return (System.nanoTime() - startedAt) / 1_000_000L;
	}

	private static ApiException unavailable(Throwable cause) {
		return new ApiException(503, "MEDIA_PROCESSING_UNAVAILABLE",
			"Não foi possível processar o arquivo agora. Tente novamente em instantes.", cause);
	}

	static ApiException rejected(Throwable cause) {
		return new ApiException(422, "MEDIA_PROCESSING_FAILED",
			"Não foi possível processar este arquivo. Confira o formato e tente novamente.", cause);
	}
}
