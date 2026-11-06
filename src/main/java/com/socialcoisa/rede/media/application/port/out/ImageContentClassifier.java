package com.socialcoisa.rede.media.application.port.out;

import java.io.IOException;

/** CPU classifier for an uploaded image. Scores pornographic content from 0 to 1. */
public interface ImageContentClassifier {

	/**
	 * Scores every frame a viewer would see and returns the highest score. Throws
	 * {@link TooLargeToVerifyException} when the image does not fit the decode budget, and a plain
	 * {@link IOException} when it cannot be decoded at all.
	 */
	Assessment assess(byte[] imageBytes, String mimeType) throws IOException;

	/**
	 * {@code reviewFrame} is the highest-scoring frame of an animation, encoded as PNG, so a second
	 * opinion looks at the same pixels that were flagged. It is null for a still image, whose own
	 * bytes are the frame.
	 */
	record Assessment(double score, byte[] reviewFrame) {
	}

	/** The image declares more frames or pixels than the classifier is allowed to decode. */
	class TooLargeToVerifyException extends IOException {

		public TooLargeToVerifyException(String message) {
			super(message);
		}
	}
}
