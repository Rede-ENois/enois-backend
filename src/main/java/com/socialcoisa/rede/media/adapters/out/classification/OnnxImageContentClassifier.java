package com.socialcoisa.rede.media.adapters.out.classification;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;

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

import com.socialcoisa.rede.media.application.port.out.ImageContentClassifier;

/** Yahoo OpenNSFW ResNet-50 model through ONNX Runtime's CPU execution provider. */
@Component
@Lazy(false)
@ConditionalOnProperty(prefix = "rede.media.nsfw", name = "enabled", havingValue = "true", matchIfMissing = true)
class OnnxImageContentClassifier implements ImageContentClassifier {
	static final int RESIZED_EDGE = 256;
	static final int CROP_EDGE = 224;
	static final int MAX_IMAGE_PIXELS = 16_000_000;
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
				requireSingleFrame(frames);
				int width = reader.getWidth(0);
				int height = reader.getHeight(0);
				if ((long) width * height > MAX_IMAGE_PIXELS) {
					throw new IOException("A imagem excede o limite de resolução para verificação.");
				}
				BufferedImage image = reader.read(0);
				if (image == null) {
					throw new IOException("Não foi possível decodificar a imagem.");
				}
				return classify(image);
			}
			finally {
				reader.dispose();
			}
		}
		catch (OrtException failure) {
			throw new IllegalStateException("Falha na inferência ONNX.", failure);
		}
	}

	static void requireSingleFrame(int frames) throws IOException {
		// ImageIO exposes some formats as partial frame rectangles. Scoring only those rectangles
		// could miss content elsewhere in the displayed animation, so reject until all frames can
		// be composited correctly.
		if (frames != 1) {
			throw new IOException("Imagens animadas não podem ser verificadas com segurança.");
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
