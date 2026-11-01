package com.socialcoisa.rede.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.ObjectMapper;

/**
 * A politica HTTP da leitura publica: corpo guardado mas conferido, e nunca politica publica na
 * resposta de quem esta logado.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PublicReadCacheHttpTests {

	private static final String PUBLIC_POLICY = "public, max-age=0, must-revalidate";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void anAnonymousListCarriesAnEtagAndTheRevalidatingPolicy() throws Exception {
		MvcResult first = mockMvc.perform(get("/communities")).andExpect(status().isOk()).andReturn();

		assertThat(first.getResponse().getHeader("ETag")).isNotBlank();
		assertThat(first.getResponse().getHeader("Cache-Control")).isEqualTo(PUBLIC_POLICY);
	}

	@Test
	void theSameBodyComesBackAsNotModified() throws Exception {
		MvcResult first = mockMvc.perform(get("/communities")).andExpect(status().isOk()).andReturn();
		String etag = first.getResponse().getHeader("ETag");

		MvcResult second = mockMvc.perform(get("/communities").header("If-None-Match", etag)).andReturn();

		assertThat(second.getResponse().getStatus()).isEqualTo(304);
		assertThat(second.getResponse().getContentAsString()).isEmpty();
	}

	@Test
	void aLoggedInReaderNeverGetsThePublicPolicy() throws Exception {
		TestAuthHelper.AuthSession session = new TestAuthHelper(mockMvc, objectMapper)
			.authenticate("public_cache_http_" + System.nanoTime());

		MvcResult result = mockMvc.perform(get("/communities").header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andReturn();

		assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
		assertThat(result.getResponse().getHeader("ETag")).isNull();
	}

	/**
	 * A vitrine cacheada e a mesma para todos; o "sou membro" continua sendo de cada leitor. Sem
	 * isso, guardar a lista entregaria a visao de um usuario a todos os outros.
	 */
	@Test
	void theCachedListKeepsEachReadersOwnMembership() throws Exception {
		TestAuthHelper auth = new TestAuthHelper(mockMvc, objectMapper);
		String marker = "comunidade_cache_" + System.nanoTime();
		TestAuthHelper.AuthSession owner = auth.authenticate("public_cache_owner_" + System.nanoTime());

		MvcResult created = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
			.post("/communities")
			.header("X-Session-Token", owner.token())
			.header("Idempotency-Key", "community-cache-" + marker)
			.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(java.util.Map.of("name", marker, "topic", "GERAL"))))
			.andExpect(status().is2xxSuccessful())
			.andReturn();
		String communityId = objectMapper.readTree(created.getResponse().getContentAsString()).path("id").asText();

		// Primeira leitura: populou o cache. Segunda, com sessao do dono: mesma lista, outra visao.
		assertThat(joinedFlag(mockMvc.perform(get("/communities")).andReturn().getResponse().getContentAsString(),
			communityId)).isFalse();
		assertThat(joinedFlag(mockMvc.perform(get("/communities").header("X-Session-Token", owner.token()))
			.andReturn().getResponse().getContentAsString(), communityId)).isTrue();
	}

	private boolean joinedFlag(String body, String communityId) {
		for (tools.jackson.databind.JsonNode item : objectMapper.readTree(body).path("items")) {
			if (communityId.equals(item.path("id").asText())) {
				return item.path("joined").asBoolean();
			}
		}
		throw new AssertionError("comunidade ausente na vitrine: " + communityId);
	}

	@Test
	void aRouteOutsideThePublicListKeepsNoPolicyFromUs() throws Exception {
		// Notificacao e leitura pessoal: nao pode ganhar ETag nem politica publica.
		MvcResult result = mockMvc.perform(get("/notifications")).andReturn();

		assertThat(result.getResponse().getHeader("Cache-Control")).isNotEqualTo(PUBLIC_POLICY);
	}
}
