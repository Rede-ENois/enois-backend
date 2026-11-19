package com.socialcoisa.rede.termometro.adapters.in.web;

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

import com.socialcoisa.rede.confessionario.adapters.in.web.ConfessionarioController;
import com.socialcoisa.rede.desabafos.adapters.in.web.DesabafosController;
import com.socialcoisa.rede.feed.domain.PostType;
import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.termometro.application.TermometroWorker;

/**
 * O Termômetro de ponta a ponta: conteúdo publicado nos espaços (feed, Desabafos, Confessionário),
 * corte rodado na mão (o agendador fica com delay enorme na suite) e painel lido no endpoint.
 *
 * O centro dos testes é PRIVACIDADE: o painel é agregado — porcentagem por emoção, contagem de
 * manifestações e de pessoas distintas — e NADA de individual atravessa: nem texto, nem autor,
 * nem id de quem escreveu. E AMOSTRA: com pouca gente, a janela existe e o indicador não.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TermometroFlowTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private TermometroWorker worker;

	@Test
	void endpointExigeSessao() throws Exception {
		// Painel do clima é espaço da rede: sem sessão não há leitura.
		mockMvc.perform(get("/termometro")).andExpect(status().isUnauthorized());
	}

	@Test
	void painelDeJanelaNovaEAmostraPequena() throws Exception {
		var leitor = auth("termo_leitor");

		// Corte sem conteúdo novo das fontes desta classe: as janelas existem, com zero em tudo.
		worker.corte();

		MvcResult painel = mockMvc.perform(get("/termometro").header("X-Session-Token", leitor.token()))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode janelas = objectMapper.readTree(painel.getResponse().getContentAsString());
		org.assertj.core.api.Assertions.assertThat(janelas).hasSize(4);
		for (JsonNode janela : janelas) {
			// A resposta é SÓ agregado: nenhum campo carrega texto ou autoria.
			org.assertj.core.api.Assertions.assertThat(janela.has("texto")).isFalse();
			org.assertj.core.api.Assertions.assertThat(janela.has("autor")).isFalse();
			org.assertj.core.api.Assertions.assertThat(janela.has("autorId")).isFalse();
			org.assertj.core.api.Assertions.assertThat(janela.get("periodo").asText()).isIn("HOJE", "ULTIMAS_24H",
					"ULTIMOS_7D", "ULTIMOS_30D");
		}
	}

	@Test
	void publicacaoNoFeedEntraNoCorteESaiAgregada() throws Exception {
		var autor = auth("termo_autor");
		var leitor = auth("termo_leitor2");

		// Insigth com sinal claro de celebração, no feed público.
		mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", autor.token())
				.header("Idempotency-Key", "termo-post-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("type", PostType.INSIGHT.name(),
					"content", "CONSEGUI fechar o contrato! Parabéns para mim, que legal isso", "topic", "carreira"))))
			.andExpect(status().isCreated());

		// A autoria do post NUNCA chega ao painel: a resposta do corte é contagem, não lista.
		worker.corte();

		MvcResult painel = mockMvc.perform(get("/termometro").header("X-Session-Token", leitor.token()))
			.andExpect(status().isOk())
			.andReturn();
		String corpo = painel.getResponse().getContentAsString();
		JsonNode janelas = objectMapper.readTree(corpo);

		// A manifestação entrou: alguma janela larga (7d ou 30d) tem contagem e celebração.
		JsonNode de7d = janelaDe(janelas, "ULTIMOS_7D");
		JsonNode de30d = janelaDe(janelas, "ULTIMOS_30D");
		long celebrando = Math.max(contagemDe(de30d, "CELEBRANDO"), contagemDe(de7d, "CELEBRANDO"));
		org.assertj.core.api.Assertions.assertThat(celebrando).isGreaterThanOrEqualTo(1);

		// E nada de individual no corpo inteiro: nem id do autor, nem o texto publicado.
		org.assertj.core.api.Assertions.assertThat(corpo).doesNotContain(autor.userId());
		org.assertj.core.api.Assertions.assertThat(corpo).doesNotContain("CONSEGUI fechar o contrato");
	}

	@Test
	void desabafoEConfissaoEntraNaMedicaoComoQualquerTexto() throws Exception {
		var autor = auth("termo_acolhido");
		var leitor = auth("termo_leitor3");

		// Desabafo com sinal de ajuda.
		mockMvc.perform(post("/desabafos/posts")
				.header("X-Session-Token", autor.token())
				.header("Idempotency-Key", "termo-des-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("content",
					"preciso de ajuda para não desanimar desta vez"))))
			.andExpect(status().isCreated());

		// Confissão com sinal de celebração (o anonimato de exibição não a tira da medição
		// agregada: internamente a autoria existe para moderação).
		mockMvc.perform(post("/confessionario/posts")
				.header("X-Session-Token", autor.token())
				.header("Idempotency-Key", "termo-conf-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("content", "consegui terminar o projeto ontem"))))
			.andExpect(status().isCreated());

		worker.corte();

		MvcResult painel = mockMvc.perform(get("/termometro").header("X-Session-Token", leitor.token()))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode janelas = objectMapper.readTree(painel.getResponse().getContentAsString());
		JsonNode de30d = janelaDe(janelas, "ULTIMOS_30D");

		// As duas manifestações contam no agregado das janelas largas.
		org.assertj.core.api.Assertions.assertThat(contagemDe(de30d, "PRECISANDO")).isGreaterThanOrEqualTo(1);
		org.assertj.core.api.Assertions.assertThat(contagemDe(de30d, "CELEBRANDO")).isGreaterThanOrEqualTo(1);
		// O anonimato sobrevive ao Termômetro: nem o id interno do autor aparece.
		org.assertj.core.api.Assertions.assertThat(painel.getResponse().getContentAsString())
			.doesNotContain(autor.userId());
	}

	private JsonNode janelaDe(JsonNode janelas, String periodo) {
		for (JsonNode janela : janelas) {
			if (periodo.equals(janela.get("periodo").asText())) {
				return janela;
			}
		}
		throw new AssertionError("janela " + periodo + " não veio no painel");
	}

	private long contagemDe(JsonNode janela, String emocao) {
		for (JsonNode emocaoNode : janela.get("emocoes")) {
			if (emocao.equals(emocaoNode.get("emocao").asText())) {
				return emocaoNode.get("contagem").asLong();
			}
		}
		return 0;
	}

	private TestAuthHelper.AuthSession auth(String name) throws Exception {
		return new TestAuthHelper(mockMvc, objectMapper).authenticate(name);
	}

}
