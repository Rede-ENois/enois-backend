package com.socialcoisa.rede.media.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.socialcoisa.rede.media.application.port.out.MediaObjectStore;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

/**
 * Storage local de dev: o upload existe sem bucket e continua passando pelo MESMO proxy
 * `/storage` — sanitização de metadados e moderação não são puladas, só mudam de destino.
 */
@SpringBootTest(properties = { "rede.media.storage=local",
		"rede.media.local.dir=${java.io.tmpdir}/rede-media-test-${random.uuid}",
		"rede.media.local.api-base=/api" })
@AutoConfigureMockMvc
class LocalMediaStorageTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private MediaObjectStore store;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	@Test
	void uploadIsSignedToTheLocalProxyAndReadBackSanitized() throws Exception {
		// PNG com um chunk tEXt antes do IEND: a limpeza de metadados deve sumir com ele na escrita.
		byte[] png = withTextChunk(image("png", 3, 2));
		JsonNode created = createUpload("media_local_read", "image/png", png, "POST_IMAGE");
		String objectKey = created.path("objectKey").asText();

		assertThat(created.path("provider").asText()).isEqualTo("S3");
		assertThat(created.path("uploadUrl").asText()).isEqualTo("/api/storage/" + objectKey);
		assertThat(created.path("cdnUrl").asText()).isEqualTo("/api/storage/" + objectKey);
		assertThat(created.path("uploadHeaders").path("Content-Type").asText()).isEqualTo("image/png");

		sendToProxy("media_local_read", created, png, "image/png");

		byte[] stored = readDirectly(objectKey);
		// O caminho de escrita passa pelo sanitizador mesmo sem bucket: o metadado não fica no
		// arquivo servido (é isso que garante EXIF/GPS fora do storage também no modo local).
		assertThat(new String(png, StandardCharsets.ISO_8859_1)).contains("tEXt").contains("segredo");
		assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("tEXt")
			.doesNotContain("segredo");
		assertThat(stored.length).isGreaterThan(8);
		assertThat(stored[0] & 0xFF).isEqualTo(0x89);
		assertThat(new String(stored, 1, 3, StandardCharsets.US_ASCII)).isEqualTo("PNG");

		MvcResult read = mockMvc.perform(get("/storage/" + objectKey))
			.andExpect(status().isOk())
			.andExpect(header().string("Content-Type", "image/png"))
			.andExpect(header().string("Accept-Ranges", "bytes"))
			.andReturn();
		assertBody(read, stored);
	}

	@Test
	void rangeReadReturnsTheRequestedSlice() throws Exception {
		byte[] png = image("png", 4, 4);
		JsonNode created = createUpload("media_local_range", "image/png", png, "POST_IMAGE");
		String objectKey = created.path("objectKey").asText();
		sendToProxy("media_local_range", created, png, "image/png");
		byte[] stored = readDirectly(objectKey);

		MvcResult read = mockMvc.perform(get("/storage/" + objectKey).header("Range", "bytes=0-3"))
			.andExpect(status().isPartialContent())
			.andExpect(header().string("Content-Range", "bytes 0-3/" + stored.length))
			.andReturn();

		assertBody(read, java.util.Arrays.copyOf(stored, 4));
	}

	@Test
	void writeStillRequiresTheOwnerSession() throws Exception {
		byte[] png = image("png", 2, 2);
		JsonNode created = createUpload("media_local_owner", "image/png", png, "POST_IMAGE");

		mockMvc.perform(put("/storage/" + created.path("objectKey").asText())
				.contentType("image/png")
				.content(png))
			.andExpect(status().isUnauthorized());
	}

	private JsonNode createUpload(String user, String mime, byte[] data, String purpose) throws Exception {
		var owner = auth.authenticate(user);
		String created = mockMvc.perform(post("/media/uploads")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "media-local-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("fileName", "foto.png", "mimeType", mime,
					"size", data.length, "purpose", purpose))))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		return objectMapper.readTree(created);
	}

	private void sendToProxy(String user, JsonNode created, byte[] data, String mime) throws Exception {
		var owner = auth.authenticate(user);
		mockMvc.perform(put("/storage/" + created.path("objectKey").asText())
				.header("X-Session-Token", owner.token())
				.contentType(mime)
				.content(data))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("STORED"));
	}

	private byte[] readDirectly(String objectKey) throws Exception {
		try (var object = store.read(objectKey, null).body()) {
			return object.readAllBytes();
		}
	}

	/**
	 * O `StreamingResponseBody` escreve na thread de streaming; o corpo pode não estar completo
	 * quando o `perform` volta (mesmo cuidado de `MediaStorageProxyTests`).
	 */
	private static void assertBody(MvcResult result, byte[] expected) throws InterruptedException {
		long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		byte[] body;
		do {
			body = result.getResponse().getContentAsByteArray();
			if (body.length == expected.length || System.nanoTime() > limit) {
				break;
			}
			Thread.sleep(10);
		}
		while (true);
		assertThat(body).isEqualTo(expected);
	}

	private static byte[] image(String format, int width, int height) throws Exception {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, format, out);
		return out.toByteArray();
	}

	/** Insere um chunk tEXt válido antes do IEND (mesmo CRC) para provar que ele some. */
	private static byte[] withTextChunk(byte[] png) throws Exception {
		int iend = indexOf(png, "IEND".getBytes(StandardCharsets.US_ASCII)) - 4;
		byte[] type = "tEXt".getBytes(StandardCharsets.US_ASCII);
		byte[] data = "Comment\u0000segredo".getBytes(StandardCharsets.ISO_8859_1);
		ByteArrayOutputStream chunk = new ByteArrayOutputStream();
		chunk.write((data.length >>> 24) & 0xFF);
		chunk.write((data.length >>> 16) & 0xFF);
		chunk.write((data.length >>> 8) & 0xFF);
		chunk.write(data.length & 0xFF);
		chunk.writeBytes(type);
		chunk.writeBytes(data);
		java.util.zip.CRC32 crc = new java.util.zip.CRC32();
		crc.update(type);
		crc.update(data);
		long value = crc.getValue();
		chunk.write((int) (value >>> 24) & 0xFF);
		chunk.write((int) (value >>> 16) & 0xFF);
		chunk.write((int) (value >>> 8) & 0xFF);
		chunk.write((int) value & 0xFF);

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(png, 0, iend);
		chunk.writeTo(out);
		out.write(png, iend, png.length - iend);
		return out.toByteArray();
	}

	private static int indexOf(byte[] data, byte[] needle) {
		outer:
		for (int i = 0; i <= data.length - needle.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (data[i + j] != needle[j]) {
					continue outer;
				}
			}
			return i;
		}
		throw new IllegalArgumentException("Marcador nao encontrado no PNG.");
	}
}
