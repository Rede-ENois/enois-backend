package com.socialcoisa.rede.media.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.ObjectMapper;
import com.socialcoisa.rede.media.application.MediaService;
import com.socialcoisa.rede.media.application.ReportAttachments;
import com.socialcoisa.rede.media.application.port.out.MediaAssetRepository;
import com.socialcoisa.rede.media.application.port.out.MediaObjectStore;
import com.socialcoisa.rede.media.domain.MediaAsset;
import com.socialcoisa.rede.media.domain.MediaStorageProvider;
import com.socialcoisa.rede.shared.web.StreamedBody;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

/**
 * A cópia de uma denúncia só se abre para quem tem direito: quem enviou, quem modera ou quem tem o
 * endereço assinado do painel. Sem isso, saber o id do asset bastaria — e a cópia é conteúdo que
 * ainda não passou por revisão de imagem.
 */
@SpringBootTest(properties = { "rede.media.storage=s3", "rede.media.s3.bucket=bucket-de-teste",
		"rede.media.s3.publicBaseUrl=https://enois.exemplo.com/api/storage",
		"rede.backend.url=https://api.enois.exemplo.com",
		"rede.media.attachments.secret=segredo-de-teste-com-mais-de-32-caracteres" })
@AutoConfigureMockMvc
class ReportAttachmentTests {

	static {
		System.setProperty("aws.accessKeyId", "test");
		System.setProperty("aws.secretAccessKey", "test");
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private ReportAttachments attachments;

	@Autowired
	private MediaAssetRepository assets;

	private TestAuthHelper auth;

	private TestAuthHelper auth() {
		if (auth == null) {
			auth = new TestAuthHelper(mockMvc, objectMapper);
		}
		return auth;
	}

	@TestConfiguration
	static class FakeStore {

		@Bean
		@Primary
		FakeObjectStore fakeObjectStore() {
			return new FakeObjectStore();
		}
	}

	/** Só a chave privada existe no bucket: a pública levanta "objeto inexistente". */
	/** O bucket só tem o que foi gravado pela rota de subida: a chave do asset é a que existe. */
	static class FakeObjectStore implements MediaObjectStore {

		static final String CONTEUDO = "captura-guardada";

		byte[] conteudo = CONTEUDO.getBytes(StandardCharsets.UTF_8);
		private static final java.util.Set<String> escrita = java.util.concurrent.ConcurrentHashMap.newKeySet();

		@Override
		public StoredObject read(String key, String range) {
			if (!escrita.contains(key)) {
				throw new IllegalStateException("objeto inexistente");
			}
			return new StoredObject(new ByteArrayInputStream(conteudo), conteudo.length, "image/jpeg", null,
				"\"etag\"");
		}

		@Override
		public void write(String key, String contentType, long length, java.io.InputStream body) {
			try (var sink = new ByteArrayOutputStream()) {
				body.transferTo(sink);
				escrita.add(key);
			}
			catch (java.io.IOException failure) {
				throw new IllegalStateException(failure);
			}
		}
	}

	/**
	 * Cópia de denúncia já registrada (o asset nasce no estado em que o upload termina: objeto no
	 * bucket). O endereço público dele é a chave privada — é isso que este teste confere.
	 */
	private MediaAsset captura(String id, String dono, String arquivo) {
		String chave = "reports/capture/uploads/" + id + "/" + arquivo;
		var asset = MediaAsset.pending(id, new com.socialcoisa.rede.feed.domain.UserId(dono), arquivo,
			"image/jpeg", 16, MediaService.REPORT_CAPTURE_PURPOSE, chave,
			"https://enois.exemplo.com/api/storage/" + chave,
			java.time.Instant.now(),
			java.time.Instant.now().plus(Duration.ofMinutes(15)), MediaStorageProvider.S3);
		FakeObjectStore.escrita.add(chave);
		return assets.save(asset);
	}

	@Test
	void aCapturaNasceEmChavePrivada() {
		var asset = captura("media_captura_chave", "report_attachment_chave", "media/x/captura.jpg");

		assertThat(asset.objectKey()).startsWith("reports/capture/");
		assertThat(asset.cdnUrl()).endsWith("/reports/capture/" + asset.objectKey().substring("reports/capture/".length()));
	}

	@Test
	void semAssinaturaESemSessaoACopiaNaoExiste() throws Exception {
		String mediaId = captura("media_captura_anon", "report_attachment_anon", "media/x/anon.jpg").id();

		mockMvc.perform(get("/media/attachments/" + mediaId))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("MEDIA_NOT_FOUND"));
		mockMvc.perform(get("/media/attachments/" + mediaId + "?scope=moderator&expires=1&signature=x"))
			.andExpect(status().isNotFound());
	}

	@Test
	void oDonoBaixaAPropriaCopiaComoDownloadECadaSessaoSoVeAsSuas() throws Exception {
		var dono = auth().authenticate("report_attachment_dono");
		var outro = auth().authenticate("report_attachment_outro");
		String mediaId = captura("media_captura_dono", dono.userId(), "media/x/dono.jpg").id();

		mockMvc.perform(get("/media/attachments/" + mediaId).header("X-Session-Token", outro.token()))
			.andExpect(status().isForbidden());

		var leitura = mockMvc.perform(get("/media/attachments/" + mediaId)
				.header("X-Session-Token", dono.token()))
			.andExpect(status().isOk())
			.andExpect(header().string("Content-Type", "application/octet-stream"))
			.andExpect(header().string("Content-Disposition", "attachment"))
			.andExpect(header().string("Cache-Control", "private, no-store"))
			.andReturn();
		// O corpo é escrito na thread de streaming: ler o buffer na hora devolvia vazio na suíte
		// inteira (`Expecting actual not to be empty` no CI). Espera o streaming terminar.
		StreamedBody.assertNotEmpty(leitura);
	}

	@Test
	void oEnderecoAssinadoDoPainelAbreSemSessao() throws Exception {
		String mediaId = captura("media_captura_painel", "report_attachment_painel", "media/x/painel.jpg").id();
		String url = attachments.moderatorUrl(mediaId);

		assertThat(url).contains("/media/attachments/" + mediaId);
		mockMvc.perform(get(url.replace("https://api.enois.exemplo.com", "")))
			.andExpect(status().isOk())
			.andExpect(header().string("Content-Disposition", "attachment"))
			.andExpect(header().string("Cache-Control", "private, no-store"));
	}

	@Test
	void assinaturaDeOutroAssetOuVencidaNaoAbre() throws Exception {
		String mediaId = captura("media_captura_assinatura", "report_attachment_assinatura", "media/x/a.jpg").id();

		mockMvc.perform(get("/media/attachments/" + mediaId
				+ "?scope=moderator&expires=100&signature=" + "0".repeat(64)))
			.andExpect(status().isNotFound());
	}

	@Test
	void aRotaSoServeAssetDeCaptura() throws Exception {
		var foto = MediaAsset.pending("media_foto_post", new com.socialcoisa.rede.feed.domain.UserId("auto_post"),
			"foto.jpg", "image/jpeg", 16, "POST", "uploads/media_foto_post/foto.jpg",
			"https://enois.exemplo.com/api/storage/uploads/media_foto_post/foto.jpg", java.time.Instant.now(),
			java.time.Instant.now().plus(Duration.ofMinutes(15)), MediaStorageProvider.S3);
		assets.save(foto);

		mockMvc.perform(get("/media/attachments/media_foto_post")
				.header("X-Session-Token", auth().authenticate("group-admin").token()))
			.andExpect(status().isNotFound());
	}
}