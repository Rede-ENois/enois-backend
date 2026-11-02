package com.socialcoisa.rede.media.application.port.out;

import java.io.IOException;

/** CPU classifier for an already decoded image. Returns a pornographic-content score from 0 to 1. */
public interface ImageContentClassifier {
	double pornographicScore(byte[] imageBytes, String mimeType) throws IOException;
}
