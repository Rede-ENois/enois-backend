package com.socialcoisa.rede.feed.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "rede.rate.duplicate-window-minutes=60")
class PostDuplicationContextTests {

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private ObjectMapper objectMapper;
	private TestAuthHelper auth;

	@BeforeEach
	void preparar() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("publicacoesDistintas")
	void mesmoTextoComConteudoEfetivoDiferentePodeSerPublicado(String caso,
			Map<String, Object> primeira, Map<String, Object> segunda) throws Exception {
		var autor = auth.authenticate("dupctx_" + caso);
		String primeiroId = objectMapper.readTree(enviar(autor, primeira)
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("id").asString();
		String segundoId = objectMapper.readTree(enviar(autor, segunda)
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("id").asString();
		assertThat(segundoId).isNotEqualTo(primeiroId);
		enviar(autor, segunda).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DUPLICATE_CONTENT"));
		assertThat(jdbc.queryForObject("select count(*) from posts where author_id = ?", Integer.class,
			autor.userId())).isEqualTo(2);
	}

	static Stream<Arguments> publicacoesDistintas() {
		return Stream.of(
			Arguments.of("artigo", Map.of("type", "ARTICLE", "content", "Meu diario",
				"articleBody", "Primeira experiencia de aprendizado."),
				Map.of("type", "ARTICLE", "content", "Meu diario", "articleBody", "Segunda experiencia de aprendizado.")),
			Arguments.of("pagina", Map.of("type", "PAGE", "content", "Meu projeto", "pageHtml", "<p>Primeira pagina</p>"),
				Map.of("type", "PAGE", "content", "Meu projeto", "pageHtml", "<p>Segunda pagina</p>")),
			Arguments.of("enquete", Map.of("type", "POLL", "content", "Qual voce prefere?", "pollOptions", java.util.List.of("A", "B")),
				Map.of("type", "POLL", "content", "Qual voce prefere?", "pollOptions", java.util.List.of("C", "D"))),
			Arguments.of("pagina_vazia", Map.of("type", "PAGE", "content", "", "pageHtml", "<p>Pagina sem titulo A</p>"),
				Map.of("type", "PAGE", "content", "", "pageHtml", "<p>Pagina sem titulo B</p>")),
			Arguments.of("tipo", Map.of("type", "INSIGHT", "content", "Uma lembranca"),
				Map.of("type", "MOMENT", "content", "Uma lembranca")));
	}

	@Autowired
	private org.springframework.transaction.PlatformTransactionManager transacoes;

	@org.junit.jupiter.api.Test
	void transacaoDesfeitaNaoReservaConteudoParaOutraTentativa() throws Exception {
		var autor = auth.authenticate("dupctx_rollback");
		Map<String, Object> corpo = Map.of("type", "INSIGHT", "content", "Publicacao desfeita pela transacao.");
		new org.springframework.transaction.support.TransactionTemplate(transacoes).executeWithoutResult(estado -> {
			try {
				enviar(autor, corpo).andExpect(status().isCreated());
			} catch (Exception falha) {
				throw new IllegalStateException(falha);
			}
			estado.setRollbackOnly();
		});
		enviar(autor, corpo).andExpect(status().isCreated());
	}

	@Autowired
	private org.springframework.jdbc.core.JdbcTemplate jdbc;

	@org.junit.jupiter.api.Test
	void mesmaLegendaComMidiasDiferentesNaoColideEmPostsNemComentarios() throws Exception {
		var autor = auth.authenticate("dupctx_midias");
		for (String midia : java.util.List.of("dupctx-imagem-a", "dupctx-imagem-b")) {
			jdbc.update("""
				insert into media_assets (id, owner_id, file_name, mime_type, size_bytes, purpose,
				object_key, cdn_url, status, delete_status, created_at, expires_at)
				values (?, ?, 'foto.png', 'image/png', 1024, 'POST_IMAGE', 'fixture/foto.png',
				'https://cdn.test/foto.png', 'READY', 'NONE', ?, ?)
				""", midia, autor.userId(), java.time.Instant.now(), java.time.Instant.now().plusSeconds(3600));
		}
		String postId = objectMapper.readTree(enviar(autor, Map.of("type", "INSIGHT", "content", "Minhas fotos",
			"mediaIds", java.util.List.of("dupctx-imagem-a"))).andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString()).path("id").asString();
		var outro = Map.<String, Object>of("type", "INSIGHT", "content", "Minhas fotos",
			"mediaIds", java.util.List.of("dupctx-imagem-b"));
		enviar(autor, outro).andExpect(status().isCreated());
		enviar(autor, outro).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DUPLICATE_CONTENT"));
		for (String midia : java.util.List.of("dupctx-imagem-a", "dupctx-imagem-b")) {
			var corpo = Map.of("mediaIds", java.util.List.of(midia));
			for (int tentativa = 0; tentativa < 2; tentativa++) {
				mockMvc.perform(post("/feed/posts/" + postId + "/comments")
					.header("X-Session-Token", autor.token()).header("Idempotency-Key", UUID.randomUUID().toString())
					.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(corpo)))
					.andExpect(tentativa == 0 ? status().isCreated() : status().isConflict());
			}
		}
	}

	@org.junit.jupiter.api.Test
	void destinosDiferentesSaoIndependentesMasMetadadosNaoContornamDuplicacao() throws Exception {
		var autor = auth.authenticate("dupctx_destinos");
		String primeira = criarDestino(autor, "/communities", Map.of("name", "Contexto primeira comunidade",
			"description", "Comunidade ficticia", "topic", "TECNOLOGIA"), 201);
		String segunda = criarDestino(autor, "/communities", Map.of("name", "Contexto segunda comunidade",
			"description", "Comunidade ficticia", "topic", "TECNOLOGIA"), 201);
		String canalA = criarDestino(autor, "/communities/" + primeira + "/channels",
			Map.of("name", "Primeiro canal", "topic", "TECNOLOGIA"), 200);
		String canalB = criarDestino(autor, "/communities/" + primeira + "/channels",
			Map.of("name", "Segundo canal", "topic", "TECNOLOGIA"), 200);
		for (var destino : java.util.List.of(Map.<String, Object>of(), Map.<String, Object>of("communityId", primeira),
			Map.<String, Object>of("communityId", segunda),
			Map.<String, Object>of("communityId", primeira, "channelId", canalA),
			Map.<String, Object>of("communityId", primeira, "channelId", canalB))) {
			var corpo = new java.util.HashMap<>(destino);
			corpo.put("type", "INSIGHT");
			corpo.put("content", "Comunicado para cada contexto.");
			enviar(autor, corpo).andExpect(status().isCreated());
			corpo.put("tags", java.util.List.of("nova-tag"));
			corpo.put("articleBody", "Ignorado fora de artigo");
			corpo.put("pageHtml", "<p>Ignorado fora de pagina</p>");
			corpo.put("pollOptions", java.util.List.of("Ignorada A", "Ignorada B"));
			corpo.put("mediaSpoiler", true);
			enviar(autor, corpo).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("DUPLICATE_CONTENT"));
		}
	}

