package com.socialcoisa.rede.media.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Drops container metadata from video/audio with ffmpeg and, for video, also shrinks the file.
 *
 * <p>The invocation is fixed: arguments go straight to {@link ProcessBuilder} (no shell), both paths
 * are server-generated temporary files, the demuxer and the muxer are pinned from the validated MIME
 * type and only the {@code file} protocol is allowed, so the file content alone decides nothing about
 * what ffmpeg opens. {@code rede.media.ffmpeg.max-concurrent} caps how many processes run at once.</p>
 *
 * <p>Every failure rejects the upload with a named error: storing the original would publish the
 * metadata this step exists to remove.</p>
 *
 * <p>Compression ({@link #compress}) is a second, optional pass: it re-encodes video to at most
 * 1280px on the long side and keeps the result only when it is at least 10% smaller. Anything else —
 * a type that is not video, a file above {@code rede.media.ffmpeg.transcode-max-bytes}, a transcode
 * that times out or fails, a result that barely shrinks — falls back to the remux this class always
 * did. The two budgets add up to the timeout the caller already allowed, so an upload never waits
 * longer than it did before compression existed.</p>
 */
@Component
public class MediaFfmpegRunner {

	private static final Logger LOG = LoggerFactory.getLogger(MediaFfmpegRunner.class);

	/** How long an upload waits for a free ffmpeg slot before it is told to retry. */
	static final Duration PERMIT_WAIT = Duration.ofSeconds(30);

	private record Formats(String demuxer, String muxer) {
	}

	/**
	 * Tipos em que o {@code moov} vai para o começo do arquivo ({@code -movflags +faststart}).
	 *
	 * <p>O arquivo cru do celular tem o {@code moov} no fim: o player precisa baixar dezenas de MB
	 * até achar a duração, e um card de vídeo no feed fica sem quadro e sem tempo. Com o
	 * {@code moov} na frente, a resposta de 200 com o corpo inteiro basta — o player já sabe a
	 * duração e desenha o primeiro quadro. Só remuxa o índice: {@code -c copy} continua valendo e o
	 * bitstream não muda (ver {@code MediaFfmpegRunnerTests}).</p>
	 *
	 * <p>Fica só no mp4 porque a flag é dele: nos demais contêineres o ffmpeg a ignora sem aviso,
	 * e depender disso seria depender de um comportamento não documentado.</p>
	 */
	private static final Set<String> FASTSTART_TYPES = Set.of("video/mp4");

	/** Keys are the video/audio types {@code MediaService} accepts. */
	private static final Map<String, Formats> FORMATS = Map.of(
		"video/mp4", new Formats("mov", "mp4"),
		"video/quicktime", new Formats("mov", "mov"),
		"video/webm", new Formats("matroska", "webm"),
		"audio/webm", new Formats("matroska", "webm"),
		"audio/mpeg", new Formats("mp3", "mp3"),
		"audio/ogg", new Formats("ogg", "ogg"),
		"audio/wav", new Formats("wav", "wav"));

	/**
	 * Long side of the compressed video. 1280 is the width a feed card and a full-screen phone both
	 * render at; above that the extra pixels are thrown away by the screen, not by the player.
	 *
	 * <p>The filter keeps the aspect ratio and never upscales: {@code min(1280, iw)} on the long side
	 * and {@code -2} (even, same ratio) on the short one. The quotes are part of the argument — the
	 * commas inside {@code if(...)} would otherwise split the filter into separate options, and
	 * {@link ProcessBuilder} passes the string through untouched, so ffmpeg is the one that strips
	 * them.</p>
	 */
	static final String SCALE_FILTER =
		"scale=w='if(gt(iw,ih),min(1280,iw),-2)':h='if(gt(iw,ih),-2,min(1280,ih))'";

	/** A transcode that saves less than this is thrown away and the remux is used instead. */
	static final double MIN_SAVING = 0.10;

	/** Below this the file is already small; the remux is enough and the CPU is spared. */
	static final long COMPRESS_MIN_BYTES = 4L * 1024 * 1024;

	/** Time kept aside for the remux fallback, so a slow transcode cannot eat the whole budget. */
	static final Duration REMUX_RESERVE = Duration.ofSeconds(10);

	static final long DEFAULT_TRANSCODE_MAX_BYTES = 150L * 1024 * 1024;
	static final Duration DEFAULT_TRANSCODE_TIMEOUT = Duration.ofSeconds(90);

	/** Encoder arguments per container: x264/aac in mp4 and mov, vp9/opus in webm. */
	private record Encoders(List<String> video, List<String> audio) {
	}

	private static final Map<String, Encoders> ENCODERS = Map.of(
		"video/mp4", new Encoders(
			List.of("-c:v", "libx264", "-preset", "veryfast", "-crf", "28", "-pix_fmt", "yuv420p",
				"-threads", "2"),
			List.of("-c:a", "aac", "-b:a", "128k")),
		"video/quicktime", new Encoders(
			List.of("-c:v", "libx264", "-preset", "veryfast", "-crf", "28", "-pix_fmt", "yuv420p",
				"-threads", "2"),
			List.of("-c:a", "aac", "-b:a", "128k")),
		"video/webm", new Encoders(
			List.of("-c:v", "libvpx-vp9", "-preset", "veryfast", "-crf", "34", "-b:v", "0", "-row-mt", "1",
				"-threads", "2", "-pix_fmt", "yuv420p"),
			List.of("-c:a", "libopus", "-b:a", "96k")));

	private final String ffmpegPath;
	private final Semaphore permits;
	/** Above this the transcode is skipped (too long for an upload request); 0 disables it. */
	private final long transcodeMaxBytes;
	private final Duration transcodeTimeout;

	@Autowired
	public MediaFfmpegRunner(@Value("${rede.media.ffmpeg-path:ffmpeg}") String ffmpegPath,
			@Value("${rede.media.ffmpeg.max-concurrent:2}") int maxConcurrent,
			@Value("${rede.media.ffmpeg.transcode-max-bytes:157286400}") long transcodeMaxBytes,
			@Value("${rede.media.ffmpeg.transcode-timeout-ms:90000}") long transcodeTimeoutMs) {
		this(ffmpegPath, maxConcurrent, transcodeMaxBytes, Duration.ofMillis(transcodeTimeoutMs));
	}

	/** Same limits as the bean, for tests and call sites that do not need to tune them. */
	MediaFfmpegRunner(String ffmpegPath, int maxConcurrent) {
		this(ffmpegPath, maxConcurrent, DEFAULT_TRANSCODE_MAX_BYTES, DEFAULT_TRANSCODE_TIMEOUT);
	}

	MediaFfmpegRunner(String ffmpegPath, int maxConcurrent, long transcodeMaxBytes, Duration transcodeTimeout) {
		if (maxConcurrent < 1) {
			throw new IllegalArgumentException("rede.media.ffmpeg.max-concurrent precisa ser pelo menos 1.");
		}
		this.ffmpegPath = ffmpegPath;
		this.permits = new Semaphore(maxConcurrent, true);
		this.transcodeMaxBytes = Math.max(0, transcodeMaxBytes);
		this.transcodeTimeout = transcodeTimeout == null ? Duration.ZERO : transcodeTimeout;
	}

	/**
	 * Writes {@code input} to {@code output} without metadata, compressing video when that is
	 * possible and worth it. Throws {@link ApiException} only when neither pass produced a file.
	 *
	 * <p>{@code timeout} is the whole budget for the upload, not one per pass: the transcode gets at
	 * most {@code timeout - REMUX_RESERVE} and the remux that follows gets whatever is left. Without
	 * that split a failed transcode would take the full budget and the fallback would start with no
	 * time at all, turning a slow encode into a refused upload.</p>
	 */
	void clean(Path input, Path output, String mimeType, Duration timeout) {
		Formats formats = FORMATS.get(mimeType);
		if (formats == null) {
			LOG.warn("media_ffmpeg_rejected reason=unsupported_type mime={}", mimeType);
			throw rejected(null);
		}
		long deadline = System.nanoTime() + timeout.toNanos();
		acquirePermit(mimeType);
		try {
			if (compress(input, output, mimeType, formats, timeout)) {
				return;
			}
			Duration remaining = Duration.ofNanos(deadline - System.nanoTime());
			run(command(formats, input, output, FASTSTART_TYPES.contains(mimeType)), output, mimeType,
				remaining.isNegative() || remaining.isZero() ? REMUX_RESERVE : remaining);
		}
		finally {
			permits.release();
		}
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
			run(command(formats, input, output, FASTSTART_TYPES.contains(mimeType)), output, mimeType, timeout);
		}
		finally {
			permits.release();
		}
	}

	/**
	 * Re-encodes {@code input} into {@code output} and returns whether {@code output} is the
	 * compressed file. A compression problem is never an upload failure: the caller still has the
	 * remux, which is what the upload did before this pass existed.
	 */
	private boolean compress(Path input, Path output, String mimeType, Formats formats, Duration timeout) {
		Encoders encoders = ENCODERS.get(mimeType);
		if (encoders == null || transcodeMaxBytes <= 0) {
			return false;
		}
		long budgetMs = Math.min(transcodeTimeout.toMillis(), timeout.toMillis() - REMUX_RESERVE.toMillis());
		if (budgetMs <= 0) {
			return false;
		}
		long inputBytes;
		try {
			inputBytes = Files.size(input);
		}
		catch (IOException unreadable) {
			return false;
		}
		if (inputBytes < COMPRESS_MIN_BYTES || inputBytes > transcodeMaxBytes) {
			LOG.info("media_ffmpeg_transcode_skipped mime={} inputBytes={} reason={}", mimeType, inputBytes,
				inputBytes < COMPRESS_MIN_BYTES ? "small" : "over_limit");
			return false;
		}
		long startedAt = System.nanoTime();
		try {
			run(transcodeCommand(formats, encoders, input, output, FASTSTART_TYPES.contains(mimeType)), output,
				mimeType, Duration.ofMillis(budgetMs));
		}
		catch (ApiException failure) {
			// Timeout, encoder ausente ou arquivo que o ffmpeg nao consegue decodificar: o remux
			// continua valendo e e ele que decide o resultado do upload.
			LOG.warn("media_ffmpeg_transcode_failed mime={} inputBytes={} elapsedMs={} code={}", mimeType, inputBytes,
				elapsedMs(startedAt), failure.code());
			deleteQuietly(output);
			return false;
		}
		try {
			long outputBytes = Files.size(output);
			if (outputBytes == 0 || outputBytes > inputBytes * (1 - MIN_SAVING)) {
				LOG.info("media_ffmpeg_transcode_discarded mime={} inputBytes={} outputBytes={} elapsedMs={}",
					mimeType, inputBytes, outputBytes, elapsedMs(startedAt));
				deleteQuietly(output);
				return false;
			}
			LOG.info("media_ffmpeg_transcoded mime={} inputBytes={} outputBytes={} elapsedMs={}", mimeType,
				inputBytes, outputBytes, elapsedMs(startedAt));
			return true;
		}
		catch (IOException unreadable) {
			deleteQuietly(output);
			return false;
		}
	}

	/** O arquivo descartado não pode transformar uma compressão que falhou em erro de upload. */
	private static void deleteQuietly(Path file) {
		try {
			Files.deleteIfExists(file);
		}
		catch (IOException alreadyGone) {
			LOG.debug("media_ffmpeg_discard_failed path={} error={}", file, alreadyGone.getClass().getSimpleName());
		}
	}

	private List<String> command(Formats formats, Path input, Path output, boolean faststart) {
		List<String> base = List.of(ffmpegPath, "-hide_banner", "-nostdin", "-loglevel", "error", "-y",
			"-protocol_whitelist", "file", "-f", formats.demuxer(), "-i", "file:" + input.toAbsolutePath(),
			"-map_metadata", "-1", "-map_chapters", "-1", "-c", "copy");
		List<String> tail = faststart
			? List.of("-movflags", "+faststart", "-f", formats.muxer(), "file:" + output.toAbsolutePath())
			: List.of("-f", formats.muxer(), "file:" + output.toAbsolutePath());
		return concat(base, tail);
	}

	/**
	 * The transcode invocation. Only the first video stream and the audio streams are kept: subtitles
	 * and data tracks have no player in the feed and would only add bytes. {@code -map 0:a?} keeps a
	 * silent video silent instead of failing on the missing stream.
	 */
	private List<String> transcodeCommand(Formats formats, Encoders encoders, Path input, Path output,
			boolean faststart) {
		List<String> base = List.of(ffmpegPath, "-hide_banner", "-nostdin", "-loglevel", "error", "-y",
			"-protocol_whitelist", "file", "-f", formats.demuxer(), "-i", "file:" + input.toAbsolutePath(),
			"-map", "0:v:0", "-map", "0:a?", "-sn", "-dn",
			"-map_metadata", "-1", "-map_chapters", "-1", "-vf", SCALE_FILTER);
		List<String> tail = faststart
			? List.of("-movflags", "+faststart", "-f", formats.muxer(), "file:" + output.toAbsolutePath())
			: List.of("-f", formats.muxer(), "file:" + output.toAbsolutePath());
		return concat(concat(concat(base, encoders.video()), encoders.audio()), tail);
	}

	private static List<String> concat(List<String> first, List<String> second) {
		List<String> all = new ArrayList<>(first.size() + second.size());
		all.addAll(first);
		all.addAll(second);
		return List.copyOf(all);
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
