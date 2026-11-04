package com.socialcoisa.rede.media.adapters.out.classification;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;

import org.w3c.dom.Node;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import com.socialcoisa.rede.media.application.port.out.ImageContentClassifier;

/** Yahoo OpenNSFW ResNet-50 model through ONNX Runtime's CPU execution provider. */
@Component
@Lazy(false)
@ConditionalOnProperty(prefix = "rede.media.nsfw", name = "enabled", havingValue = "true", matchIfMissing = true)
class OnnxImageContentClassifier implements ImageContentClassifier {
	static final int RESIZED_EDGE = 256;
	static final int CROP_EDGE = 224;
	static final int MAX_IMAGE_PIXELS = 16_000_000;
	/** Animated GIFs: frames scored per image, and the decode budget (frames x pixels) that bounds the replay. */
	static final int MAX_SAMPLED_FRAMES = 8;
	static final int MAX_ANIMATION_FRAMES = 1_000;
	static final long MAX_ANIMATION_PIXELS = 400_000_000L;
	private static final String GIF_STREAM_FORMAT = "javax_imageio_gif_stream_1.0";
	private static final String GIF_IMAGE_FORMAT = "javax_imageio_gif_image_1.0";
	private static final float[] BGR_MEAN = { 104.0f, 117.0f, 123.0f };

	private final OrtEnvironment environment;
	private final OrtSession session;
	private final String inputName;
	private final String outputName;

	OnnxImageContentClassifier(@Value("${rede.media.nsfw.model-path:/app/data/open-nsfw.onnx}") String modelPath,
			@Value("${rede.media.nsfw.threads:2}") int threads) throws OrtException {
		if (threads < 1 || threads > 8) {
			throw new IllegalArgumentException("rede.media.nsfw.threads precisa estar entre 1 e 8.");
		}
		this.environment = OrtEnvironment.getEnvironment();
		// Model inference must remain local; ONNX Runtime's optional telemetry is disabled explicitly.
		this.environment.setTelemetry(false);
		OrtSession.SessionOptions options = new OrtSession.SessionOptions();
		options.setIntraOpNumThreads(threads);
		options.setInterOpNumThreads(1);
		options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
		this.session = environment.createSession(Path.of(modelPath).toString(), options);
		this.inputName = session.getInputNames().iterator().next();
		this.outputName = session.getOutputNames().iterator().next();
	}

	@Override
	public double pornographicScore(byte[] imageBytes, String mimeType) throws IOException {
		if (imageBytes == null || imageBytes.length == 0) {
			throw new IOException("Imagem vazia.");
		}
		try (ImageInputStream imageInput = ImageIO.createImageInputStream(new ByteArrayInputStream(imageBytes))) {
			if (imageInput == null) {
				throw new IOException("Não foi possível abrir a imagem.");
			}
			Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
			if (!readers.hasNext()) {
				throw new IOException("Formato de imagem não reconhecido.");
			}
			ImageReader reader = readers.next();
			try {
				reader.setInput(imageInput, false, false);
				int frames = Math.max(1, reader.getNumImages(true));
				int width = reader.getWidth(0);
				int height = reader.getHeight(0);
				if ((long) width * height > MAX_IMAGE_PIXELS) {
					throw new IOException("A imagem excede o limite de resolução para verificação.");
				}
				requireSupportedFrames(reader.getFormatName(), frames, width, height);
				// Uma imagem animada vale pelo seu quadro mais impróprio: basta um passar do limiar.
				double score = 0.0;
				for (BufferedImage image : framesToScore(reader, frames)) {
					score = Math.max(score, classify(image));
				}
				return score;
			}
			finally {
				reader.dispose();
			}
		}
		catch (OrtException failure) {
			throw new IllegalStateException("Falha na inferência ONNX.", failure);
		}
	}

	static void requireSupportedFrames(String formatName, int frames, int width, int height) throws IOException {
		if (frames == 1) {
			return;
		}
		// ImageIO exposes animated frames as partial rectangles. Only GIF has a compositor here; any
		// other animated format stays rejected, since scoring bare rectangles could miss content.
		boolean compositable = "gif".equalsIgnoreCase(formatName)
			&& frames <= MAX_ANIMATION_FRAMES
			&& (long) width * height * frames <= MAX_ANIMATION_PIXELS;
		if (!compositable) {
			throw new IOException("Imagens animadas não podem ser verificadas com segurança.");
		}
	}

	private static List<BufferedImage> framesToScore(ImageReader reader, int frames) throws IOException {
		if (frames == 1) {
			BufferedImage image = reader.read(0);
			if (image == null) {
				throw new IOException("Não foi possível decodificar a imagem.");
			}
			return List.of(image);
		}
		return compositeGifFrames(reader, frames);
	}

	/** Evenly spaced frame positions, always including the first and the last one. */
	static int[] sampledFrameIndexes(int frames, int maxSamples) {
		int count = Math.min(frames, maxSamples);
		int[] indexes = new int[count];
		for (int i = 0; i < count; i++) {
			indexes[i] = count == 1 ? 0 : (int) ((long) i * (frames - 1) / (count - 1));
		}
		return indexes;
	}

