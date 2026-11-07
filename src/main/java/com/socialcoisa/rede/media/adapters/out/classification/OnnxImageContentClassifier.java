package com.socialcoisa.rede.media.adapters.out.classification;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import com.socialcoisa.rede.media.application.ImageDecoding;
import com.socialcoisa.rede.media.application.port.out.ImageContentClassifier;

/** Yahoo OpenNSFW ResNet-50 model through ONNX Runtime's CPU execution provider. */
@Component
@Lazy(false)
@ConditionalOnProperty(prefix = "rede.media.nsfw", name = "enabled", havingValue = "true", matchIfMissing = true)
class OnnxImageContentClassifier implements ImageContentClassifier {
	static final int RESIZED_EDGE = 256;
	static final int CROP_EDGE = 224;
	private static final String ANIMATION_NOT_VERIFIABLE = "Imagens animadas neste formato não podem ser verificadas com segurança.";
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
	public Assessment assess(byte[] imageBytes, String mimeType) throws IOException {
		if (imageBytes == null || imageBytes.length == 0) {
			throw new IOException("Imagem vazia.");
		}
		if (AnimatedContainers.isAnimated(imageBytes)) {
			throw new IOException(ANIMATION_NOT_VERIFIABLE);
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
				return assessFrames(reader, Math.max(1, reader.getNumImages(true)), this::classify);
			}
			finally {
				reader.dispose();
			}
		}
	}

	@FunctionalInterface
	interface FrameScorer {
		double score(FloatBuffer input) throws IOException;
	}

	/**
	 * A still image is scored once. An animated GIF is scored on every distinct composited frame and
	 * is worth its most explicit one; identical frames (same model input) are scored once.
	 */
	static Assessment assessFrames(ImageReader reader, int frames, FrameScorer scorer) throws IOException {
		if (frames == 1) {
			if ((long) reader.getWidth(0) * reader.getHeight(0) > ImageDecoding.MAX_PIXELS) {
				throw new TooLargeToVerifyException("A imagem excede o limite de resolução para verificação.");
			}
			BufferedImage image = reader.read(0);
			if (image == null) {
				throw new IOException("Não foi possível decodificar a imagem.");
			}
			return new Assessment(scorer.score(preprocess(image)), null);
		}
		// ImageIO exposes animated frames as partial rectangles; only GIF has a compositor here.
		if (!"gif".equalsIgnoreCase(reader.getFormatName())) {
			throw new IOException(ANIMATION_NOT_VERIFIABLE);
		}
		GifAnimation animation = GifAnimation.inspect(reader, frames);
		Set<ByteBuffer> scored = new HashSet<>();
		double[] worstScore = { -1.0 };
		BufferedImage[] worstFrame = { null };
		animation.replay(canvas -> {
			FloatBuffer input = preprocess(canvas);
			if (!scored.add(digest(input))) {
				return;
			}
			double score = scorer.score(input);
			if (score > worstScore[0]) {
				worstScore[0] = score;
				worstFrame[0] = GifAnimation.copyOf(canvas);
			}
		});
		return new Assessment(worstScore[0], png(worstFrame[0]));
	}

	private static ByteBuffer digest(FloatBuffer input) {
		ByteBuffer bytes = ByteBuffer.allocate(input.remaining() * Float.BYTES);
		bytes.asFloatBuffer().put(input.duplicate());
		try {
			return ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(bytes.array()));
		}
		catch (NoSuchAlgorithmException missing) {
			throw new IllegalStateException("SHA-256 indisponível.", missing);
		}
	}

	private static byte[] png(BufferedImage image) throws IOException {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		if (!ImageIO.write(image, "png", output)) {
			throw new IOException("Não foi possível preparar o quadro para revisão.");
		}
		return output.toByteArray();
	}

	private double classify(FloatBuffer input) {
		try (OnnxTensor tensor = OnnxTensor.createTensor(environment, input,
			new long[] { 1, CROP_EDGE, CROP_EDGE, 3 });
				OrtSession.Result result = session.run(Map.of(inputName, tensor), Set.of(outputName))) {
			Object value = result.get(outputName).get().getValue();
			if (!(value instanceof float[][] scores) || scores.length != 1 || scores[0].length != 2) {
				throw new IllegalStateException("Saída inesperada do modelo OpenNSFW.");
			}
			return scores[0][1];
		}
		catch (OrtException failure) {
			throw new IllegalStateException("Falha na inferência ONNX.", failure);
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
