package com.socialcoisa.rede.media.application.port.out;

import java.util.Optional;

/** A semantic second opinion for images flagged by the fast local classifier. */
public interface ImageContentReviewer {
	enum Result {
		SAFE,
		PROHIBITED,
		UNSURE
	}

	/** Empty means the semantic model could not return a usable review. */
	Optional<Result> review(byte[] imageBytes);
}
