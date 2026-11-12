package com.socialcoisa.rede.media.application;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

import com.socialcoisa.rede.shared.web.ApiException;

/** Stand-in for ffmpeg in web tests: "cleans" by copying, or fails the way the real runner does. */
public class StubFfmpegRunner extends MediaFfmpegRunner {

	private volatile ApiException failure;

	public StubFfmpegRunner() {
		super("ffmpeg-stub", 1);
	}

	/** The next runs throw this; null restores the copying behaviour. */
	public void failWith(ApiException failure) {
		this.failure = failure;
	}

	public static ApiException processingFailed() {
		return rejected(null);
	}

	@Override
	void strip(Path input, Path output, String mimeType, Duration timeout) {
		copyOrFail(input, output);
	}

	/** The sanitizer calls this one; the stub has no encoder, so it behaves like the remux. */
	@Override
	void clean(Path input, Path output, String mimeType, Duration timeout) {
		copyOrFail(input, output);
	}

	private void copyOrFail(Path input, Path output) {
		if (failure != null) {
			throw failure;
		}
		try {
			Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING);
		}
		catch (IOException copyFailed) {
			throw new UncheckedIOException(copyFailed);
		}
	}
}
