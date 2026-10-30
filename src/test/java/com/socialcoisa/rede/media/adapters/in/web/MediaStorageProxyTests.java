package com.socialcoisa.rede.media.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.ObjectMapper;
import com.socialcoisa.rede.media.application.port.out.MediaObjectStore;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

/**
 * Proxy `/api/storage`: o navegador fala so com o nosso host.
 *
 * O gateway S3 do b7cloud nao responde CORS, entao a midia nao pode ser servida nem recebida
 * direto do bucket. Estes testes cobrem o caminho que substitui aquilo: leitura publica (com
 * Range, para o player) e escrita autorizada pelo dono do asset e pela chave exata.
 */
@SpringBootTest(properties = { "rede.media.storage=s3", "rede.media.s3.bucket=bucket-de-teste",
		"rede.media.s3.publicBaseUrl=https://enois.exemplo.com/api/storage" })
@AutoConfigureMockMvc
class MediaStorageProxyTests {

	static {
		System.setProperty("aws.accessKeyId", "test");
		System.setProperty("aws.secretAccessKey", "test");
	}

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

	@TestConfiguration
	static class FakeStore {

		@Bean
		@Primary
		FakeObjectStore fakeObjectStore() {
			return new FakeObjectStore();
		}
	}

	static class FakeObjectStore implements MediaObjectStore {

		byte[] conteudo = "conteudo-da-midia".getBytes(StandardCharsets.UTF_8);
		String tipo = "image/jpeg";
		String ultimoRange;
		String chaveEscrita;
		byte[] escrito;

		@Override
		public StoredObject read(String key, String range) {
			this.ultimoRange = range;
			boolean parcial = range != null;
			return new StoredObject(new ByteArrayInputStream(conteudo), conteudo.length, tipo,
				parcial ? "bytes 0-4/" + conteudo.length : null, "\"etag-teste\"");
		}

		@Override
		public void write(String key, String contentType, long length, InputStream body) {
			try {
				this.chaveEscrita = key;
				this.escrito = body.readAllBytes();
			}
			catch (java.io.IOException failure) {
				throw new IllegalStateException(failure);
			}
		}
	}

	private FakeObjectStore store() {
		return (FakeObjectStore) store;
	}

	/**
	 * O corpo da leitura é escrito na thread de streaming (`StreamingResponseBody`), então o
	 * `perform` pode voltar antes de o buffer estar completo — na suíte inteira isso aparecia
	 * como falha intermitente de corpo vazio. Esperar o buffer encher mantém a asserção exata.
	 */
	private void assertCorpoEmStreaming(MvcResult resultado, byte[] esperado) throws InterruptedException {
		long limite = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
		byte[] corpo;
		do {
			corpo = resultado.getResponse().getContentAsByteArray();
			if (corpo.length == esperado.length || System.nanoTime() > limite) {
				break;
			}
			Thread.sleep(10);
		}
		while (true);
		assertThat(corpo).isEqualTo(esperado);
	}

