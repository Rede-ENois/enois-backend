package com.socialcoisa.rede.media.application;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import com.socialcoisa.rede.media.application.port.out.MediaAssetRepository;
import com.socialcoisa.rede.media.application.port.out.MediaObjectStore;
import com.socialcoisa.rede.media.domain.MediaAsset;

/**
 * Pixel size of image assets, so a post card reserves its final height before the image loads.
 *
 * <p>New uploads are measured from the sanitized bytes the proxy already holds; assets stored before
 * that are measured by the backfill ({@link #backfill(int)}). Measuring never fails an upload: an
 * unreadable header keeps the size null.</p>
 */
@Service
public class MediaDimensionsService {

	private static final Logger LOG = LoggerFactory.getLogger(MediaDimensionsService.class);

	/** Failed reads before the backfill gives up on an asset. */
	static final int MAX_ATTEMPTS = 3;
	/**
	 * Only the head of the object is fetched: every supported format keeps its size near the start
	 * (JPEG SOF after at most a 64KB EXIF block, PNG/GIF/WebP in the first bytes).
	 */
	static final int HEAD_BYTES = 1024 * 1024;
	/** Same window MediaService gives an upload: younger assets may simply not be stored yet. */
	private static final Duration UPLOAD_WINDOW = Duration.ofMinutes(60);

	private final MediaAssetRepository assets;
	/** Absent when storage is ImageKit; the backfill then has nothing to read. */
	private final ObjectProvider<MediaObjectStore> storeProvider;
	private final Clock clock;

	public MediaDimensionsService(MediaAssetRepository assets, ObjectProvider<MediaObjectStore> storeProvider,
			Clock clock) {
		this.assets = assets;
		this.storeProvider = storeProvider;
		this.clock = clock;
	}

	/** Measures freshly uploaded bytes (already sanitized, so EXIF rotation is baked in). */
	public void recordUploaded(MediaAsset asset, byte[] stored) {
		if (!isImage(asset)) {
			return;
		}
		ImageDecoding.Dimensions dimensions = measure(asset, stored);
		if (dimensions != null) {
			assets.recordDimensions(asset.id(), dimensions.width(), dimensions.height());
		}
	}

	/** One backfill round; returns how many assets got their size. */
	public int backfill(int batchSize) {
		MediaObjectStore store = storeProvider.getIfAvailable();
		if (store == null) {
			return 0;
		}
		int filled = 0;
		for (MediaAsset asset : assets.findForDimensionBackfill(batchSize, MAX_ATTEMPTS,
				clock.instant().minus(UPLOAD_WINDOW))) {
			ImageDecoding.Dimensions dimensions = measure(asset, head(store, asset));
			if (dimensions == null) {
				assets.recordDimensionFailure(asset.id());
				continue;
			}
			assets.recordDimensions(asset.id(), dimensions.width(), dimensions.height());
			filled++;
		}
		return filled;
	}

	private static byte[] head(MediaObjectStore store, MediaAsset asset) {
		try (InputStream body = store.read(asset.objectKey(), "bytes=0-" + (HEAD_BYTES - 1)).body()) {
			return body.readNBytes(HEAD_BYTES);
		}
		catch (IOException | RuntimeException failure) {
			LOG.warn("media_dimensions_read_failed mediaId={} error={}", asset.id(), failure.getClass().getSimpleName());
			return null;
		}
	}

	private static ImageDecoding.Dimensions measure(MediaAsset asset, byte[] data) {
		if (data == null || data.length == 0) {
			return null;
		}
		try {
			ImageDecoding.Dimensions dimensions = ImageDecoding.displayDimensions(data);
			if (dimensions == null || dimensions.width() <= 0 || dimensions.height() <= 0) {
				LOG.debug("media_dimensions_unknown mediaId={} mime={}", asset.id(), asset.mimeType());
				return null;
			}
			return dimensions;
		}
		catch (IOException | RuntimeException failure) {
			LOG.warn("media_dimensions_unreadable mediaId={} mime={} error={}", asset.id(), asset.mimeType(),
				failure.getClass().getSimpleName());
			return null;
		}
	}

	private static boolean isImage(MediaAsset asset) {
		return asset.mimeType() != null && asset.mimeType().startsWith("image/");
	}
}
