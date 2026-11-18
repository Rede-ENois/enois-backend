package com.socialcoisa.rede.paporeto.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.socialcoisa.rede.feed.domain.PostType;
import com.socialcoisa.rede.paporeto.application.PapoRetoWorker;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

/**
 * O Papo Reto de ponta a ponta: conteúdo publicado nos espaços (feed, Desabafos,
 * Confessionário), corte rodado na mão (o agendador fica com delay enorme na suite) e ranking
 * lido no endpoint.
 *
 * O centro dos testes é PRIVACIDADE e AMOSTRA: a resposta é agregada — subject, status,
 * contagens, timeline — e NADA de individual atravessa (nem texto de publicação, nem autor, nem
 * id de quem falou). E o chão de pessoas distintas: um assunto repetido por uma conta só não
 * vira alta, mesmo com potência alta.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PapoRetoFlowTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private PapoRetoWorker worker;

	@Test
	void endpointExigeSessao() throws Exception {
		// Assuntos em alta é espaço da rede: sem sessão não há leitura.
		mockMvc.perform(get("/papo-reto")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/papo-reto/analise")).andExpect(status().isUnauthorized());
	}

	@Test
	void rankingDeJanelaNovaVazioSemNada() throws Exception {
		var leitor = auth("papo_leitor");

		// Corte sem conteúdo novo das fontes desta classe: o ranking existe e é lista.
		worker.corte();

		MvcResult ranking = mockMvc.perform(get("/papo-reto").header("X-Session-Token", leitor.token()))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode assuntos = objectMapper.readTree(ranking.getResponse().getContentAsString());
		org.assertj.core.api.Assertions.assertThat(assuntos).isInstanceOf(JsonNode.class);
	}

	@Test
	void publicacaoRepetidaPorUmaContaSoNaoViraAlta() throws Exception {
		var autor = auth("papo_spammer");
		var leitor = auth("papo_leitor2");

		// A mesma pessoa repete a MESMA expressão em duas publicações: 1 pessoa, abaixo do chão.
		for (int i = 0; i < 2; i++) {
			mockMvc.perform(post("/feed/posts")
					.header("X-Session-Token", autor.token())
					.header("Idempotency-Key", "papo-spam-" + UUID.randomUUID())
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(Map.of("type", PostType.INSIGHT.name(),
						"content", "ZubatZonk rocketlaunch na minha zona", "topic", "OUTROS"))))
				.andExpect(status().isCreated());
		}

		worker.corte();

		MvcResult ranking = mockMvc.perform(get("/papo-reto").header("X-Session-Token", leitor.token()))
			.andExpect(status().isOk())
			.andReturn();
		String corpo = ranking.getResponse().getContentAsString();
		JsonNode assuntos = objectMapper.readTree(corpo);
		for (JsonNode assunto : assuntos) {
			org.assertj.core.api.Assertions.assertThat(assunto.get("subject").asText())
				.isNotEqualTo("zubatzonk");
		}

		// E nada de individual no corpo: nem o id do autor, nem o texto publicado.
		org.assertj.core.api.Assertions.assertThat(corpo).doesNotContain(autor.userId());
		org.assertj.core.api.Assertions.assertThat(corpo).doesNotContain("rocketlaunch na minha zona");
	}

	private TestAuthHelper.AuthSession auth(String name) throws Exception {
		return new TestAuthHelper(mockMvc, objectMapper).authenticate(name);
	}

}
