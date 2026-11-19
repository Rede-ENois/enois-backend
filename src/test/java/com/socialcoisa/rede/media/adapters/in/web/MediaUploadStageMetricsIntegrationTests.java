package com.socialcoisa.rede.media.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.media.application.StubFfmpegRunner;
import com.socialcoisa.rede.media.application.port.out.MediaObjectStore;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.ObjectMapper;

/**
 * As etapas do upload aparecem na série `rede_media_upload_stage` com o resultado técnico (#710):
 * um upload que passa registra sanitize e write como ok, sem mudar resposta nem fluxo.
 * O caminho blocked da verificação de conteúdo é coberto na unidade
 * (MediaImageModerationServiceTests), porque o contexto ligado exigiria o modelo ONNX real.
 */
@SpringBootTest(properties = { "rede.media.storage=s3", "rede.media.s3.bucket=bucket-de-teste",
		"rede.media.s3.publicBaseUrl=https://enois.exemplo.com/api/storage",
		"rede.media.attachments.secret=segredo-de-teste-com-mais-de-32-caracteres" })
@AutoConfigureMockMvc
class MediaUploadStageMetricsIntegrationTests {

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
	private MeterRegistry registry;

	@TestConfiguration
	static class Registro {

		@Bean
		@Primary
		MeterRegistry registroDeTeste() {
			return new SimpleMeterRegistry();
		}

		@Bean
		@Primary
		MediaObjectStore fakeObjectStore() {
			return new MediaStorageProxyTests.FakeObjectStore();
		}

		@Bean
		@Primary
		StubFfmpegRunner stubFfmpegRunner() {
			return new StubFfmpegRunner();
		}

	}

	private String assetDeImagem(String dono, String idempotencia, int tamanho) throws Exception {
		var criado = mockMvc.perform(post("/media/uploads")
				.header("X-Session-Token", new TestAuthHelper(mockMvc, objectMapper).authenticate(dono).token())
				.header("Idempotency-Key", idempotencia)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("fileName", "foto.jpg",
					"mimeType", "image/jpeg", "size", (long) tamanho, "purpose", "POST_IMAGE"))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(criado.getResponse().getContentAsString()).path("objectKey").asText();
	}

	@Test
	void uploadQuePassaRegistraAsTresEtapasComoOk() throws Exception {
		byte[] imagem = "imagem-legitima-de-teste".getBytes(StandardCharsets.UTF_8);
		String objectKey = assetDeImagem("media_stage_ok", "media-stage-ok-1", imagem.length);

		mockMvc.perform(put("/storage/" + objectKey)
				.header("X-Session-Token", new TestAuthHelper(mockMvc, objectMapper).authenticate("media_stage_ok").token())
				.contentType(MediaType.IMAGE_JPEG)
				.content(imagem))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("STORED"));

		// o nsfw fica desligado neste contexto (o modelo ONNX nao esta no repositorio),
		// por isso a prova dele e unitaria
		for (String stage : new String[] { "sanitize", "write" }) {
			Timer timer = registry.find("rede_media_upload_stage")
				.tags("stage", stage, "outcome", "ok").timer();
			assertThat(timer).as("etapa " + stage + " registada como ok").isNotNull();
			assertThat(timer.count()).isGreaterThanOrEqualTo(1);
		}
		assertThat(((MediaStorageProxyTests.FakeObjectStore) store).chaveEscrita).isEqualTo(objectKey);
	}

}
