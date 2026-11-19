package com.socialcoisa.rede.games.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.ObjectMapper;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

/**
 * Progresso dos jogos vinculado à conta (épico "Proposta: vincular o progresso dos jogos à
 * conta"): trocar de aparelho ou limpar dados não perde o histórico. O estado é opaco para o
 * servidor; último estado vence; o progresso é privado do dono.
 */
@SpringBootTest
@AutoConfigureMockMvc
class GameProgressTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	/** Sem estado nenhum, a leitura devolve nulo — o jogo começa do zero do lado dele. */
	@Test
	void leituraSemProgressoDevolveNulo() throws Exception {
		var jogador = auth("gprog_leitura");

		mockMvc.perform(get("/games/game_wordle/progresso").header("X-Session-Token", jogador.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.gameId").value("game_wordle"))
			.andExpect(jsonPath("$.progresso").value(org.hamcrest.Matchers.nullValue()));
	}

	/** O coração do épico: gravou em um dispositivo, abriu em outro, o progresso está lá. */
	@Test
	void progressoSalvoSobreviveATrocaDeDispositivo() throws Exception {
		var jogador = auth("gprog_multi");
		String estado = "{\"nivel\":3,\"pontos\":120,\"fase\":\"floresta\"}";

		mockMvc.perform(put("/games/game_wordle/progresso").header("X-Session-Token", jogador.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("state", estado))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.progresso").value(estado));

		// "Outro dispositivo": uma sessão nova da mesma conta lê o mesmo estado.
		var mesmaConta = auth("gprog_multi");
		mockMvc.perform(get("/games/game_wordle/progresso").header("X-Session-Token", mesmaConta.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.progresso").value(estado));
	}

	/** Último estado vence: o PUT substitui o inteiro, sem remendar história antiga. */
	@Test
	void segundoPutSubstituiOEstado() throws Exception {
		var jogador = auth("gprog_substitui");

		mockMvc.perform(put("/games/game_rpg/progresso").header("X-Session-Token", jogador.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("state", "{\"nivel\":1}"))))
			.andExpect(status().isOk());

		MvcResult salvo = mockMvc.perform(put("/games/game_rpg/progresso").header("X-Session-Token", jogador.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("state", "{\"nivel\":2,\"chefao\":true}"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.updatedAt").isNotEmpty()).andReturn();

		// O PUT e o GET serializam o mesmo instante com precisões diferentes (nanos vs micros do
		// banco): compara o INSTANTE, não a string.
		String updatedAt = objectMapper.readTree(salvo.getResponse().getContentAsString(
			java.nio.charset.StandardCharsets.UTF_8)).get("updatedAt").asString();

		MvcResult leitura = mockMvc.perform(get("/games/game_rpg/progresso").header("X-Session-Token", jogador.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.progresso").value("{\"nivel\":2,\"chefao\":true}"))
			.andReturn();

		String updatedAtGet = objectMapper.readTree(leitura.getResponse().getContentAsString(
			java.nio.charset.StandardCharsets.UTF_8)).get("updatedAt").asString();
		// O banco arredonda nanos para micros (às vezes 1 micro abaixo do instante da JVM): o
		// carimbo do GET tem que ser o MESMO instante dentro de 1 micro — o que o cliente usa é
		// comparar "é mais novo que o meu?", não a igualdade exata de strings.
		org.assertj.core.api.Assertions
			.assertThat(java.time.Duration.between(java.time.Instant.parse(updatedAt),
				java.time.Instant.parse(updatedAtGet)).abs())
			.isLessThanOrEqualTo(java.time.Duration.of(1, java.time.temporal.ChronoUnit.MICROS));
	}

	/** O progresso é privado do dono: outra conta lê nulo e não sobrescreve o alheio. */
	@Test
	void progressoEPrivadoDoDono() throws Exception {
		var dona = auth("gprog_privado_dona");
		var outra = auth("gprog_privado_outra");

		mockMvc.perform(put("/games/game_maze/progresso").header("X-Session-Token", dona.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("state", "{\"sala\":7}"))))
			.andExpect(status().isOk());

		mockMvc.perform(get("/games/game_maze/progresso").header("X-Session-Token", outra.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.progresso").value(org.hamcrest.Matchers.nullValue()));

		// A outra grava o dela; o da dona fica intacto.
		mockMvc.perform(put("/games/game_maze/progresso").header("X-Session-Token", outra.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("state", "{\"sala\":1}"))))
			.andExpect(status().isOk());
		mockMvc.perform(get("/games/game_maze/progresso").header("X-Session-Token", dona.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.progresso").value("{\"sala\":7}"));
	}

	/** Estado vazio não é progresso; estado gigante não é backup de dados arbitrários. */
	@Test
	void recusaEstadoVazioOuGrande() throws Exception {
		var jogador = auth("gprog_recusa");

		mockMvc.perform(put("/games/game_x/progresso").header("X-Session-Token", jogador.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("state", "   "))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("PROGRESSO_VAZIO"));

		String gigante = "x".repeat(32_001);
		mockMvc.perform(put("/games/game_x/progresso").header("X-Session-Token", jogador.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("state", gigante))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("PROGRESSO_GRANDE"));
	}

	/** Sem sessão ninguém lê nem grava progresso (é dado de conta). */
	@Test
	void exigeSessao() throws Exception {
		mockMvc.perform(get("/games/game_wordle/progresso")).andExpect(status().isUnauthorized());
		mockMvc.perform(put("/games/game_wordle/progresso").contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("state", "{}"))))
			.andExpect(status().isUnauthorized());
	}


	private TestAuthHelper.AuthSession auth(String name) throws Exception {
		return new TestAuthHelper(mockMvc, objectMapper).authenticate(name);
	}
}
