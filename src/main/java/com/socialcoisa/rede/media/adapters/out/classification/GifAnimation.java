package com.socialcoisa.rede.media.adapters.out.classification;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;

import org.w3c.dom.Node;

import com.socialcoisa.rede.media.application.ImageDecoding;
import com.socialcoisa.rede.media.application.port.out.ImageContentClassifier.TooLargeToVerifyException;

/**
 * An animated GIF replayed as a viewer shows it (frame position and disposal method), one full
 * canvas per frame.
 *
 * <p>{@link #inspect} reads every frame descriptor before any pixel is decoded, so the budget is the
 * real one: each frame at its own declared size, plus one full canvas per frame for compositing and
 * scoring. Frame delays are ignored on purpose: a zero-delay frame is still drawn by some viewers,
 * so it is still checked.</p>
 */
final class GifAnimation {

	static final int MAX_FRAMES = 300;
	/** Sum of every decoded frame plus one composited canvas per frame. */
	static final long MAX_DECODED_PIXELS = 400_000_000L;
	private static final String GIF_STREAM_FORMAT = "javax_imageio_gif_stream_1.0";
	private static final String GIF_IMAGE_FORMAT = "javax_imageio_gif_image_1.0";

	@FunctionalInterface
	interface CanvasVisitor {
		/** The canvas is reused for the next frame: copy it to keep it. */
		void visit(BufferedImage canvas) throws IOException;
	}

	private record Frame(int left, int top, int width, int height, String disposal) {
	}

	private final ImageReader reader;
	private final List<Frame> frames;
	private final int canvasWidth;
	private final int canvasHeight;
	private final int subsampling;

	private GifAnimation(ImageReader reader, List<Frame> frames, int canvasWidth, int canvasHeight, int subsampling) {
		this.reader = reader;
		this.frames = frames;
		this.canvasWidth = canvasWidth;
		this.canvasHeight = canvasHeight;
		this.subsampling = subsampling;
	}

	static GifAnimation inspect(ImageReader reader, int frameCount) throws IOException {
		if (frameCount > MAX_FRAMES) {
			throw new TooLargeToVerifyException("A animação tem quadros demais para ser verificada.");
		}
		int subsampling = subsamplingFor(reader, frameCount);
		int canvasWidth = 0;
		int canvasHeight = 0;
		IIOMetadata stream = reader.getStreamMetadata();
		if (stream != null) {
			Node screen = child(stream.getAsTree(GIF_STREAM_FORMAT), "LogicalScreenDescriptor");
			canvasWidth = attribute(screen, "logicalScreenWidth", 0);
			canvasHeight = attribute(screen, "logicalScreenHeight", 0);
		}
		List<Frame> frames = new ArrayList<>(frameCount);
		long framePixels = 0;
		for (int index = 0; index < frameCount; index++) {
			Node root = reader.getImageMetadata(index).getAsTree(GIF_IMAGE_FORMAT);
			Node descriptor = child(root, "ImageDescriptor");
			Frame frame = new Frame(attribute(descriptor, "imageLeftPosition", 0),
				attribute(descriptor, "imageTopPosition", 0), attribute(descriptor, "imageWidth", 0),
				attribute(descriptor, "imageHeight", 0),
				attributeText(child(root, "GraphicControlExtension"), "disposalMethod", "none"));
			if (frame.width() <= 0 || frame.height() <= 0) {
				throw new IOException("Quadro de GIF inválido.");
			}
			long pixels = (long) frame.width() * frame.height();
			framePixels += pixels / (long) subsampling * subsampling;
			// Viewers that grow the canvas to fit a frame show all of it, so all of it is scored.
			canvasWidth = Math.max(canvasWidth, frame.left() + frame.width());
			canvasHeight = Math.max(canvasHeight, frame.top() + frame.height());
			frames.add(frame);
		}
		return new GifAnimation(reader, List.copyOf(frames), canvasWidth, canvasHeight, subsampling);
	}

