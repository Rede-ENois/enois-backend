package com.socialcoisa.rede.apiv1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import org.junit.jupiter.api.Tag;
import tools.jackson.databind.ObjectMapper;

/**
 * O limite de taxa do login tem de valer no caminho versionado.
 *
 * <p>Este é o ponto mais delicado do alias: o limitador por IP reconhece caminhos pelo nome
 * ("/auth/login"). Se o filtro do v1 reescrevesse o caminho <b>depois</b> do limitador, o login
 * pelo <code>/v1</code> passaria por um caminho que o limitador não conhece — e a proteção contra
 * força bruta viraria uma porta dos fundos. O teste fixa a ordem: o filtro roda antes.
 */
@Tag("critico")
@SpringBootTest(properties = {
	// O limite por IP do login, bem baixo, para o teste fechar em tres chamadas.
	"rede.security.rate-limit.login.limit=2",
	"springdoc.api-docs.enabled=false",
	"springdoc.swagger-ui.enabled=false",
})
@AutoConfigureMockMvc
class V1LoginRateLimitTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void loginPeloV1UsaOMesmoLimiteDoLoginSemVersao() throws Exception {
		String payload = objectMapper.writeValueAsString(Map.of(
			"email", "v1-rate-limit@test.com",
			"password", "SenhaErrada1!"));

		// 1) Uma tentativa pelo caminho sem versao: conta no limite do IP.
		MvcResult primeira = mockMvc.perform(post("/auth/login")
				.header("Idempotency-Key", "v1-rate-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(payload)).andReturn();
		assertThat(primeira.getResponse().getStatus()).isEqualTo(401);

		// 2) A tentativa pelo v1 ainda passa (o limite e 2).
		MvcResult segunda = mockMvc.perform(post("/v1/auth/login")
				.header("Idempotency-Key", "v1-rate-2")
				.contentType(MediaType.APPLICATION_JSON)
				.content(payload)).andReturn();
		assertThat(segunda.getResponse().getStatus()).isEqualTo(401);

		// 3) A terceira e barrada: o v1 conta no MESMO balde do caminho sem versao.
		MvcResult terceira = mockMvc.perform(post("/v1/auth/login")
				.header("Idempotency-Key", "v1-rate-3")
				.contentType(MediaType.APPLICATION_JSON)
				.content(payload)).andReturn();
		assertThat(terceira.getResponse().getStatus()).isEqualTo(429);
		assertThat(terceira.getResponse().getContentAsString()).contains("RATE_LIMITED");
	}
}
