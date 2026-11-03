package com.socialcoisa.rede.media.adapters.out.classification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.FloatBuffer;
import java.util.List;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

import org.junit.jupiter.api.Test;

class OnnxImageContentClassifierTests {

	@Test
	void acceptsAnimatedGifsWithinTheBudgetAndRejectsEveryOtherAnimation() throws Exception {
		OnnxImageContentClassifier.requireSupportedFrames("jpeg", 1, 320, 320);
		OnnxImageContentClassifier.requireSupportedFrames("gif", 51, 320, 320);

		assertThatIOException().isThrownBy(() -> OnnxImageContentClassifier.requireSupportedFrames("tiff", 2, 8, 8))
				.withMessageContaining("animadas");
		assertThatIOException().isThrownBy(() -> OnnxImageContentClassifier
				.requireSupportedFrames("gif", OnnxImageContentClassifier.MAX_ANIMATION_FRAMES + 1, 8, 8))
				.withMessageContaining("animadas");
		assertThatIOException().isThrownBy(() -> OnnxImageContentClassifier.requireSupportedFrames("gif", 500, 4000, 4000))
				.withMessageContaining("animadas");
	}

	@Test
	void samplesFramesEvenlyKeepingTheFirstAndTheLast() {
		assertThat(OnnxImageContentClassifier.sampledFrameIndexes(1, 8)).containsExactly(0);
		assertThat(OnnxImageContentClassifier.sampledFrameIndexes(3, 8)).containsExactly(0, 1, 2);
		assertThat(OnnxImageContentClassifier.sampledFrameIndexes(51, 8)).containsExactly(0, 7, 14, 21, 28, 35, 42, 50);
		assertThat(OnnxImageContentClassifier.sampledFrameIndexes(1000, 8)).hasSize(8).doesNotHaveDuplicates()
				.startsWith(0).endsWith(999);
	}

	@Test
	void compositesPartialGifFramesOntoTheFullCanvas() throws Exception {
		// Quadro 0: tela toda vermelha. Quadro 1: so um quadrado azul 4x4 em (2,2), mantendo o fundo.
		byte[] gif = gif(8, 8,
				new GifFrame(solid(8, 8, Color.RED), 0, 0, "doNotDispose"),
				new GifFrame(solid(4, 4, Color.BLUE), 2, 2, "doNotDispose"));

		List<BufferedImage> frames = composite(gif);

		assertThat(frames).hasSize(2);
		assertThat(frames.get(1).getWidth()).isEqualTo(8);
		assertThat(frames.get(1).getHeight()).isEqualTo(8);
		assertThat(rgb(frames.get(1), 0, 0)).isEqualTo(Color.RED.getRGB() & 0xFFFFFF);
		assertThat(rgb(frames.get(1), 3, 3)).isEqualTo(Color.BLUE.getRGB() & 0xFFFFFF);
	}

	@Test
	void honorsTheDisposalMethodBetweenFrames() throws Exception {
		// O quadrado azul do quadro 1 e descartado (volta ao fundo) antes do quadro 2, que so pinta 1 pixel.
		byte[] gif = gif(8, 8,
				new GifFrame(solid(8, 8, Color.RED), 0, 0, "doNotDispose"),
				new GifFrame(solid(4, 4, Color.BLUE), 2, 2, "restoreToPrevious"),
				new GifFrame(solid(1, 1, Color.GREEN), 7, 7, "doNotDispose"));

		List<BufferedImage> frames = composite(gif);

		assertThat(frames).hasSize(3);
		assertThat(rgb(frames.get(1), 3, 3)).isEqualTo(Color.BLUE.getRGB() & 0xFFFFFF);
		assertThat(rgb(frames.get(2), 3, 3)).isEqualTo(Color.RED.getRGB() & 0xFFFFFF);
		assertThat(rgb(frames.get(2), 7, 7)).isEqualTo(Color.GREEN.getRGB() & 0xFFFFFF);
	}

	private static List<BufferedImage> composite(byte[] gif) throws Exception {
		try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(gif))) {
			ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
			try {
				reader.setInput(input, false, false);
				return OnnxImageContentClassifier.compositeGifFrames(reader, reader.getNumImages(true));
			}
			finally {
				reader.dispose();
			}
		}
	}

	private static int rgb(BufferedImage image, int x, int y) {
		return image.getRGB(x, y) & 0xFFFFFF;
	}

	private static BufferedImage solid(int width, int height, Color color) {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				image.setRGB(x, y, color.getRGB());
			}
		}
		return image;
	}

	private record GifFrame(BufferedImage image, int left, int top, String disposal) {
	}

	private static byte[] gif(int width, int height, GifFrame... frames) throws Exception {
		ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ImageOutputStream output = ImageIO.createImageOutputStream(bytes)) {
			writer.setOutput(output);
			ImageWriteParam param = writer.getDefaultWriteParam();
			IIOMetadata stream = writer.getDefaultStreamMetadata(param);
			String streamFormat = "javax_imageio_gif_stream_1.0";
			IIOMetadataNode streamRoot = (IIOMetadataNode) stream.getAsTree(streamFormat);
			IIOMetadataNode screen = new IIOMetadataNode("LogicalScreenDescriptor");
			screen.setAttribute("logicalScreenWidth", String.valueOf(width));
			screen.setAttribute("logicalScreenHeight", String.valueOf(height));
			screen.setAttribute("colorResolution", "8");
			screen.setAttribute("pixelAspectRatio", "0");
			streamRoot.appendChild(screen);
			stream.mergeTree(streamFormat, streamRoot);
			writer.prepareWriteSequence(stream);
			for (GifFrame frame : frames) {
				IIOMetadata metadata = writer.getDefaultImageMetadata(
						ImageTypeSpecifier.createFromRenderedImage(frame.image()), param);
				String imageFormat = "javax_imageio_gif_image_1.0";
				IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(imageFormat);
				IIOMetadataNode descriptor = new IIOMetadataNode("ImageDescriptor");
				descriptor.setAttribute("imageLeftPosition", String.valueOf(frame.left()));
				descriptor.setAttribute("imageTopPosition", String.valueOf(frame.top()));
				descriptor.setAttribute("imageWidth", String.valueOf(frame.image().getWidth()));
				descriptor.setAttribute("imageHeight", String.valueOf(frame.image().getHeight()));
				descriptor.setAttribute("interlaceFlag", "FALSE");
				root.removeChild(root.getElementsByTagName("ImageDescriptor").item(0));
				root.insertBefore(descriptor, root.getFirstChild());
				IIOMetadataNode control = (IIOMetadataNode) root.getElementsByTagName("GraphicControlExtension").item(0);
				control.setAttribute("disposalMethod", frame.disposal());
				control.setAttribute("userInputFlag", "FALSE");
				control.setAttribute("transparentColorFlag", "FALSE");
				control.setAttribute("delayTime", "5");
				control.setAttribute("transparentColorIndex", "0");
				metadata.setFromTree(imageFormat, root);
				writer.writeToSequence(new IIOImage(frame.image(), null, metadata), param);
			}
			writer.endWriteSequence();
		}
		finally {
			writer.dispose();
		}
		return bytes.toByteArray();
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
