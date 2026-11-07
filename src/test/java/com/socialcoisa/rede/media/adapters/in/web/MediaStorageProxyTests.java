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
import com.socialcoisa.rede.media.application.StubFfmpegRunner;
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

	@Autowired
	private com.socialcoisa.rede.media.application.MediaMetadataSanitizer sanitizer;

	@Autowired
	private StubFfmpegRunner ffmpeg;

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

		@Bean
		@Primary
		StubFfmpegRunner stubFfmpegRunner() {
			return new StubFfmpegRunner();
		}
	}

	static class FakeObjectStore implements MediaObjectStore {

		byte[] conteudo = "conteudo-da-midia".getBytes(StandardCharsets.UTF_8);
		String tipo = "image/jpeg";
		String ultimoRange;
		String chaveEscrita;
		byte[] escrito;
		/** Quando preenchido, a escrita falha como um gateway S3 fora do ar. */
		RuntimeException falhaNaEscrita;

		@Override
		public StoredObject read(String key, String range) {
			this.ultimoRange = range;
			boolean parcial = range != null;
			return new StoredObject(new ByteArrayInputStream(conteudo), conteudo.length, tipo,
				parcial ? "bytes 0-4/" + conteudo.length : null, "\"etag-teste\"");
		}

		@Override
		public void write(String key, String contentType, long length, InputStream body) {
			if (falhaNaEscrita != null) {
				throw falhaNaEscrita;
			}
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

		byte[] foto = "foto".repeat(256).getBytes(StandardCharsets.UTF_8);
		mockMvc.perform(put("/storage/" + objectKey)
				.header("X-Session-Token", dono.token())
				.contentType(MediaType.IMAGE_JPEG)
				.content(foto))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("STORED"));

		assertThat(store().chaveEscrita).isEqualTo(objectKey);
		assertThat(store().escrito).isEqualTo(foto);
	}

	@Test
	void donoEnviaVideoMp4PeloProxyEOArquivoChegaAoBucket() throws Exception {
		var dono = auth.authenticate("media_proxy_video");
		// O tamanho declarado no upload e o contrato: o PUT precisa mandar exatamente esse tanto.
		byte[] video = "ftypisom-corpo-de-video-de-teste".repeat(64).getBytes(StandardCharsets.UTF_8);
		var criado = mockMvc.perform(post("/media/uploads")
				.header("X-Session-Token", dono.token())
				.header("Idempotency-Key", "media-proxy-video-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("fileName", "clipe.mp4",
					"mimeType", "video/mp4", "size", video.length, "purpose", "POST_VIDEO"))))
			.andExpect(status().isCreated())
			.andReturn();
		String objectKey = objectMapper.readTree(criado.getResponse().getContentAsString())
			.path("objectKey").asText();
		assertThat(objectKey).endsWith("/file.mp4");

		mockMvc.perform(put("/storage/" + objectKey)
				.header("X-Session-Token", dono.token())
				.contentType("video/mp4")
				.content(video))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("STORED"));

		assertThat(store().chaveEscrita).isEqualTo(objectKey);
		assertThat(store().escrito).isEqualTo(video);
	}

	@Test
	void videoQueOFfmpegNaoLimpaERecusadoENaoChegaAoBucket() throws Exception {
		var dono = auth.authenticate("media_proxy_video_sujo");
		byte[] video = "ftypisom-video-com-metadados".repeat(16).getBytes(StandardCharsets.UTF_8);
		var criado = mockMvc.perform(post("/media/uploads")
				.header("X-Session-Token", dono.token())
				.header("Idempotency-Key", "media-proxy-video-sujo-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("fileName", "clipe.mp4",
					"mimeType", "video/mp4", "size", video.length, "purpose", "POST_VIDEO"))))
			.andExpect(status().isCreated())
			.andReturn();
		String objectKey = objectMapper.readTree(criado.getResponse().getContentAsString())
			.path("objectKey").asText();

		ffmpeg.failWith(StubFfmpegRunner.processingFailed());
		try {
			mockMvc.perform(put("/storage/" + objectKey)
					.header("X-Session-Token", dono.token())
					.contentType("video/mp4")
					.content(video))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("MEDIA_PROCESSING_FAILED"));
		}
		finally {
			ffmpeg.failWith(null);
		}
		assertThat(store().chaveEscrita).isNotEqualTo(objectKey);
	}

	@Test
	void falhaDoBucketNaEscritaVira502ComCodigoProprioEnaoErroGenerico() throws Exception {
		var dono = auth.authenticate("media_proxy_bucket_fora");
		byte[] video = "ftypisom-video".repeat(32).getBytes(StandardCharsets.UTF_8);
		var criado = mockMvc.perform(post("/media/uploads")
				.header("X-Session-Token", dono.token())
				.header("Idempotency-Key", "media-proxy-bucket-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("fileName", "clipe.mp4",
					"mimeType", "video/mp4", "size", video.length, "purpose", "POST_VIDEO"))))
			.andExpect(status().isCreated())
			.andReturn();
		String objectKey = objectMapper.readTree(criado.getResponse().getContentAsString())
			.path("objectKey").asText();

		store().falhaNaEscrita = new IllegalStateException("gateway recusou a gravacao");
		try {
			mockMvc.perform(put("/storage/" + objectKey)
					.header("X-Session-Token", dono.token())
					.contentType("video/mp4")
					.content(video))
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.code").value("MEDIA_STORAGE_WRITE_FAILED"))
				.andExpect(jsonPath("$.message").value(
					org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("gateway"))));
		}
		finally {
			store().falhaNaEscrita = null;
		}
	}

	@Test
	void videoGrandeAcimaDoLimiteEmMemoriaTambemChegaAoBucket() throws Exception {
		// Acima de MAX_IN_MEMORY o proxy usa arquivo temporario + ffmpeg (sanitizeToFile).
		byte[] video = new byte[41 * 1024 * 1024];
		String objectKey = assetDeVideoGrande("media_proxy_video_grande", "media-proxy-video-2", video.length);

		mockMvc.perform(put("/storage/" + objectKey)
				.header("X-Session-Token", auth.authenticate("media_proxy_video_grande").token())
				.contentType("video/mp4")
				.content(video))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("STORED"));

		assertThat(store().chaveEscrita).isEqualTo(objectKey);
		assertThat(store().escrito).hasSize(video.length);
	}

	/**
	 * Arquivo do Enois num campo de imagem: a checagem de conteúdo recusa `video/mp4` com erro do
	 * classificador e virava 500. Quem tentava trocar a foto por um vídeo lia "erro interno" em
	 * vez do motivo — a resposta agora é 422 dizendo que o arquivo não pôde ser verificado.
	 *
	 * <p>No perfil de teste a checagem está desligada e não há classificador carregado, então o
	 * teste roda num contexto com `rede.media.nsfw.enabled=true` e um classificador que se recusa a
	 * ler o arquivo, que é o que o ONNX faz com bytes de vídeo.</p>
	 */
	/** Cadastra a mídia de vídeo grande e devolve a chave, sem repetir o mesmo POST em cada teste. */
	private String assetDeVideoGrande(String dono, String idempotencia, int tamanho) throws Exception {
		var criado = mockMvc.perform(post("/media/uploads")
				.header("X-Session-Token", auth.authenticate(dono).token())
				.header("Idempotency-Key", idempotencia)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("fileName", "aula.mp4",
					"mimeType", "video/mp4", "size", (long) tamanho, "purpose", "POST_VIDEO"))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(criado.getResponse().getContentAsString()).path("objectKey").asText();
	}

	/**
	 * A captura de denúncia é a prova da violação: a checagem de imagem pública recusaria o
	 * arquivo, e ela não pode ser publicada. Fica guardada e só quem modera consegue abrir.
	 */
	@Test
	void capturaDeDenunciaNaoPassaPelaChecagemPublicaESoModeracaoLe() throws Exception {
		var dono = auth.authenticate("media_proxy_captura");
		var outro = auth.authenticate("media_proxy_captura_outro");
		var admin = auth.authenticate("group-admin");
		byte[] captura = "captura-da-tela-da-chamada".repeat(64).getBytes(StandardCharsets.UTF_8);

		var criado = mockMvc.perform(post("/media/uploads")
				.header("X-Session-Token", dono.token())
				.header("Idempotency-Key", "media-proxy-captura-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("fileName", "captura.jpg",
					"mimeType", "image/jpeg", "size", captura.length, "purpose", "REPORT_CAPTURE"))))
			.andExpect(status().isCreated())
			.andReturn();
		String objectKey = objectMapper.readTree(criado.getResponse().getContentAsString())
			.path("objectKey").asText();

		mockMvc.perform(put("/storage/" + objectKey)
				.header("X-Session-Token", dono.token())
				.contentType(MediaType.IMAGE_JPEG)
				.content(captura))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("STORED"));

		// Sem sessão e com sessão comum: a prova não é servida.
		mockMvc.perform(get("/storage/" + objectKey)).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/storage/" + objectKey).header("X-Session-Token", outro.token()))
			.andExpect(status().isForbidden());

		var leitura = mockMvc.perform(get("/storage/" + objectKey).header("X-Session-Token", admin.token()))
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
			.andReturn();
		// O bucket falso devolve o conteúdo fixo dele; o que importa aqui é que a leitura passou.
		assertCorpoEmStreaming(leitura, store().conteudo);
	}

	@Test
	void capturaDeDenunciaSoAceitaImagem() throws Exception {
		var dono = auth.authenticate("media_proxy_captura_tipo");
		mockMvc.perform(post("/media/uploads")
				.header("X-Session-Token", dono.token())
				.header("Idempotency-Key", "media-proxy-captura-2")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("fileName", "captura.mp4",
					"mimeType", "video/mp4", "size", 1024, "purpose", "REPORT_CAPTURE"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("MEDIA_TYPE_NOT_ALLOWED"));
	}
}

