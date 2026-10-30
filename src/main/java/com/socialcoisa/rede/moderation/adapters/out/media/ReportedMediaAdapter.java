package com.socialcoisa.rede.moderation.adapters.out.media;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.socialcoisa.rede.feed.application.port.out.CommentRepository;
import com.socialcoisa.rede.feed.application.port.out.PostRepository;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.media.application.port.out.MediaAssetRepository;
import com.socialcoisa.rede.media.application.port.out.MediaSourceDownloader;
import com.socialcoisa.rede.media.domain.MediaAsset;
import com.socialcoisa.rede.moderation.application.port.out.ReportedMediaReader;

/**
 * Lê as imagens do alvo denunciado para a triagem.
 *
 * <p>Dois cuidados que não são detalhe:</p>
 * <ul>
 * <li><b>Reduz antes de mandar.</b> O modelo não precisa de 4000px para dizer que há nudez, e um
 * original de 8MB viraria uma requisição caríssima por denúncia. A redução também é o que descarta
 * metadado: o que vai ao modelo é um JPEG novo, sem EXIF.</li>
 * <li><b>Nunca lança.</b> Denúncia de conteúdo explícito é justamente a que não pode morrer por
 * causa de um download lento. Falha em uma imagem não impede as outras.</li>
 * </ul>
 *
 * <p>Vídeo e áudio ficam de fora: extrair quadro exigiria ffmpeg no caminho da triagem, e a
 * varredura de mídia (MediaSafetySweepWorker) cobre o caso com revisão humana.</p>
 */
@Component
class ReportedMediaAdapter implements ReportedMediaReader {

	private static final Logger LOG = LoggerFactory.getLogger(ReportedMediaAdapter.class);

	/** Lado maior da imagem enviada ao modelo. Acima disso é detalhe que não muda o veredito. */
	private static final int MAX_SIDE = 768;

	private static final Set<String> IMAGE_TYPES = Set.of(
		"image/jpeg", "image/png", "image/gif", "image/webp", "image/avif");

	private final PostRepository posts;
	private final CommentRepository comments;
	private final MediaAssetRepository assets;
	/**
	 * O downloader só existe com a migração ligada (ver MediaMigrationConfig). Sem ele a triagem
	 * não olha imagem e a denúncia segue para revisão humana — o comportamento antigo, não um erro.
	 */
	private final ObjectProvider<MediaSourceDownloader> downloader;

	ReportedMediaAdapter(PostRepository posts, CommentRepository comments, MediaAssetRepository assets,
			ObjectProvider<MediaSourceDownloader> downloader) {
		this.posts = posts;
		this.comments = comments;
		this.assets = assets;
		this.downloader = downloader;
	}

	@Override
	public List<byte[]> imagesOf(String targetType, String targetId, int limit) {
		if (limit <= 0 || targetId == null) {
			return List.of();
		}
		MediaSourceDownloader source = downloader.getIfAvailable();
		if (source == null) {
			return List.of();
		}
		List<String> mediaIds = mediaIdsOf(targetType, targetId);
		if (mediaIds.isEmpty()) {
			return List.of();
		}
		List<byte[]> images = new ArrayList<>();
		for (MediaAsset asset : assets.findByIds(mediaIds)) {
			if (images.size() >= limit) {
				break;
			}
			if (!IMAGE_TYPES.contains(asset.mimeType())) {
				continue;
			}
			byte[] image = shrink(source, asset);
			if (image != null) {
				images.add(image);
			}
		}
		return images;
	}

	private List<String> mediaIdsOf(String targetType, String targetId) {
		return switch (targetType == null ? "" : targetType) {
			// A varredura de mídia não parte de um post: o alvo JÁ é a mídia.
			case "MEDIA" -> List.of(targetId);
			case "POST" -> posts.findById(new PostId(targetId)).map(post -> post.mediaIds()).orElse(List.of());
			case "COMMENT" -> comments.findById(targetId).map(comment -> comment.mediaIds()).orElse(List.of());
			default -> List.of();
		};
	}

	/** Baixa, decodifica e reduz para JPEG. Qualquer falha vira null (a imagem é ignorada). */
	private byte[] shrink(MediaSourceDownloader source, MediaAsset asset) {
		Path temp = null;
		try {
			temp = source.download(asset.cdnUrl());
			BufferedImage original = ImageIO.read(temp.toFile());
			if (original == null) {
				return null;
			}
			BufferedImage reduced = scale(original);
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			if (!ImageIO.write(reduced, "jpg", out)) {
				return null;
			}
			return out.toByteArray();
		}
		catch (Exception failure) {
			LOG.debug("midia_denuncia_nao_lida mediaId={} motivo={}", asset.id(),
				failure.getClass().getSimpleName());
			return null;
		}
		finally {
			if (temp != null) {
				try {
					Files.deleteIfExists(temp);
				}
				catch (Exception ignored) {
				}
			}
		}
	}

	private BufferedImage scale(BufferedImage original) {
		int width = original.getWidth();
		int height = original.getHeight();
		double factor = Math.min(1.0, (double) MAX_SIDE / Math.max(width, height));
		int targetWidth = Math.max(1, (int) Math.round(width * factor));
		int targetHeight = Math.max(1, (int) Math.round(height * factor));
		// JPEG não tem canal alfa: desenhar sobre um RGB evita o fundo preto de PNG transparente.
		BufferedImage target = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = target.createGraphics();
		try {
			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
				RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			graphics.drawImage(original, 0, 0, targetWidth, targetHeight, null);
		}
		finally {
			graphics.dispose();
		}
		return target;
	}
}
