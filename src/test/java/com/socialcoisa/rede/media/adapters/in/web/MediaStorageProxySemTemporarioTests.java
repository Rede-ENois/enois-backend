package com.socialcoisa.rede.media.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.ObjectMapper;
import com.socialcoisa.rede.media.application.port.out.MediaObjectStore;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

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

	@TempDir
	static Path dir;

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
		// Áudio, não vídeo: vídeo tem o teto próprio de 10MB (issue #656), abaixo de MAX_IN_MEMORY;
		// o caminho de arquivo temporário é por TAMANHO e continua coberto por áudio.
		byte[] audio = new byte[41 * 1024 * 1024];
		String token = auth.authenticate("media_proxy_video_sem_tmp").token();
		var criado = mockMvc.perform(post("/media/uploads")
				.header("X-Session-Token", token)
				.header("Idempotency-Key", "media-proxy-video-3")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("fileName", "aula.mp3",
					"mimeType", "audio/mpeg", "size", (long) audio.length, "purpose", "POST_IMAGE"))))
			.andExpect(status().isCreated())
			.andReturn();
		String objectKey = objectMapper.readTree(criado.getResponse().getContentAsString())
			.path("objectKey").asText();

		// O contexto deste teste injeta um resolvedor sem nenhuma pasta gravável (ver `SemTemporario`),
		// então nada aqui mexe em `java.io.tmpdir`: a decisão entra pelo bean, não pelo processo.
		mockMvc.perform(put("/storage/" + objectKey)
				.header("X-Session-Token", token)
				.contentType("audio/mpeg")
				.content(audio))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("MEDIA_UPLOAD_UNAVAILABLE"))
			.andExpect(jsonPath("$.message").value(
				org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(dir.toString()))));
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
		com.socialcoisa.rede.media.application.MediaTemporaryFiles semPastaGravavel() throws Exception {
			Path arquivo = Files.createFile(dir.resolve("arquivo-nao-e-pasta"));
			return new com.socialcoisa.rede.media.application.MediaTemporaryFiles(
				arquivo.resolve("configurada").toString(), arquivo.resolve("dados").toString(),
				arquivo.resolve("tmp").toString(), null);
		}
	}
}