/**
 * O caminho de vídeo grande guarda o conteúdo em arquivo temporário antes de mandar para o bucket.
 * Quando o servidor não tem nenhuma pasta gravável (o `/tmp` da imagem recusando, o volume de dados
 * fora do ar), o envio não pode virar "erro interno inesperado": sai 503 com código próprio e o log
 * mostra quais pastas foram tentadas.
 *
 * <p>A pasta é resolvida no boot, então o `MockMvc` da classe irmã não enxerga a troca — este
 * contexto carrega o mesmo banco em memória e substitui só o resolvedor do temporário.</p>
 */
@SpringBootTest(properties = { "rede.media.storage=s3", "rede.media.s3.bucket=bucket-de-teste",
		"rede.media.s3.publicBaseUrl=https://enois.exemplo.com/api/storage" })
@AutoConfigureMockMvc
class MediaStorageProxySemTemporarioTests {

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

	@Test
	void videoGrandeSemTemporarioGravavelResponde503Proprio() throws Exception {
		var auth = new TestAuthHelper(mockMvc, objectMapper);
		byte[] video = new byte[41 * 1024 * 1024];
		String token = auth.authenticate("media_proxy_video_sem_tmp").token();
		var criado = mockMvc.perform(post("/media/uploads")
				.header("X-Session-Token", token)
				.header("Idempotency-Key", "media-proxy-video-3")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("fileName", "aula.mp4",
					"mimeType", "video/mp4", "size", (long) video.length, "purpose", "POST_VIDEO"))))
			.andExpect(status().isCreated())
			.andReturn();
		String objectKey = objectMapper.readTree(criado.getResponse().getContentAsString())
			.path("objectKey").asText();

		// O contexto deste teste injeta um resolvedor sem nenhuma pasta gravável (ver `SemTemporario`),
		// então nada aqui mexe em `java.io.tmpdir`: a decisão entra pelo bean, não pelo processo.
		mockMvc.perform(put("/storage/" + objectKey)
				.header("X-Session-Token", token)
				.contentType("video/mp4")
				.content(video))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("MEDIA_UPLOAD_UNAVAILABLE"))
			.andExpect(jsonPath("$.message").value(
				org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("/dev/null"))));
		assertThat(((MediaStorageProxyTests.FakeObjectStore) store).chaveEscrita).isNotEqualTo(objectKey);
	}

	@TestConfiguration
	static class SemTemporario {

		@Bean
		@Primary
		MediaStorageProxyTests.FakeObjectStore fakeObjectStore() {
			return new MediaStorageProxyTests.FakeObjectStore();
		}

		@Bean
		@Primary
		com.socialcoisa.rede.media.application.MediaTemporaryFiles semPastaGravavel() {
			// Nenhuma das três pastas existe ou aceita arquivo: o tmp do processo é um arquivo comum
			// (não `/dev/null`, onde escrever funciona e o teste passaria pelo motivo errado).
			return new com.socialcoisa.rede.media.application.MediaTemporaryFiles("/dev/null/nao-e-pasta",
				"/dev/null/nao-e-dados", "/dev/null/nao-e-tmp", null);
		}
	}
}
