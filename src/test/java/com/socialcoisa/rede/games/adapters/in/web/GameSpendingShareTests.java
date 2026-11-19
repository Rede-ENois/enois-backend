package com.socialcoisa.rede.games.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.games.application.port.out.GameRepository;
import com.socialcoisa.rede.games.domain.Game;
import com.socialcoisa.rede.identity.application.EmailService;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "spring.task.scheduling.enabled=false")
@AutoConfigureMockMvc
class GameSpendingShareTests {

	@Autowired private MockMvc mockMvc;
	@Autowired private ObjectMapper objectMapper;
	@Autowired private GameRepository games;
	@MockitoBean private EmailService emailService;

	@Test
	void dozeGastosNaoConsomemCompartilhamentosNemInflamAMetrica() throws Exception {
		when(emailService.sendHtml(anyString(), anyString(), anyString())).thenReturn(true);
		var jogador = new TestAuthHelper(mockMvc, objectMapper).authenticate("b3_gasto_jogador");
		String sufixo = UUID.randomUUID().toString().replace("-", "");
		String jogoId = "game_b3_" + sufixo;
		Instant criadoEm = Instant.now().minusSeconds(60);
		games.save(Game.submit(jogoId, jogador.userId(), "gasto-b3-" + sufixo, "Jogo de gastos",
			null, null, "https://8.8.8.8/b3", null, criadoEm)
			.approve("revisor_b3", "Aprovado", criadoEm));

		long inicial = saldo(jogador);
		assertThat(inicial).isGreaterThanOrEqualTo(12L);

		for (int i = 0; i < 12; i++) {
			mockMvc.perform(post("/games/" + jogoId + "/despesas")
					.header("X-Session-Token", jogador.token())
					.header("Idempotency-Key", "b3-despesa-" + sufixo + "-" + i)
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(Map.of(
						"amount", 1L, "motivo", "Item do jogo", "requestId", "b3-" + sufixo + "-" + i))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("DEBITED"));
		}
		assertThat(saldo(jogador)).isEqualTo(inicial - 12L);

		mockMvc.perform(post("/games/" + jogoId + "/compartilhar")
				.header("X-Session-Token", jogador.token())
				.header("Idempotency-Key", "b3-compartilhar-" + sufixo)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("texto", "Concluí a primeira fase."))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("SHARED"));

		var metricas = mockMvc.perform(get("/games/" + jogoId + "/analytics")
				.header("X-Session-Token", jogador.token())
				.param("days", "2"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.series.length()").value(2))
			.andReturn();
		long compartilhamentos = objectMapper.readTree(metricas.getResponse().getContentAsString())
			.get("series").valueStream()
			.mapToLong(dia -> dia.get("shares").asLong())
			.sum();
		assertThat(compartilhamentos).isEqualTo(1L);
	}

	private long saldo(TestAuthHelper.AuthSession jogador) throws Exception {
		var result = mockMvc.perform(get("/carteira").header("X-Session-Token", jogador.token()))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("balance").asLong();
	}
}
