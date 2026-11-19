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
		mockMvc.perform(get("/papo-reto/meetup/publicacoes")).andExpect(status().isUnauthorized());
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

	@Test
	void publicacoesRelacionadasTrazemOTextoPublicoDoAssunto() throws Exception {
		var leitor = auth("papo_rel_leitor");

		// Cinco contas publicam a MESMA expressão: o assunto tem gente de verdade, e são
		// essas as publicações que a view filtrada do Papo Reto devolve.
		for (int i = 1; i <= 5; i++) {
			var autor = auth("papo_rel_autor_" + i);
			mockMvc.perform(post("/feed/posts")
					.header("X-Session-Token", autor.token())
					.header("Idempotency-Key", "papo-rel-" + UUID.randomUUID())
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(Map.of("type", PostType.INSIGHT.name(),
						"content", "cogumelo magico no quintal", "topic", "OUTROS"))))
				.andExpect(status().isCreated());
		}

		worker.corte();

		MvcResult rankingLido = mockMvc.perform(get("/papo-reto").header("X-Session-Token", leitor.token()))
			.andExpect(status().isOk())
			.andReturn();
		org.assertj.core.api.Assertions
			.assertThat(rankingLido.getResponse().getContentAsString())
			.contains("cogumelo magico");

		MvcResult resultado = mockMvc
			.perform(get("/papo-reto/{subject}/publicacoes", "cogumelo magico")
				.header("X-Session-Token", leitor.token()))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode relacionadas = objectMapper.readTree(resultado.getResponse().getContentAsString());

		org.assertj.core.api.Assertions.assertThat(relacionadas.size()).isEqualTo(5);
		JsonNode primeira = relacionadas.get(0);
		org.assertj.core.api.Assertions.assertThat(primeira.get("texto").asText())
			.isEqualTo("cogumelo magico no quintal");
		org.assertj.core.api.Assertions.assertThat(primeira.get("fonte").asText()).isEqualTo("POST");
		org.assertj.core.api.Assertions.assertThat(primeira.get("href").asText()).startsWith("/post/");
		org.assertj.core.api.Assertions.assertThat(primeira.get("autor").asText()).isNotBlank();
		// O id interno de quem publicou não atravessa a view.
		for (JsonNode publicacao : relacionadas) {
			org.assertj.core.api.Assertions.assertThat(publicacao.has("autorId")).isFalse();
		}
	}

	@Test
	void assuntoDeRuidoNaoAbrePublicacoes() throws Exception {
		var leitor = auth("papo_rel_leitor2");

		// O "http" que entrou antes da correção não tem view: a régua da leitura recusa.
		MvcResult resultado = mockMvc.perform(get("/papo-reto/http/publicacoes").header("X-Session-Token",
				leitor.token()))
			.andExpect(status().isOk())
			.andReturn();

		org.assertj.core.api.Assertions
			.assertThat(objectMapper.readTree(resultado.getResponse().getContentAsString()).size())
			.isZero();
	}

	private TestAuthHelper.AuthSession auth(String name) throws Exception {
		return new TestAuthHelper(mockMvc, objectMapper).authenticate(name);
	}

}
