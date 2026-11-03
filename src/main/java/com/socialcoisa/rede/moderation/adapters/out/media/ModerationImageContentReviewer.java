package com.socialcoisa.rede.moderation.adapters.out.media;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Optional;

import javax.imageio.ImageIO;

import org.springframework.stereotype.Component;

import com.socialcoisa.rede.media.application.ImageDecoding;
import com.socialcoisa.rede.media.application.port.out.ImageContentReviewer;
import com.socialcoisa.rede.media.application.port.out.ImageContentReviewer.Result;
import com.socialcoisa.rede.moderation.ai.MediaSafetyAnalyzer;
import com.socialcoisa.rede.moderation.domain.MediaSafetyVerdict;

/** Adapts the existing semantic media review for the synchronous upload gate. */
@Component
class ModerationImageContentReviewer implements ImageContentReviewer {
	private final MediaSafetyAnalyzer analyzer;

	ModerationImageContentReviewer(MediaSafetyAnalyzer analyzer) {
		this.analyzer = analyzer;
	}

	@Override
	public Optional<Result> review(byte[] imageBytes) {
		byte[] reduced;
		try {
			reduced = reduce(imageBytes);
		}
		catch (IOException failure) {
			return Optional.empty();
		}
		return analyzer.analyze(reduced).map(verdict -> {
			if (verdict.verdict() == MediaSafetyVerdict.SAFE
					&& verdict.confidence() >= MediaSafetyAnalyzer.MIN_CONFIDENCE) {
				return Result.SAFE;
			}
			if (MediaSafetyAnalyzer.canActAlone(verdict) && verdict.verdict().isViolation()) {
				return Result.PROHIBITED;
			}
			return Result.UNSURE;
		});
	}

	private static byte[] reduce(byte[] imageBytes) throws IOException {
		BufferedImage source = ImageDecoding.read(new ByteArrayInputStream(imageBytes));
		if (source == null) {
			throw new IOException("Não foi possível decodificar a imagem para revisão.");
		}
		int maxSide = 768;
		double factor = Math.min(1.0, (double) maxSide / Math.max(source.getWidth(), source.getHeight()));
		int width = Math.max(1, (int) Math.round(source.getWidth() * factor));
		int height = Math.max(1, (int) Math.round(source.getHeight() * factor));
		BufferedImage reduced = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = reduced.createGraphics();
		try {
			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
				RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			graphics.drawImage(source, 0, 0, width, height, null);
		}
		finally {
			graphics.dispose();
		}
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		if (!ImageIO.write(reduced, "jpg", output)) {
			throw new IOException("Não foi possível preparar a imagem para revisão.");
		}
		return output.toByteArray();
	}
}
