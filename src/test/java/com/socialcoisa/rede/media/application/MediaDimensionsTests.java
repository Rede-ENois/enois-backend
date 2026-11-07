package com.socialcoisa.rede.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.media.application.port.out.MediaAssetRepository;
import com.socialcoisa.rede.media.application.port.out.MediaObjectStore;
import com.socialcoisa.rede.media.domain.MediaAsset;
import com.socialcoisa.rede.media.domain.MediaStorageProvider;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

/** Image width/height: measured on upload, backfilled for older assets, returned by GET /media/{id}. */
@SpringBootTest(properties = { "rede.media.storage=s3", "rede.media.s3.bucket=bucket-de-teste",
		"rede.media.s3.publicBaseUrl=https://enois.exemplo.com/api/storage" })
@AutoConfigureMockMvc
class MediaDimensionsTests {

	static {
		System.setProperty("aws.accessKeyId", "test");
		System.setProperty("aws.secretAccessKey", "test");
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private MediaAssetRepository assets;

	@Autowired
	private MediaDimensionsService dimensions;

	@Autowired
	private MediaObjectStore store;

	@TestConfiguration
	static class Store {

		@Bean
		@Primary
		MapStore mapStore() {
			return new MapStore();
		}
	}

	static class MapStore implements MediaObjectStore {

		final Map<String, byte[]> objects = new ConcurrentHashMap<>();

		@Override
		public StoredObject read(String key, String range) {
			byte[] data = objects.get(key);
			if (data == null) {
				throw new IllegalStateException("NoSuchKey");
			}
			return new StoredObject(new ByteArrayInputStream(data), data.length, "image/png", null, null);
		}

		@Override
		public void write(String key, String contentType, long length, InputStream body) {
			try {
				objects.put(key, body.readAllBytes());
			}
			catch (java.io.IOException failure) {
				throw new IllegalStateException(failure);
			}
		}
	}

	@Test
	void uploadedPngStoresItsSizeAndGetMediaReturnsIt() throws Exception {
		String mediaId = upload("media_dims_png", "image/png", image("png", 3, 2));

		mockMvc.perform(get("/media/" + mediaId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.width").value(3))
			.andExpect(jsonPath("$.height").value(2));
	}

	@Test
	void uploadedJpegRotatedByExifStoresTheDisplayedSize() throws Exception {
		// 4x2 pixels with EXIF orientation 6 (rotate 90): displayed, and stored after sanitizing, as 2x4.
		String mediaId = upload("media_dims_exif", "image/jpeg", withOrientation6(image("jpg", 4, 2)));

		assertThat(assets.findById(mediaId).orElseThrow())
			.extracting(MediaAsset::width, MediaAsset::height)
			.containsExactly(2, 4);
	}

	@Test
	void nonImageKeepsNullSize() throws Exception {
		String mediaId = upload("media_dims_pdf", "application/pdf", pdf());

		mockMvc.perform(get("/media/" + mediaId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.width").isEmpty())
			.andExpect(jsonPath("$.height").isEmpty());
	}

	@Test
	void backfillMeasuresStoredImagesAndGivesUpOnUnreadableOnes() throws Exception {
		MediaAsset readable = storedAsset("image/jpeg");
		MediaAsset unreadable = storedAsset("image/png");
		((MapStore) store).objects.put(readable.objectKey(), withOrientation6(image("jpg", 4, 2)));
		((MapStore) store).objects.put(unreadable.objectKey(), "not an image".getBytes());

		for (int round = 0; round < MediaDimensionsService.MAX_ATTEMPTS + 1; round++) {
			dimensions.backfill(100);
		}

		assertThat(assets.findById(readable.id()).orElseThrow())
			.extracting(MediaAsset::width, MediaAsset::height)
			.containsExactly(2, 4);
		MediaAsset failed = assets.findById(unreadable.id()).orElseThrow();
		assertThat(failed.width()).isNull();
		// Counted once per round and then no longer picked: the extra round did not add a fourth try.
		assertThat(failed.dimensionAttempts()).isEqualTo(MediaDimensionsService.MAX_ATTEMPTS);
	}

	private String upload(String user, String mime, byte[] data) throws Exception {
		var owner = new TestAuthHelper(mockMvc, objectMapper).authenticate(user);
		String created = mockMvc.perform(post("/media/uploads")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", user + "-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("fileName", "file", "mimeType", mime,
					"size", data.length, "purpose", "POST_IMAGE"))))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		JsonNode body = objectMapper.readTree(created);
		mockMvc.perform(put("/storage/" + body.path("objectKey").asText())
				.header("X-Session-Token", owner.token())
				.contentType(mime)
				.content(data))
			.andExpect(status().isOk());
		return body.path("id").asText();
	}

	/** An asset stored on S3 two hours ago, outside the upload window the backfill leaves alone. */
	private MediaAsset storedAsset(String mime) {
		String id = "media_dimsbackfill" + UUID.randomUUID().toString().replace("-", "");
		Instant createdAt = Instant.now().minus(2, ChronoUnit.HOURS);
		return assets.save(MediaAsset.pending(id, new UserId("user_dims_backfill"), "file", mime, 10, "POST_IMAGE",
			"uploads/" + id + "/file", "https://enois.exemplo.com/api/storage/uploads/" + id + "/file", createdAt,
			createdAt.plus(1, ChronoUnit.HOURS), MediaStorageProvider.S3));
	}

	private static byte[] image(String format, int width, int height) throws Exception {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, format, out);
		return out.toByteArray();
	}

	/** Inserts an APP1/EXIF block with Orientation = 6 right after the JPEG SOI marker. */
	private static byte[] withOrientation6(byte[] jpeg) {
		byte[] app1 = { (byte) 0xFF, (byte) 0xE1, 0, 34, 'E', 'x', 'i', 'f', 0, 0,
			'M', 'M', 0, 42, 0, 0, 0, 8,
			0, 1,
			0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, 6, 0, 0,
			0, 0, 0, 0 };
		byte[] out = new byte[jpeg.length + app1.length];
		System.arraycopy(jpeg, 0, out, 0, 2);
		System.arraycopy(app1, 0, out, 2, app1.length);
		System.arraycopy(jpeg, 2, out, 2 + app1.length, jpeg.length - 2);
		return out;
	}

	private static byte[] pdf() throws java.io.IOException {
		try (org.apache.pdfbox.pdmodel.PDDocument document = new org.apache.pdfbox.pdmodel.PDDocument()) {
			document.addPage(new org.apache.pdfbox.pdmodel.PDPage());
			java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
			document.save(out);
			return out.toByteArray();
		}
	}
}
