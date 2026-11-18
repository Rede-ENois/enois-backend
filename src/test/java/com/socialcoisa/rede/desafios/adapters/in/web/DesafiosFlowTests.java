package com.socialcoisa.rede.desafios.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
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

import com.socialcoisa.rede.moderation.application.ReportedContentRemover;
import com.socialcoisa.rede.moderation.domain.Report;
import com.socialcoisa.rede.moderation.domain.ReportReason;
import com.socialcoisa.rede.moderation.domain.TargetType;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

/**
 * Desafios de ponta a ponta: o convite nominal do autor, a participação de texto de quem topa
 * (uma por pessoa, substituível), a torcida contada sem lista, o encerramento com reconhecimento
 * e o isolamento — desafio não é linha em `posts`, então nada vaza para /feed, perfil, diário
 * ou busca. Regras de convivência: quem convocou não participa do próprio desafio; encerrar
 * exige reconhecimento de uma participação no ar; participação fora da janela recusa.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DesafiosFlowTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private ReportedContentRemover reportedContentRemover;

	@Test
	void publicaConviteRecebeParticipacaoEEncerraComReconhecimento() throws Exception {
		var autor = auth("desafio_autor");
		var topa = auth("desafio_topa");
		var outra = auth("desafio_outra");

		// O convite: nominal, com janela de uma semana.
		MvcResult criado = mockMvc.perform(post("/desafios")
				.header("X-Session-Token", autor.token())
				.header("Idempotency-Key", "desafio-pub-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of(
					"title", "Desenhe o mascote só com formas geométricas",
					"description", "Quem topa desenhar o mascote da rede usando apenas círculos e triângulos?",
					"endsAt", Instant.now().plusSeconds(7 * 24 * 3600).toString()))))
			.andExpect(status().isCreated())
			.andReturn();
		JsonNode desafio = objectMapper.readTree(criado.getResponse().getContentAsString());
		String desafioId = desafio.get("id").asText();
		org.assertj.core.api.Assertions.assertThat(desafioId).startsWith("desafio_");
		// NOMINAL: o autor vai no JSON, com nome e @.
		org.assertj.core.api.Assertions.assertThat(desafio.get("author").get("id").asText()).isEqualTo(autor.userId());
		org.assertj.core.api.Assertions.assertThat(desafio.get("status").asText()).isEqualTo("ACTIVE");
		org.assertj.core.api.Assertions.assertThat(desafio.get("mine").asBoolean()).isTrue();

		// A participação: texto puro, nominal, uma por pessoa.
		MvcResult participou = mockMvc.perform(post("/desafios/" + desafioId + "/participacoes")
				.header("X-Session-Token", topa.token())
				.header("Idempotency-Key", "desafio-part-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of(
					"content", "Fiz o mascote com um círculo pra cabeça e três triângulos pra orelha"))))
			.andExpect(status().isCreated())
			.andReturn();
		JsonNode participacao = objectMapper.readTree(participou.getResponse().getContentAsString());
		String participacaoId = participacao.get("id").asText();
		org.assertj.core.api.Assertions.assertThat(participacao.get("mine").asBoolean()).isTrue();

		// Quem convocou NÃO participa do próprio desafio.
		mockMvc.perform(post("/desafios/" + desafioId + "/participacoes")
				.header("X-Session-Token", autor.token())
				.header("Idempotency-Key", "desafio-propria-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of("content", "participo eu mesmo"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("OWN_CHALLENGE"));

		// ISOLAMENTO — o desafio não vaza para o feed geral…
		MvcResult feedGeral = mockMvc.perform(get("/feed").param("tab", "ALL")
				.header("X-Session-Token", autor.token()))
			.andExpect(status().isOk())
			.andReturn();
		org.assertj.core.api.Assertions.assertThat(feedGeral.getResponse().getContentAsString())
			.as("desafio não vaza para o feed Tudo").doesNotContain(desafioId);

		// …nem para o perfil (posts)…
		MvcResult perfil = mockMvc.perform(get("/users/" + usernameOf(autor) + "/posts")
				.header("X-Session-Token", autor.token()))
			.andExpect(status().isOk())
			.andReturn();
		org.assertj.core.api.Assertions.assertThat(perfil.getResponse().getContentAsString())
			.as("desafio não vaza para o perfil").doesNotContain(desafioId);

		// …nem para o diário de atividade…
		MvcResult diario = mockMvc.perform(get("/users/" + usernameOf(autor) + "/diario")
				.header("X-Session-Token", autor.token()))
			.andExpect(status().isOk())
			.andReturn();
		org.assertj.core.api.Assertions.assertThat(diario.getResponse().getContentAsString())
			.as("desafio não vaza para o diário").doesNotContain(desafioId);

		// …nem para o buscador.
		MvcResult busca = mockMvc.perform(get("/search").param("q", "mascote geométrico")
				.header("X-Session-Token", autor.token()))
			.andExpect(status().isOk())
			.andReturn();
		org.assertj.core.api.Assertions.assertThat(busca.getResponse().getContentAsString())
			.as("desafio não vaza para a busca").doesNotContain(desafioId);

		// Segunda participação da mesma pessoa SUBSTITUI a primeira (uma por pessoa).
		MvcResult substituiu = mockMvc.perform(post("/desafios/" + desafioId + "/participacoes")
				.header("X-Session-Token", topa.token())
				.header("Idempotency-Key", "desafio-part2-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of("content", "refiz com círculos só"))))
			.andExpect(status().isCreated())
			.andReturn();
		String participacaoNova = objectMapper.readTree(substituiu.getResponse().getContentAsString())
			.get("id").asText();
		org.assertj.core.api.Assertions.assertThat(participacaoNova).isNotEqualTo(participacaoId);
		MvcResult mural = mockMvc.perform(get("/desafios/" + desafioId + "/participacoes")
				.header("X-Session-Token", outra.token()))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode items = objectMapper.readTree(mural.getResponse().getContentAsString()).get("items");
		org.assertj.core.api.Assertions.assertThat(items.size()).isEqualTo(1);
		org.assertj.core.api.Assertions.assertThat(items.get(0).get("id").asText()).isEqualTo(participacaoNova);
		org.assertj.core.api.Assertions.assertThat(items.get(0).get("content").asText()).contains("círculos só");

		// Torcida: soma, é uma por pessoa, e não revela quem torceu.
		mockMvc.perform(post("/desafios/participacoes/" + participacaoNova + "/torcidas")
				.header("X-Session-Token", outra.token())
				.header("Idempotency-Key", "desafio-cheer-" + UUID.randomUUID()))
			.andExpect(status().isOk())
			// A resposta é o mapa de contagens: {participacaoId: 1} — sem identidade de ninguém.
			.andExpect(jsonPath("$[\" + participacaoNova + \"]").doesNotExist());
		MvcResult torcidaMapa = mockMvc.perform(post("/desafios/participacoes/" + participacaoNova + "/torcidas")
				.header("X-Session-Token", outra.token())
				.header("Idempotency-Key", "desafio-cheer2-" + UUID.randomUUID()))
			.andExpect(status().isOk())
			.andReturn();
		org.assertj.core.api.Assertions
			.assertThat(objectMapper.readTree(torcidaMapa.getResponse().getContentAsString())
				.get(participacaoNova).asLong())
			.isEqualTo(1);
		MvcResult muralTorcida = mockMvc.perform(get("/desafios/" + desafioId + "/participacoes")
				.header("X-Session-Token", autor.token()))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode comTorcida = objectMapper.readTree(muralTorcida.getResponse().getContentAsString())
			.get("items").get(0);
		org.assertj.core.api.Assertions.assertThat(comTorcida.get("cheers").asLong()).isEqualTo(1);
		org.assertj.core.api.Assertions.assertThat(comTorcida.get("cheered").asBoolean()).isFalse();
		org.assertj.core.api.Assertions.assertThat(muralTorcida.getResponse().getContentAsString())
			.as("quem torceu não aparece em lugar nenhum").doesNotContain(outra.userId());

		// Encerrar sem reconhecimento de participação no ar recusa.
		mockMvc.perform(post("/desafios/" + desafioId + "/fechamento")
				.header("X-Session-Token", autor.token())
				.header("Idempotency-Key", "desafio-close-inv-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of("destaqueId", "desafio_p_nada"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("DESTAQUE_INVALID"));

		// Encerrar SEM SER O AUTOR recusa.
		mockMvc.perform(post("/desafios/" + desafioId + "/fechamento")
				.header("X-Session-Token", topa.token())
				.header("Idempotency-Key", "desafio-close-outro-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of("destaqueId", participacaoNova))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NOT_AUTHORIZED"));

		// O encerramento: reconhecimento nominal gravado no próprio desafio.
		MvcResult fechado = mockMvc.perform(post("/desafios/" + desafioId + "/fechamento")
				.header("X-Session-Token", autor.token())
				.header("Idempotency-Key", "desafio-close-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of("destaqueId", participacaoNova))))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode desafioFechado = objectMapper.readTree(fechado.getResponse().getContentAsString());
		org.assertj.core.api.Assertions.assertThat(desafioFechado.get("status").asText()).isEqualTo("CLOSED");
		org.assertj.core.api.Assertions.assertThat(desafioFechado.get("destaqueId").asText())
			.isEqualTo(participacaoNova);

		// Desafio fechado não recebe mais participação.
		mockMvc.perform(post("/desafios/" + desafioId + "/participacoes")
				.header("X-Session-Token", outra.token())
				.header("Idempotency-Key", "desafio-tarde-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of("content", "cheguei tarde"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("DESAFIO_FECHADO"));
	}

	@Test
	void regrasDeConviteRecusamTituloVazioDescricaoVaziaEJanelaPassada() throws Exception {
		var autor = auth("desafio_regras");

		mockMvc.perform(post("/desafios")
				.header("X-Session-Token", autor.token())
				.header("Idempotency-Key", "desafio-vazio-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of("title", "  ",
					"description", "descrição ok"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("TITLE_REQUIRED"));
		mockMvc.perform(post("/desafios")
				.header("X-Session-Token", autor.token())
				.header("Idempotency-Key", "desafio-desc-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of("title", "título ok",
					"description", "   "))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("DESCRIPTION_REQUIRED"));
		mockMvc.perform(post("/desafios")
				.header("X-Session-Token", autor.token())
				.header("Idempotency-Key", "desafio-passado-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of(
					"title", "título ok", "description", "descrição ok",
					"endsAt", Instant.now().minusSeconds(3600).toString()))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("ENDS_AT_PAST"));
		// Sem chave de idempotência nem começa.
		mockMvc.perform(post("/desafios")
				.header("X-Session-Token", autor.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of(
					"title", "título ok", "description", "descrição ok"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
		// Participação vazia recusa.
		MvcResult criado = mockMvc.perform(post("/desafios")
				.header("X-Session-Token", autor.token())
				.header("Idempotency-Key", "desafio-para-vazio-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of(
					"title", "Conte sua receita de bolo em uma linha",
					"description", "receita impossível, mas quem topa?"))))
			.andExpect(status().isCreated())
			.andReturn();
		String desafioId = objectMapper.readTree(criado.getResponse().getContentAsString()).get("id").asText();
		var outra = auth("desafio_regras_outra");
		mockMvc.perform(post("/desafios/" + desafioId + "/participacoes")
				.header("X-Session-Token", outra.token())
				.header("Idempotency-Key", "desafio-vazio2-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of("content", " "))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("CONTENT_REQUIRED"));
	}

	@Test
void bloqueioPaginacaoEBloqueioAndam() throws Exception {
		var leitor = auth("desafio_leitor");
		var bloqueado = auth("desafio_bloqueado");
		String ida = publish(bloqueado, "Desafio de quem foi bloqueado", "não vou ver isso");
		String minha = publish(leitor, "Meu próprio desafio", "este eu vejo");

		// Página curta: 1 por página e cursor para a próxima (sem ranking: ordem cronológica).
		MvcResult primeira = mockMvc.perform(get("/desafios").param("limit", "1")
				.header("X-Session-Token", leitor.token()))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode pagina = objectMapper.readTree(primeira.getResponse().getContentAsString());
		org.assertj.core.api.Assertions.assertThat(pagina.get("items").size()).isEqualTo(1);
		org.assertj.core.api.Assertions.assertThat(pagina.get("nextCursor").isTextual()).isTrue();
		MvcResult segunda = mockMvc.perform(get("/desafios").param("limit", "1")
				.param("cursor", pagina.get("nextCursor").asText())
				.header("X-Session-Token", leitor.token()))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode paginaSeguinte = objectMapper.readTree(segunda.getResponse().getContentAsString());
		org.assertj.core.api.Assertions.assertThat(paginaSeguinte.get("items").size()).isEqualTo(1);
		org.assertj.core.api.Assertions.assertThat(pagina.get("items").get(0).get("id").asText())
			.isNotEqualTo(paginaSeguinte.get("items").get(0).get("id").asText());

		// Bloqueio: o desafio de quem foi bloqueado some do feed de quem bloqueou…
		mockMvc.perform(post("/network/blocks/" + bloqueado.userId())
				.header("X-Session-Token", leitor.token())
				.header("Idempotency-Key", "desafio-block-" + UUID.randomUUID()))
			.andExpect(status().isCreated());
		MvcResult depoisBloqueio = mockMvc.perform(get("/desafios")
				.header("X-Session-Token", leitor.token()))
			.andExpect(status().isOk())
			.andReturn();
		org.assertj.core.api.Assertions.assertThat(depoisBloqueio.getResponse().getContentAsString())
			.doesNotContain(ida).contains(minha);

		// …mas continua visível para quem não tem bloqueio nenhum.
		var alheio = auth("desafio_alheio");
		MvcResult olhoNeutro = mockMvc.perform(get("/desafios")
				.header("X-Session-Token", alheio.token()))
			.andExpect(status().isOk())
			.andReturn();
		org.assertj.core.api.Assertions.assertThat(olhoNeutro.getResponse().getContentAsString()).contains(ida);
	}

	@Test
	void denunciaConfirmadaDerrubaDesafioEParticipacao() throws Exception {
		var autor = auth("desafio_mautor");
		var visitante = auth("desafio_mvisitor");
		String desafioId = publish(autor, "Desafio que vai ser denunciado", "convite problemático");
		String participacaoId = participate(visitante, desafioId, "participação que cai junto");

		// Denúncia confirmada no DESAFIO: o convite sai e as participações caem com ele.
		Report report = Report.create("report_desafio_" + UUID.randomUUID().toString().replace("-", ""),
			visitante.userId(), TargetType.DESAFIO_POST, desafioId, ReportReason.SPAM, "spam", Instant.now());
		reportedContentRemover.remove(report, "moderador_1", "TESTE");
		mockMvc.perform(get("/desafios/" + desafioId)
				.header("X-Session-Token", autor.token()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("DESAFIO_NOT_FOUND"));
		MvcResult feed = mockMvc.perform(get("/desafios").header("X-Session-Token", autor.token()))
			.andExpect(status().isOk())
			.andReturn();
		org.assertj.core.api.Assertions.assertThat(feed.getResponse().getContentAsString()).doesNotContain(desafioId);

		// Denúncia confirmada na PARTICIPAÇÃO: ela sai e o desafio continua no ar.
		String desafioId2 = publish(autor, "Desafio que permanece", "convite que fica");
		String participacaoId2 = participate(visitante, desafioId2, "participação denunciada sozinha");
		Report report2 = Report.create("report_desafio2_" + UUID.randomUUID().toString().replace("-", ""),
			autor.userId(), TargetType.DESAFIO_PARTICIPACAO, participacaoId2, ReportReason.SPAM, "spam",
			Instant.now());
		reportedContentRemover.remove(report2, "moderador_1", "TESTE");
		MvcResult mural = mockMvc.perform(get("/desafios/" + desafioId2 + "/participacoes")
				.header("X-Session-Token", autor.token()))
			.andExpect(status().isOk())
			.andReturn();
		org.assertj.core.api.Assertions.assertThat(mural.getResponse().getContentAsString())
			.doesNotContain(participacaoId).doesNotContain(participacaoId2);
		mockMvc.perform(get("/desafios/" + desafioId2)
				.header("X-Session-Token", autor.token()))
			.andExpect(status().isOk());
	}

	@Test
	void soQuemConvocouRemoveELeituraExigeSessao() throws Exception {
		var autor = auth("desafio_dautor");
		var visitante = auth("desafio_dvisitor");
		String desafioId = publish(autor, "Desafio que eu mesma vou remover", "não pegou");

		mockMvc.perform(delete("/desafios/" + desafioId)
				.header("X-Session-Token", visitante.token())
				.header("Idempotency-Key", "desafio-del-outro-" + UUID.randomUUID()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NOT_AUTHORIZED"));
		mockMvc.perform(delete("/desafios/" + desafioId)
				.header("X-Session-Token", autor.token())
				.header("Idempotency-Key", "desafio-del-" + UUID.randomUUID()))
			.andExpect(status().isOk());
		mockMvc.perform(get("/desafios/" + desafioId)
				.header("X-Session-Token", autor.token()))
			.andExpect(status().isNotFound());

		// Espaço de convivência, não página pública: sem sessão não há leitura nem escrita.
		mockMvc.perform(get("/desafios")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/desafios/qualquer")).andExpect(status().isUnauthorized());
		mockMvc.perform(post("/desafios").contentType(MediaType.APPLICATION_JSON)
				.content("{\"title\":\"x\",\"description\":\"y\"}"))
			.andExpect(status().isUnauthorized());
	}

	private String publish(TestAuthHelper.AuthSession autor, String title, String description) throws Exception {
		MvcResult criado = mockMvc.perform(post("/desafios")
				.header("X-Session-Token", autor.token())
				.header("Idempotency-Key", "desafio-flow-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of("title", title, "description", description))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(criado.getResponse().getContentAsString()).get("id").asText();
	}

	private String participate(TestAuthHelper.AuthSession session, String desafioId, String content)
			throws Exception {
		MvcResult criado = mockMvc.perform(post("/desafios/" + desafioId + "/participacoes")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "desafio-flow-p-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of("content", content))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(criado.getResponse().getContentAsString()).get("id").asText();
	}

	private String usernameOf(TestAuthHelper.AuthSession session) throws Exception {
		var result = mockMvc.perform(get("/me")
				.header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString())
			.get("username").asText();
	}

	private TestAuthHelper.AuthSession auth(String name) throws Exception {
		return new TestAuthHelper(mockMvc, objectMapper).authenticate(name);
	}
}
