package com.socialcoisa.rede.media.adapters.out.classification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.FloatBuffer;
import java.util.ArrayList;
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

import com.socialcoisa.rede.media.application.port.out.ImageContentClassifier;

class OnnxImageContentClassifierTests {

	@Test
	void scoresEveryDistinctFrameAndKeepsTheMostExplicitOne() throws Exception {
		// 51 frames: only frame 3 is red. Evenly spaced sampling (0, 7, 14, ...) never looked at it.
		GifFrame[] frames = new GifFrame[51];
		for (int i = 0; i < frames.length; i++) {
			frames[i] = i == 3 ? new GifFrame(solid(8, 8, Color.RED), 0, 0, "restoreToPrevious")
				: new GifFrame(solid(8, 8, Color.BLUE), 0, 0, "doNotDispose");
		}
		List<Float> scoredInputs = new ArrayList<>();

		ImageContentClassifier.Assessment assessment = assess(gif(8, 8, frames), input -> {
			scoredInputs.add(input.get(0));
			return redScore(input);
		});

		assertThat(assessment.score()).isEqualTo(1.0);
		// Identical canvases have the same model input and are scored once: one blue, one red.
		assertThat(scoredInputs).hasSize(2);
		BufferedImage flagged = ImageIO.read(new ByteArrayInputStream(assessment.reviewFrame()));
		assertThat(rgb(flagged, 4, 4)).isEqualTo(Color.RED.getRGB() & 0xFFFFFF);
	}

	@Test
	void scoresAStillImageOnceWithoutAReviewFrame() throws Exception {
		ByteArrayOutputStream png = new ByteArrayOutputStream();
		ImageIO.write(solid(8, 8, Color.RED), "png", png);

		ImageContentClassifier.Assessment assessment = assess(png.toByteArray(),
			OnnxImageContentClassifierTests::redScore);

		assertThat(assessment.score()).isEqualTo(1.0);
		assertThat(assessment.reviewFrame()).isNull();
	}

	@Test
	void rejectsLargeLaterFramesBehindATinyFirstFrameBeforeDecodingAnything() throws Exception {
		int[][] frames = new int[31][];
		frames[0] = new int[] { 0, 0, 1, 1 };
		for (int i = 1; i < frames.length; i++) {
			frames[i] = new int[] { 0, 0, 4000, 4000 };
		}
		byte[] gif = headerOnlyGif(1, 1, frames);
		int[] scored = { 0 };

		assertThatThrownBy(() -> assess(gif, input -> {
			scored[0]++;
			return 0.0;
		})).isInstanceOf(ImageContentClassifier.TooLargeToVerifyException.class);
		assertThat(scored[0]).isZero();
		// The budget is enforced from the frame headers alone: the pixel data here is not even valid.
		assertThatThrownBy(() -> inspect(gif)).isInstanceOf(ImageContentClassifier.TooLargeToVerifyException.class);
	}

	@Test
	void rejectsTooManyFramesAndFramesPlacedOutsideTheBudget() throws Exception {
		int[][] tooMany = new int[GifAnimation.MAX_FRAMES + 1][];
		java.util.Arrays.fill(tooMany, new int[] { 0, 0, 1, 1 });
		assertThatThrownBy(() -> inspect(headerOnlyGif(1, 1, tooMany)))
			.isInstanceOf(ImageContentClassifier.TooLargeToVerifyException.class);

		// A 1x1 frame far from the origin grows the canvas a viewer may show beyond the pixel cap.
		assertThatThrownBy(() -> inspect(headerOnlyGif(1, 1, new int[] { 0, 0, 1, 1 },
			new int[] { 65_000, 65_000, 1, 1 }))).isInstanceOf(ImageContentClassifier.TooLargeToVerifyException.class);

		assertThatCode(() -> inspect(headerOnlyGif(1, 1, new int[] { 0, 0, 1, 1 }, new int[] { 0, 0, 1, 1 })))
			.doesNotThrowAnyException();
	}

	@Test
	void refusesAnimatedFormatsWhoseReaderOnlyExposesTheStillImage() {
		byte[] apng = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 13, 'I', 'H', 'D', 'R', 0, 0, 0, 1, 0,
			0, 0, 1, 8, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 8, 'a', 'c', 'T', 'L', 0, 0, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0 };
		byte[] animatedWebp = { 'R', 'I', 'F', 'F', 30, 0, 0, 0, 'W', 'E', 'B', 'P', 'V', 'P', '8', 'X', 10, 0, 0, 0,
			0x02, 0, 0, 0, 0, 0, 0, 0, 0, 0 };
		byte[] avifSequence = { 0, 0, 0, 24, 'f', 't', 'y', 'p', 'a', 'v', 'i', 'f', 0, 0, 0, 0, 'a', 'v', 'i', 'f',
			'a', 'v', 'i', 's' };
		byte[] stillWebp = animatedWebp.clone();
		stillWebp[20] = 0;

		assertThat(AnimatedContainers.isAnimated(apng)).isTrue();
		assertThat(AnimatedContainers.isAnimated(animatedWebp)).isTrue();
		assertThat(AnimatedContainers.isAnimated(avifSequence)).isTrue();
		assertThat(AnimatedContainers.isAnimated(stillWebp)).isFalse();
	}

	private static double redScore(FloatBuffer input) {
		// First pixel's blue channel minus the training mean: negative only when there is no blue.
		return input.get(0) < 0 ? 1.0 : 0.0;
	}

	private static ImageContentClassifier.Assessment assess(byte[] image,
			OnnxImageContentClassifier.FrameScorer scorer) throws Exception {
		try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(image))) {
			ImageReader reader = ImageIO.getImageReaders(input).next();
			try {
				reader.setInput(input, false, false);
				return OnnxImageContentClassifier.assessFrames(reader, reader.getNumImages(true), scorer);
			}
			finally {
				reader.dispose();
			}
		}
	}

	private static GifAnimation inspect(byte[] gif) throws Exception {
		try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(gif))) {
			ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
			try {
				reader.setInput(input, false, false);
				return GifAnimation.inspect(reader, reader.getNumImages(true));
			}
			finally {
				reader.dispose();
			}
		}
	}

	/**
	 * A structurally valid GIF whose frame descriptors declare {left, top, width, height} while each
	 * frame carries only a few bytes of image data: enough to be counted and inspected, never decoded.
	 */
	private static byte[] headerOnlyGif(int screenWidth, int screenHeight, int[]... frames) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.writeBytes("GIF89a".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
		littleEndian(out, screenWidth);
		littleEndian(out, screenHeight);
		out.writeBytes(new byte[] { (byte) 0x80, 0, 0, 0, 0, 0, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF });
		for (int[] frame : frames) {
			out.write(0x2C);
			for (int value : frame) {
				littleEndian(out, value);
			}
			out.writeBytes(new byte[] { 0, 2, 2, 0x44, 0x01, 0 });
		}
		out.write(0x3B);
		return out.toByteArray();
	}

	private static void littleEndian(ByteArrayOutputStream out, int value) {
		out.write(value & 0xFF);
		out.write((value >>> 8) & 0xFF);
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
				List<BufferedImage> frames = new ArrayList<>();
				GifAnimation.inspect(reader, reader.getNumImages(true))
					.replay(canvas -> frames.add(GifAnimation.copyOf(canvas)));
				return frames;
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
