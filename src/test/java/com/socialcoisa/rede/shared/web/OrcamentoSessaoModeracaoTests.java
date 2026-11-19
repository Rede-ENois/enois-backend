package com.socialcoisa.rede.shared.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.socialcoisa.rede.identity.application.port.out.AuthSessionRepository;

@SpringBootTest(properties = {"rede.rate.public-read-per-minute=1",
	"resilience4j.ratelimiter.instances.twofa.limit-for-period=1",
	"resilience4j.ratelimiter.instances.twofa.limit-refresh-period=1h",
	"resilience4j.ratelimiter.instances.twofa.timeout-duration=0"})
@AutoConfigureMockMvc(print = org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
class OrcamentoSessaoModeracaoTests {
	@Autowired MockMvc mvc;
	@MockitoSpyBean AuthSessionRepository sessoes;

	@Test
	void consultaPublicaDeDesafioRespeitaLimitadorExistenteDoSegundoFator() throws Exception {
		mvc.perform(get("/auth/2fa/challenge")).andExpect(status().isUnauthorized());
		mvc.perform(get("/auth/2fa/challenge")).andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("RATE_LIMITED"));
	}

	@Test
	void sessaoInventadaNaoConsultaPersistenciaAposEsgotarOrcamento() throws Exception {
		var origem = origem("198.18.70.5");
		for (int indice = 0; indice < 60; indice++) {
			mvc.perform(get("/auth/config").with(origem).header("X-Session-Token", "invalido705_" + indice))
				.andExpect(status().isOk());
		}
		mvc.perform(get("/auth/config").with(origem).header("X-Session-Token", "esgotado705"))
			.andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"))
			.andExpect(jsonPath("$.code").value("SESSION_LOOKUP_LIMIT"));
		verify(sessoes, never()).isSanctionsOnly(eq("esgotado705"), any());
		verify(sessoes, never()).findActiveSanctionsUserId(eq("esgotado705"), any());
	}

	@Test
	void leituraPublicaRecusadaNaoExecutaConsultaAdicionalDeEscopo() throws Exception {
		var origem = origem("198.18.70.6");
		mvc.perform(get("/users/search").with(origem).param("q", "sintetico705")
			.header("X-Session-Token", "primeiro705")).andExpect(status().isOk());
		mvc.perform(get("/users/search").with(origem).param("q", "sintetico705")
			.header("X-Session-Token", "segundo705")).andExpect(status().isTooManyRequests());
		verify(sessoes, never()).findActiveSanctionsUserId(eq("segundo705"), any());
		verify(sessoes, never()).isSanctionsOnly(eq("segundo705"), any());
	}

	private RequestPostProcessor origem(String endereco) {
		return pedido -> { pedido.setRemoteAddr(endereco); return pedido; };
	}
}
