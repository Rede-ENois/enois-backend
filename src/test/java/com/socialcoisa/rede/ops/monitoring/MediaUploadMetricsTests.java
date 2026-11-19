package com.socialcoisa.rede.ops.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestDatabase;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.ObjectMapper;

/**
 * As métricas do upload de mídia aparecem no painel de monitorabilidade (#925): a rota lê os
 * timers e resumos que a instrumentação do módulo publica — nada é contado duas vezes; a leitura
 * é de moderador para cima (401 sem sessão, 403 com sessão comum); e a resposta é só agregado por
 * etapa/resultado e por tipo, sem id de asset, mimetype ou caminho.
 */
@SpringBootTest(properties = "rede.monitoring.file-dir=${java.io.tmpdir}/rede-monitoring-media-test")
@AutoConfigureMockMvc
class MediaUploadMetricsTests {

	static {
		System.setProperty("aws.accessKeyId", "test");
		System.setProperty("aws.secretAccessKey", "test");
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private MeterRegistry meters;

	private TestAuthHelper authHelper() {
		return new TestAuthHelper(mockMvc, objectMapper);
	}

	@TestConfiguration
	static class RegistryDeTeste {
		@Bean
		@Primary
		MeterRegistry meterRegistry() {
			return new SimpleMeterRegistry();
		}
	}

	@Test
	void etapaRegistradaPelaInstrumentacaoApareceNoPainel() throws Exception {
		var leitor = authHelper().authenticate("media-metrics-leitor");
		TestDatabase.setAdmin(leitor.userId());

		// Registra exatamente o que a instrumentação do módulo grava num upload real.
		Timer.builder("rede_media_upload_stage").tag("stage", "sanitize").tag("outcome", "ok")
			.publishPercentiles(0.5, 0.95).register(meters).record(120, TimeUnit.MILLISECONDS);
		DistributionSummary.builder("rede_media_upload_bytes").tag("kind", "video")
			.publishPercentiles(0.5, 0.95).register(meters).record(9_500_000);

		MvcResult painel = mockMvc
			.perform(get("/admin/monitoring/media").header("X-Session-Token", leitor.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.stages[?(@.stage=='sanitize' && @.outcome=='ok')].count").value(1))
			.andExpect(jsonPath("$.stages[?(@.stage=='sanitize' && @.outcome=='ok')].totalMs").value(120))
			.andExpect(jsonPath("$.sizes.video.count").value(1))
			.andExpect(jsonPath("$.sizes.video.totalBytes").value(9500000))
			.andReturn();

		// O painel ordena por etapa para a leitura humana ficar estável entre chamadas.
		var corpo = objectMapper.readTree(painel.getResponse().getContentAsString());
		var etapas = corpo.get("stages");
		assertThat(etapas.size()).isGreaterThanOrEqualTo(1);
		for (int i = 1; i < etapas.size(); i++) {
			assertThat(etapas.get(i).get("stage").asString().toLowerCase())
				.isGreaterThanOrEqualTo(etapas.get(i - 1).get("stage").asString().toLowerCase());
		}
	}

	@Test
	void aLeituraEExclusivaDeModerador() throws Exception {
		var comum = authHelper().authenticate("media-metrics-guarda");

		// Sem sessão é 401: o resolver lança antes de qualquer leitura.
		mockMvc.perform(get("/admin/monitoring/media"))
			.andExpect(status().isUnauthorized());

		// Sessão comum é 403: ser da comunidade não dá direito ao painel.
		mockMvc.perform(get("/admin/monitoring/media").header("X-Session-Token", comum.token()))
			.andExpect(status().isForbidden());

		var leitor = authHelper().authenticate("media-metrics-guarda-leitor");
		TestDatabase.setAdmin(leitor.userId());
		mockMvc.perform(get("/admin/monitoring/media").header("X-Session-Token", leitor.token()))
			.andExpect(status().isOk());
	}
}
