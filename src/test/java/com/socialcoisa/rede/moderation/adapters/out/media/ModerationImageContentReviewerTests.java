package com.socialcoisa.rede.moderation.adapters.out.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Optional;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.media.application.port.out.ImageContentReviewer.Result;
import com.socialcoisa.rede.moderation.ai.MediaSafetyAnalyzer;
import com.socialcoisa.rede.moderation.domain.MediaSafetyVerdict;

class ModerationImageContentReviewerTests {

	private final MediaSafetyAnalyzer analyzer = mock();
	private final ModerationImageContentReviewer reviewer = new ModerationImageContentReviewer(analyzer);

	@Test
	void confirmsOnlyAHighConfidenceViolation() {
		when(analyzer.analyze(any())).thenReturn(Optional.of(new MediaSafetyAnalyzer.Verdict(
			MediaSafetyVerdict.EXPLICIT, "HIGH", 0.9, "conteúdo explícito")));

		assertThat(reviewer.review(imageBytes())).contains(Result.PROHIBITED);
	}

	@Test
	void confirmsASafeImage() {
		when(analyzer.analyze(any())).thenReturn(Optional.of(new MediaSafetyAnalyzer.Verdict(
			MediaSafetyVerdict.SAFE, "LOW", 0.99, "tela")));

		assertThat(reviewer.review(imageBytes())).contains(Result.SAFE);
	}

	@Test
	void returnsNoReviewWhenSemanticModelHasNoVerdict() {
		when(analyzer.analyze(any())).thenReturn(Optional.empty());

		assertThat(reviewer.review(imageBytes())).isEmpty();
	}

	@Test
	void doesNotTreatLowConfidenceSafeAsConfirmation() {
		when(analyzer.analyze(any())).thenReturn(Optional.of(new MediaSafetyAnalyzer.Verdict(
			MediaSafetyVerdict.SAFE, "LOW", 0.4, "incerto")));

		assertThat(reviewer.review(imageBytes())).contains(Result.UNSURE);
	}

	private static byte[] imageBytes() {
		try {
			BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			ImageIO.write(image, "png", bytes);
			return bytes.toByteArray();
		}
		catch (java.io.IOException failure) {
			throw new IllegalStateException(failure);
		}
	}
}
