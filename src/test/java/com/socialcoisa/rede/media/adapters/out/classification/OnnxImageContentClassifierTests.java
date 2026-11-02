package com.socialcoisa.rede.media.adapters.out.classification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.FloatBuffer;

import org.junit.jupiter.api.Test;

class OnnxImageContentClassifierTests {

	@Test
	void acceptsOnlySingleFrameImagesUntilAnimatedFramesCanBeCompositedSafely() throws Exception {
		OnnxImageContentClassifier.requireSingleFrame(1);

		assertThatIOException().isThrownBy(() -> OnnxImageContentClassifier.requireSingleFrame(2))
				.withMessageContaining("animadas");
	}

	@Test
	void createsTheNhwcBufferWithBgrChannelsAndTrainingMeanSubtracted() {
		BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < image.getHeight(); y++) {
			for (int x = 0; x < image.getWidth(); x++) {
				image.setRGB(x, y, new Color(255, 30, 10).getRGB());
			}
		}

		FloatBuffer result = OnnxImageContentClassifier.preprocess(image);

		assertThat(result.remaining()).isEqualTo(224 * 224 * 3);
		assertThat(result.get()).isEqualTo(-94.0f); // B: 10 - 104
		assertThat(result.get()).isEqualTo(-87.0f); // G: 30 - 117
		assertThat(result.get()).isEqualTo(132.0f); // R: 255 - 123
	}

	@Test
	void cropsTheCenterOfTheResizedImage() {
		BufferedImage image = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < 256; y++) {
			for (int x = 0; x < 256; x++) {
				image.setRGB(x, y, (x < 16 || y < 16 || x >= 240 || y >= 240)
						? Color.RED.getRGB() : Color.BLUE.getRGB());
			}
		}

		FloatBuffer result = OnnxImageContentClassifier.preprocess(image);

		assertThat(result.get()).isEqualTo(151.0f); // B: 255 - 104; outer red border was cropped.
		assertThat(result.get()).isEqualTo(-117.0f);
		assertThat(result.get()).isEqualTo(-123.0f);
	}

}
