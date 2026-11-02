package com.socialcoisa.rede.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.socialcoisa.rede.media.application.port.out.ImageContentClassifier;
import com.socialcoisa.rede.shared.web.ApiException;

class MediaImageModerationServiceTests {

	private final ObjectProvider<ImageContentClassifier> provider = mock();
	private final ImageContentClassifier classifier = mock();

	private MediaImageModerationService service(boolean enabled, double threshold) {
		return new MediaImageModerationService(provider, enabled, threshold);
	}

	@Test
	void permitsImagesBelowThreshold() throws Exception {
		when(provider.getIfAvailable()).thenReturn(classifier);
		when(classifier.pornographicScore(new byte[] { 1 }, "image/jpeg")).thenReturn(0.69);

		service(true, 0.70).verifyBeforeStorage(new byte[] { 1 }, "image/jpeg");

		verify(classifier).pornographicScore(new byte[] { 1 }, "image/jpeg");
	}

	@Test
	void blocksScoreAtThreshold() throws Exception {
		when(provider.getIfAvailable()).thenReturn(classifier);
		when(classifier.pornographicScore(new byte[] { 1 }, "image/png")).thenReturn(0.70);

		assertThatThrownBy(() -> service(true, 0.70).verifyBeforeStorage(new byte[] { 1 }, "image/png"))
			.isInstanceOf(ApiException.class)
			.extracting("code").isEqualTo("IMAGE_CONTENT_NOT_ALLOWED");
	}

	@Test
	void doesNotClassifyNonImageMedia() {
		service(true, 0.70).verifyBeforeStorage(new byte[] { 1 }, "video/mp4");

		verifyNoInteractions(provider, classifier);
	}

	@Test
	void disabledModerationDoesNotLoadOrCallTheClassifier() {
		service(false, 0.70).verifyBeforeStorage(new byte[] { 1 }, "image/jpeg");

		verifyNoInteractions(provider, classifier);
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
		when(classifier.pornographicScore(new byte[] { 1 }, "image/webp"))
			.thenThrow(new IOException("invalid image"));

		assertThatThrownBy(() -> service(true, 0.70).verifyBeforeStorage(new byte[] { 1 }, "image/webp"))
			.isInstanceOf(ApiException.class)
			.extracting("code").isEqualTo("IMAGE_CANNOT_BE_VERIFIED");
	}

	@Test
	void failsClosedWhenClassifierReturnsInvalidScore() throws Exception {
		when(provider.getIfAvailable()).thenReturn(classifier);
		when(classifier.pornographicScore(new byte[] { 1 }, "image/jpeg")).thenReturn(Double.NaN);

		assertThatThrownBy(() -> service(true, 0.70).verifyBeforeStorage(new byte[] { 1 }, "image/jpeg"))
			.isInstanceOf(ApiException.class)
			.extracting("code").isEqualTo("IMAGE_MODERATION_UNAVAILABLE");
	}

	@Test
	void rejectsInvalidThresholdAtStartup() {
		assertThatThrownBy(() -> service(true, 1.1)).isInstanceOf(IllegalArgumentException.class);
	}

}
