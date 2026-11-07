package com.socialcoisa.rede.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.socialcoisa.rede.media.application.port.out.ImageContentClassifier;
import com.socialcoisa.rede.media.application.port.out.ImageContentReviewer;
import com.socialcoisa.rede.media.application.port.out.ImageContentReviewer.Result;
import com.socialcoisa.rede.shared.web.ApiException;

class MediaImageModerationServiceTests {

	private final ObjectProvider<ImageContentClassifier> provider = mock();
	private final ImageContentClassifier classifier = mock();
	private final ImageContentReviewer reviewer = mock();

	private MediaImageModerationService service(boolean enabled, double threshold) {
		return new MediaImageModerationService(provider, reviewer, enabled, threshold);
	}

	@Test
	void permitsImagesBelowThreshold() throws Exception {
		when(provider.getIfAvailable()).thenReturn(classifier);
		when(classifier.assess(new byte[] { 1 }, "image/jpeg")).thenReturn(still(0.69));

		service(true, 0.70).verifyBeforeStorage(new byte[] { 1 }, "image/jpeg");

		verify(classifier).assess(new byte[] { 1 }, "image/jpeg");
	}

	@Test
	void blocksScoreAtThreshold() throws Exception {
		when(provider.getIfAvailable()).thenReturn(classifier);
		when(classifier.assess(new byte[] { 1 }, "image/png")).thenReturn(still(0.70));
		when(reviewer.review(new byte[] { 1 })).thenReturn(Optional.of(Result.PROHIBITED));

		assertThatThrownBy(() -> service(true, 0.70).verifyBeforeStorage(new byte[] { 1 }, "image/png"))
			.isInstanceOf(ApiException.class)
			.extracting("code").isEqualTo("IMAGE_CONTENT_NOT_ALLOWED");
	}

	@Test
	void allowsAnImageWhenTheCoarseClassifierFlagsItButSemanticReviewDoesNotConfirmIt() throws Exception {
		when(provider.getIfAvailable()).thenReturn(classifier);
		when(classifier.assess(new byte[] { 1 }, "image/jpeg")).thenReturn(still(0.95));
		when(reviewer.review(new byte[] { 1 })).thenReturn(Optional.of(Result.SAFE));

		service(true, 0.70).verifyBeforeStorage(new byte[] { 1 }, "image/jpeg");

		verify(reviewer).review(new byte[] { 1 });
	}

	@Test
	void reviewsTheFlaggedFrameOfAnAnimationInsteadOfTheOriginalFile() throws Exception {
		byte[] flaggedFrame = { 9, 9 };
		when(provider.getIfAvailable()).thenReturn(classifier);
		when(classifier.assess(new byte[] { 1 }, "image/gif"))
			.thenReturn(new ImageContentClassifier.Assessment(0.95, flaggedFrame));
		when(reviewer.review(flaggedFrame)).thenReturn(Optional.of(Result.PROHIBITED));
		when(reviewer.review(new byte[] { 1 })).thenReturn(Optional.of(Result.SAFE));

		assertThatThrownBy(() -> service(true, 0.70).verifyBeforeStorage(new byte[] { 1 }, "image/gif"))
			.isInstanceOf(ApiException.class)
			.extracting("code").isEqualTo("IMAGE_CONTENT_NOT_ALLOWED");
	}

	@Test
	void namesTheRejectionWhenTheImageIsTooLargeToVerify() throws Exception {
		when(provider.getIfAvailable()).thenReturn(classifier);
		when(classifier.assess(new byte[] { 1 }, "image/gif"))
			.thenThrow(new ImageContentClassifier.TooLargeToVerifyException("budget"));

		assertThatThrownBy(() -> service(true, 0.70).verifyBeforeStorage(new byte[] { 1 }, "image/gif"))
			.isInstanceOf(ApiException.class)
			.extracting("code").isEqualTo("IMAGE_TOO_LARGE_TO_VERIFY");
		verifyNoInteractions(reviewer);
	}

	@Test
	void doesNotApproveAnImageWhenSemanticReviewIsInconclusive() throws Exception {
		when(provider.getIfAvailable()).thenReturn(classifier);
		when(classifier.assess(new byte[] { 1 }, "image/jpeg")).thenReturn(still(0.95));
		when(reviewer.review(new byte[] { 1 })).thenReturn(Optional.of(Result.UNSURE));

		assertThatThrownBy(() -> service(true, 0.70).verifyBeforeStorage(new byte[] { 1 }, "image/jpeg"))
			.isInstanceOf(ApiException.class)
			.extracting("code").isEqualTo("IMAGE_MODERATION_UNAVAILABLE");
	}

	@Test
	void doesNotClassifyNonImageMedia() {
		service(true, 0.70).verifyBeforeStorage(new byte[] { 1 }, "video/mp4");

		verifyNoInteractions(provider, classifier, reviewer);
	}

	@Test
	void disabledModerationDoesNotLoadOrCallTheClassifier() {
		service(false, 0.70).verifyBeforeStorage(new byte[] { 1 }, "image/jpeg");

		verifyNoInteractions(provider, classifier, reviewer);
	}

	@Test
	void failsClosedWhenClassifierBeanIsMissing() {
		assertThatThrownBy(() -> service(true, 0.70).verifyBeforeStorage(new byte[] { 1 }, "image/jpeg"))
			.isInstanceOf(ApiException.class)
			.extracting("code").isEqualTo("IMAGE_MODERATION_UNAVAILABLE");
	}

	@Test
	void failsClosedWhenClassifierCannotDecodeTheImage() throws Exception {
		when(provider.getIfAvailable()).thenReturn(classifier);
		when(classifier.assess(new byte[] { 1 }, "image/webp"))
			.thenThrow(new IOException("invalid image"));

		assertThatThrownBy(() -> service(true, 0.70).verifyBeforeStorage(new byte[] { 1 }, "image/webp"))
			.isInstanceOf(ApiException.class)
			.extracting("code").isEqualTo("IMAGE_CANNOT_BE_VERIFIED");
	}

	@Test
	void failsClosedWhenClassifierReturnsInvalidScore() throws Exception {
		when(provider.getIfAvailable()).thenReturn(classifier);
		when(classifier.assess(new byte[] { 1 }, "image/jpeg")).thenReturn(still(Double.NaN));

		assertThatThrownBy(() -> service(true, 0.70).verifyBeforeStorage(new byte[] { 1 }, "image/jpeg"))
			.isInstanceOf(ApiException.class)
			.extracting("code").isEqualTo("IMAGE_MODERATION_UNAVAILABLE");
	}

	@Test
	void rejectsInvalidThresholdAtStartup() {
		assertThatThrownBy(() -> service(true, 1.1)).isInstanceOf(IllegalArgumentException.class);
	}

	private static ImageContentClassifier.Assessment still(double score) {
		return new ImageContentClassifier.Assessment(score, null);
	}
}