	/**
	 * Replays the GIF as a viewer would (frame position and disposal method) and returns the full
	 * canvas at each sampled frame, so what is scored is what people actually see.
	 */
	static List<BufferedImage> compositeGifFrames(ImageReader reader, int frames) throws IOException {
		int[] wanted = sampledFrameIndexes(frames, MAX_SAMPLED_FRAMES);
		int canvasWidth = reader.getWidth(0);
		int canvasHeight = reader.getHeight(0);
		IIOMetadata stream = reader.getStreamMetadata();
		if (stream != null) {
			Node screen = child(stream.getAsTree(GIF_STREAM_FORMAT), "LogicalScreenDescriptor");
			canvasWidth = Math.max(canvasWidth, attribute(screen, "logicalScreenWidth", 0));
			canvasHeight = Math.max(canvasHeight, attribute(screen, "logicalScreenHeight", 0));
		}
		if ((long) canvasWidth * canvasHeight > MAX_IMAGE_PIXELS) {
			throw new IOException("A imagem excede o limite de resolução para verificação.");
		}
		BufferedImage canvas = new BufferedImage(canvasWidth, canvasHeight, BufferedImage.TYPE_INT_ARGB);
		List<BufferedImage> sampled = new ArrayList<>(wanted.length);
		int next = 0;
		for (int index = 0; index <= wanted[wanted.length - 1]; index++) {
			BufferedImage frame = reader.read(index);
			if (frame == null) {
				throw new IOException("Não foi possível decodificar a imagem.");
			}
			Node root = reader.getImageMetadata(index).getAsTree(GIF_IMAGE_FORMAT);
			Node descriptor = child(root, "ImageDescriptor");
			int left = attribute(descriptor, "imageLeftPosition", 0);
			int top = attribute(descriptor, "imageTopPosition", 0);
			String disposal = attributeText(child(root, "GraphicControlExtension"), "disposalMethod", "none");
			BufferedImage before = "restoreToPrevious".equals(disposal) ? copyOf(canvas) : null;
			Graphics2D graphics = canvas.createGraphics();
			try {
				graphics.drawImage(frame, left, top, null);
			}
			finally {
				graphics.dispose();
			}
			if (index == wanted[next]) {
				sampled.add(copyOf(canvas));
				next++;
			}
			if ("restoreToBackgroundColor".equals(disposal)) {
				Graphics2D clear = canvas.createGraphics();
				try {
					clear.setComposite(AlphaComposite.Clear);
					clear.fillRect(left, top, frame.getWidth(), frame.getHeight());
				}
				finally {
					clear.dispose();
				}
			}
			else if (before != null) {
				canvas = before;
			}
		}
		return sampled;
	}

	private static BufferedImage copyOf(BufferedImage source) {
		BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = copy.createGraphics();
		try {
			graphics.drawImage(source, 0, 0, null);
		}
		finally {
			graphics.dispose();
		}
		return copy;
	}

	private static Node child(Node parent, String name) {
		if (parent == null) {
			return null;
		}
		for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
			if (name.equals(node.getNodeName())) {
				return node;
			}
		}
		return null;
	}

	private static String attributeText(Node node, String name, String fallback) {
		if (node == null || node.getAttributes() == null || node.getAttributes().getNamedItem(name) == null) {
			return fallback;
		}
		return node.getAttributes().getNamedItem(name).getNodeValue();
	}

	private static int attribute(Node node, String name, int fallback) {
		try {
			return Integer.parseInt(attributeText(node, name, String.valueOf(fallback)));
		}
		catch (NumberFormatException invalid) {
			return fallback;
		}
	}

	private double classify(BufferedImage source) throws OrtException, IOException {
		FloatBuffer input = preprocess(source);
		try (OnnxTensor tensor = OnnxTensor.createTensor(environment, input,
			new long[] { 1, CROP_EDGE, CROP_EDGE, 3 });
				OrtSession.Result result = session.run(Map.of(inputName, tensor), java.util.Set.of(outputName))) {
			Object value = result.get(outputName).get().getValue();
			if (!(value instanceof float[][] scores) || scores.length != 1 || scores[0].length != 2) {
				throw new IllegalStateException("Saída inesperada do modelo OpenNSFW.");
			}
			return scores[0][1];
		}
	}

	/** Input layout is NHWC; pixels are BGR with the training-set mean subtracted. */
	static FloatBuffer preprocess(BufferedImage source) {
		BufferedImage resized = new BufferedImage(RESIZED_EDGE, RESIZED_EDGE, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = resized.createGraphics();
		try {
			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			graphics.drawImage(source, 0, 0, RESIZED_EDGE, RESIZED_EDGE, null);
		}
		finally {
			graphics.dispose();
		}
		int offset = (RESIZED_EDGE - CROP_EDGE) / 2;
		FloatBuffer buffer = FloatBuffer.allocate(CROP_EDGE * CROP_EDGE * 3);
		for (int y = offset; y < offset + CROP_EDGE; y++) {
			for (int x = offset; x < offset + CROP_EDGE; x++) {
				int rgb = resized.getRGB(x, y);
				buffer.put(((rgb) & 0xff) - BGR_MEAN[0]);
				buffer.put(((rgb >>> 8) & 0xff) - BGR_MEAN[1]);
				buffer.put(((rgb >>> 16) & 0xff) - BGR_MEAN[2]);
			}
		}
		buffer.flip();
		return buffer;
	}

	@PreDestroy
	void close() throws OrtException {
		session.close();
	}
}