	@Test
	void leituraFuncionaSemSessaoEComCacheLongo() throws Exception {
		var resultado = mockMvc.perform(get("/storage/uploads/media_abc/media/x/foto.jpg"))
			.andExpect(status().isOk())
			.andExpect(header().string("Content-Type", "image/jpeg"))
			.andExpect(header().string("Accept-Ranges", "bytes"))
			.andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("immutable")))
			.andReturn();

		assertCorpoEmStreaming(resultado, store().conteudo);
	}

	@Test
	void allowedTypeIsServedInlineAndUnknownTypeAsDownload() throws Exception {
		mockMvc.perform(get("/storage/uploads/media_inline/file.jpg"))
			.andExpect(status().isOk())
			.andExpect(header().string("Content-Disposition", "inline"))
			.andExpect(header().string("X-Content-Type-Options", "nosniff"));

		store().tipo = "text/html";
		try {
			mockMvc.perform(get("/storage/uploads/media_legacy/page.html"))
				.andExpect(status().isOk())
				.andExpect(header().string("Content-Type", "application/octet-stream"))
				.andExpect(header().string("Content-Disposition", "attachment"));
		}
		finally {
			store().tipo = "image/jpeg";
		}
	}

	@Test
	void objectKeyIsGeneratedByTheServerAndIgnoresTheClientFileName() throws Exception {
		var dono = auth.authenticate("media_proxy_key");
		var criado = mockMvc.perform(post("/media/uploads")
				.header("X-Session-Token", dono.token())
				.header("Idempotency-Key", "media-proxy-key-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("fileName", "../../x/evil name.html",
					"mimeType", "image/PNG; charset=binary", "size", 1024, "purpose", "POST_IMAGE"))))
			.andExpect(status().isCreated())
			.andReturn();

		var body = objectMapper.readTree(criado.getResponse().getContentAsString());
		String objectKey = body.path("objectKey").asText();
		assertThat(objectKey).matches("uploads/media_[0-9a-f]+/file\\.png");
		assertThat(body.path("cdnUrl").asText()).doesNotContain("evil").doesNotContain("..");
		assertThat(body.path("uploadHeaders").path("Content-Type").asText()).isEqualTo("image/png");
	}

	@Test
	void uploadKindRestrictsTheAcceptedTypes() throws Exception {
		var dono = auth.authenticate("media_proxy_kind");
		mockMvc.perform(post("/media/uploads")
				.header("X-Session-Token", dono.token())
				.header("Idempotency-Key", "media-proxy-kind-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("fileName", "avatar.pdf",
					"mimeType", "application/pdf", "size", 1024, "purpose", "AVATAR"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("MEDIA_TYPE_NOT_ALLOWED"));

		mockMvc.perform(post("/media/uploads")
				.header("X-Session-Token", dono.token())
				.header("Idempotency-Key", "media-proxy-kind-2")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("fileName", "cv.png",
					"mimeType", "image/png", "size", 1024, "purpose", "RESUME"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("MEDIA_TYPE_NOT_ALLOWED"));
	}

	@Test
	void pedidoParcialDevolve206ComContentRange() throws Exception {
		mockMvc.perform(get("/storage/uploads/media_abc/media/x/video.mp4").header("Range", "bytes=0-4"))
			.andExpect(status().isPartialContent())
			.andExpect(header().string("Content-Range", org.hamcrest.Matchers.startsWith("bytes 0-4/")));

		assertThat(store().ultimoRange).isEqualTo("bytes=0-4");
	}

	@Test
	void chaveComTravessiaDeCaminhoERecusada() throws Exception {
		mockMvc.perform(get("/storage/uploads/../../etc/passwd")).andExpect(status().isBadRequest());
	}

	@Test
	void envioSemSessaoEhRecusado() throws Exception {
		mockMvc.perform(put("/storage/uploads/media_abc/media/x/foto.jpg")
				.contentType(MediaType.IMAGE_JPEG)
				.content("bytes".getBytes(StandardCharsets.UTF_8)))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void donoEnviaParaAChaveDoProprioAssetEOutraPessoaNao() throws Exception {
		var dono = auth.authenticate("media_proxy_dono");
		var outro = auth.authenticate("media_proxy_outro");

		var criado = mockMvc.perform(post("/media/uploads")
				.header("X-Session-Token", dono.token())
				.header("Idempotency-Key", "media-proxy-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("fileName", "media/xyz/foto.jpg",
					"mimeType", "image/jpeg", "size", 1024, "purpose", "POST_IMAGE"))))
			.andExpect(status().isCreated())
			.andReturn();

		String objectKey = objectMapper.readTree(criado.getResponse().getContentAsString())
			.path("objectKey").asText();
		assertThat(objectKey).startsWith("uploads/media_");

		mockMvc.perform(put("/storage/" + objectKey)
				.header("X-Session-Token", outro.token())
				.contentType(MediaType.IMAGE_JPEG)
				.content("intruso".getBytes(StandardCharsets.UTF_8)))
			.andExpect(status().isForbidden());

		mockMvc.perform(put("/storage/" + objectKey)
				.header("X-Session-Token", dono.token())
				.contentType(MediaType.IMAGE_JPEG)
				.content("foto".getBytes(StandardCharsets.UTF_8)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("STORED"));

		assertThat(store().chaveEscrita).isEqualTo(objectKey);
		assertThat(store().escrito).isEqualTo("foto".getBytes(StandardCharsets.UTF_8));
	}
}