	/**
	 * Fator de subamostragem que traz o orcamento de decode para dentro do teto, em vez de recusar
	 * a animacao. O classificador trabalha em {@code 256x256} por quadro: reduzir NAO perde poder de
	 * moderacao, e antes qualquer GIF longo (ex.: 301 quadros de tela de celular) era recusado com
	 * "quadros demais". Um fator por animacao mantem a contagem de pixels previsivel.
	 */
	private static int subsamplingFor(ImageReader reader, int frameCount) throws IOException {
		int source = 1;
		while (source <= 8) {
			long framePixels = 0;
			long canvasPixels = 0;
			int canvasWidth = 0;
			int canvasHeight = 0;
			boolean overFrame = false;
			for (int index = 0; index < frameCount; index++) {
				Node root = reader.getImageMetadata(index).getAsTree(GIF_IMAGE_FORMAT);
				Node descriptor = child(root, "ImageDescriptor");
				// Divisao inteira pode zerar um quadro pequeno (1/8 = 0): o piso de 1 mantem o
				// pixel contabilizado, que e o conservador para o orcamento.
				int w = Math.max(1, attribute(descriptor, "imageWidth", 0) / source);
				int h = Math.max(1, attribute(descriptor, "imageHeight", 0) / source);
				if (w <= 0 || h <= 0) {
					continue;
				}
				long pixels = (long) w * h;
				if (pixels > ImageDecoding.MAX_PIXELS) {
					overFrame = true;
					break;
				}
				framePixels += pixels;
				canvasWidth = Math.max(canvasWidth, attribute(descriptor, "imageLeftPosition", 0) / source + w);
				canvasHeight = Math.max(canvasHeight, attribute(descriptor, "imageTopPosition", 0) / source + h);
			}
			if (!overFrame) {
				canvasPixels = (long) canvasWidth * canvasHeight;
				if (framePixels <= MAX_DECODED_PIXELS
						&& canvasPixels <= ImageDecoding.MAX_PIXELS
						&& framePixels + canvasPixels * frameCount <= MAX_DECODED_PIXELS) {
					return source;
				}
			}
			source++;
		}
		// Ate no fator maximo nao cabe: ai sim recusa, com a mensagem antiga.
		throw new TooLargeToVerifyException("A animação excede o limite de resolução para verificação.");
	}

	void replay(CanvasVisitor visitor) throws IOException {
		BufferedImage canvas = new BufferedImage((canvasWidth + subsampling - 1) / subsampling,
			(canvasHeight + subsampling - 1) / subsampling, BufferedImage.TYPE_INT_ARGB);
		for (int index = 0; index < frames.size(); index++) {
			Frame frame = frames.get(index);
			javax.imageio.ImageReadParam params = reader.getDefaultReadParam();
			if (subsampling > 1) {
				params.setSourceSubsampling(subsampling, subsampling, 0, 0);
			}
			BufferedImage pixels = reader.read(index, params);
			if (pixels == null) {
				throw new IOException("Não foi possível decodificar a imagem.");
			}
			BufferedImage before = "restoreToPrevious".equals(frame.disposal()) ? copyOf(canvas) : null;
			Graphics2D graphics = canvas.createGraphics();
			try {
				graphics.drawImage(pixels, frame.left() / subsampling, frame.top() / subsampling, null);
			}
			finally {
				graphics.dispose();
			}
			visitor.visit(canvas);
			if ("restoreToBackgroundColor".equals(frame.disposal())) {
				Graphics2D clear = canvas.createGraphics();
				try {
					clear.setComposite(AlphaComposite.Clear);
					clear.fillRect(frame.left() / subsampling, frame.top() / subsampling,
						frame.width() / subsampling, frame.height() / subsampling);
				}
				finally {
					clear.dispose();
				}
			}
			else if (before != null) {
				canvas = before;
			}
		}
	}

	static BufferedImage copyOf(BufferedImage source) {
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
}
