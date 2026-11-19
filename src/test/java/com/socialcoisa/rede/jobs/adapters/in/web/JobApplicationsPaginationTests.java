package com.socialcoisa.rede.jobs.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.identity.application.EmailService;
import com.socialcoisa.rede.jobs.application.port.out.JobRepository;
import com.socialcoisa.rede.jobs.domain.Job;
import com.socialcoisa.rede.jobs.domain.JobApplication;
import com.socialcoisa.rede.shared.web.CursorCodec;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "spring.task.scheduling.enabled=false")
@AutoConfigureMockMvc
class JobApplicationsPaginationTests {

	private static final Instant AGORA = Instant.parse("2026-11-17T12:00:00Z");
	private static final Instant PUBLICACAO = Instant.parse("2026-01-01T00:00:00Z");

	@Autowired private MockMvc mockMvc;
	@Autowired private ObjectMapper objectMapper;
	@Autowired private JobRepository repositorio;
	@MockitoBean private EmailService emailService;

	private TestAuthHelper auth;

	@BeforeEach
	void preparar() {
		when(emailService.sendHtml(anyString(), anyString(), anyString())).thenReturn(true);
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class RelogioConfig {
		@Bean
		@Primary
		Clock relogioDaPaginacao() {
			return Clock.fixed(AGORA, ZoneOffset.UTC);
		}
	}

	@Test
	void percorreTodasAsCandidaturasSemRepetirOuPularEmpates() throws Exception {
		var autor = auth.authenticate("b4_pagina_autor");
		var candidato = auth.authenticate("b4_pagina_candidato");
		auth.activateProfessional(candidato);

		mockMvc.perform(get("/jobs/applications")
				.header("X-Session-Token", candidato.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(0))
			.andExpect(jsonPath("$.nextCursor").doesNotExist());

		List<String> vagas = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			String vagaId = "job_b4_" + candidato.userId() + "_" + i;
			Instant aplicada = AGORA.minusSeconds(86400L + (i < 3 ? 0 : (i - 2) * 60L));
			repositorio.save(Job.create(vagaId, "Vaga B4 " + i, "Empresa B4", null, "Remoto",
				"CLT", "PLENO", null, "BRL", new BigDecimal("1500"), null, "MONTH",
				"Vaga de teste com valor.", List.of(), List.of(), autor.userId(),
				PUBLICACAO, PUBLICACAO, null));
			repositorio.saveApplication(JobApplication.create(null, vagaId, candidato.userId(),
				"Tenho interesse", "{}", aplicada, List.of()));
			vagas.add(vagaId);
		}

		List<String> recebidas = new ArrayList<>();
		String cursor = null;
		for (int numero = 0; numero < 3; numero++) {
			var result = mockMvc.perform(get("/jobs/applications")
					.header("X-Session-Token", candidato.token())
					.param("limit", "2")
					.param("cursor", cursor == null ? "" : cursor))
				.andExpect(status().isOk()).andReturn();
			JsonNode pagina = objectMapper.readTree(result.getResponse().getContentAsString());
			assertThat(pagina.get("items").size()).isEqualTo(numero < 2 ? 2 : 1);
			pagina.get("items").forEach(item -> {
				recebidas.add(item.get("id").asText());
				assertThat(item.get("postedAt").asText()).isEqualTo(PUBLICACAO.toString());
				assertThat(item.get("applied").asBoolean()).isTrue();
			});
			JsonNode proximo = pagina.get("nextCursor");
			cursor = proximo == null || proximo.isNull() ? null : proximo.asText();
			if (numero < 2) {
				assertThat(cursor).isNotBlank();
			}
		}

		assertThat(cursor).isNull();
		assertThat(recebidas).containsExactly(vagas.get(2), vagas.get(1), vagas.get(0),
			vagas.get(3), vagas.get(4));

		mockMvc.perform(get("/jobs/applications").header("X-Session-Token", autor.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(0));
	}

	@Test
	void recusaDesempateNaoNumericoNoCursor() throws Exception {
		var candidato = auth.authenticate("b4_cursor_invalido");
		String cursor = new CursorCodec().encodePage(AGORA, "nao_numerico");

		mockMvc.perform(get("/jobs/applications")
				.header("X-Session-Token", candidato.token())
				.param("cursor", cursor))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
	}
}