	@org.junit.jupiter.api.Test
	void htmlDescartadoNaSanitizacaoNaoCriaOutroContexto() throws Exception {
		var autor = auth.authenticate("dupctx_sanitizado");
		enviar(autor, Map.of("type", "PAGE", "content", "", "pageHtml", "<p>Minha pagina</p>"))
			.andExpect(status().isCreated());
		enviar(autor, Map.of("type", "PAGE", "content", "", "pageHtml", "<!-- variacao descartada --><p>Minha pagina</p>"))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_CONTENT"));
		assertThat(jdbc.queryForObject("select count(*) from posts where author_id = ?", Integer.class,
			autor.userId())).isEqualTo(1);
	}

	@org.junit.jupiter.api.Test
	void artigoInvalidoNaoBloqueiaPublicacaoCorrigida() throws Exception {
		var autor = auth.authenticate("dupctx_invalido");
		enviar(autor, Map.of("type", "ARTICLE", "content", "Titulo corrigido"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ARTICLE_BODY"));
		enviar(autor, Map.of("type", "ARTICLE", "content", "Titulo corrigido", "articleBody", "Corpo valido"))
			.andExpect(status().isCreated());
	}

	private String criarDestino(TestAuthHelper.AuthSession autor, String rota, Map<String, Object> corpo,
			int statusEsperado) throws Exception {
		String resposta = mockMvc.perform(post(rota).header("X-Session-Token", autor.token())
			.header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(corpo))).andExpect(status().is(statusEsperado))
			.andReturn().getResponse().getContentAsString();
		return objectMapper.readTree(resposta).path("id").asString();
	}

	private ResultActions enviar(TestAuthHelper.AuthSession autor, Map<String, Object> corpo) throws Exception {
		var payload = new java.util.HashMap<>(corpo);
		if (!payload.containsKey("communityId")) {
			payload.put("topic", "TECNOLOGIA");
		}
		return mockMvc.perform(post("/feed/posts").header("X-Session-Token", autor.token())
			.header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(payload)));
	}
}
