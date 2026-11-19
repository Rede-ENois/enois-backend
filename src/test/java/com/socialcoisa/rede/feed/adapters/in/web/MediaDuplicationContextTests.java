package com.socialcoisa.rede.feed.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.socialcoisa.rede.media.application.port.in.GetMediaSummariesUseCase;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "rede.rate.duplicate-window-minutes=60")
class MediaDuplicationContextTests {

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private ObjectMapper mapper;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private GetMediaSummariesUseCase midias;
	private TestAuthHelper auth;

	@BeforeEach
	void preparar() {
		auth = new TestAuthHelper(mockMvc, mapper);
	}

	@ParameterizedTest
	@ValueSource(strings = { "REQUESTED", "OBJECT_DELETED" })
	void midiaRemovidaNaoTornaOutroConteudoVisivel(String estado) throws Exception {
		var autor = auth.authenticate("dupmedia_" + estado.toLowerCase(java.util.Locale.ROOT));
		String midia = criarMidia(autor, estado);
		assertThat(midias.visibleByIds(List.of(midia))).isEmpty();
		Map<String, Object> publicacao = Map.of("type", "INSIGHT", "topic", "TECNOLOGIA", "content", "Texto sem anexo visivel.");
		String postId = mapper.readTree(enviar(autor, "/feed/posts", publicacao).andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString()).path("id").asString();
		var repetida = new java.util.HashMap<>(publicacao);
		repetida.put("mediaIds", List.of(midia));
		enviar(autor, "/feed/posts", repetida).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DUPLICATE_CONTENT"));
		String rota = "/feed/posts/" + postId + "/comments";
		enviar(autor, rota, Map.of("content", "Comentario sem anexo visivel.")).andExpect(status().isCreated());
		enviar(autor, rota, Map.of("content", "Comentario sem anexo visivel.", "mediaIds", List.of(midia)))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_CONTENT"));
		assertThat(jdbc.queryForObject("select count(*) from comments where post_id = ?", Integer.class, postId))
			.isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from posts where author_id = ?", Integer.class, autor.userId()))
			.isEqualTo(1);
	}

	@ParameterizedTest
	@ValueSource(strings = { "publicacao", "comentario" })
	void inverterMesmosAnexosNaoDistingueConteudo(String operacao) throws Exception {
		var autor = auth.authenticate("dupmedia_ordem_" + operacao);
		String primeira = criarMidia(autor, "NONE");
		String segunda = criarMidia(autor, "NONE");
		String rota = "/feed/posts";
		var corpo = new java.util.HashMap<String, Object>();
		corpo.put("content", "Mesmos anexos em outra ordem.");
		if (operacao.equals("publicacao")) {
			corpo.put("type", "INSIGHT");
			corpo.put("topic", "TECNOLOGIA");
		} else {
			String resposta = enviar(autor, rota, Map.of("type", "INSIGHT", "topic", "TECNOLOGIA", "content", "Destino do teste de anexos."))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
			rota += "/" + mapper.readTree(resposta).path("id").asString() + "/comments";
		}
		corpo.put("mediaIds", List.of(primeira, segunda));
		enviar(autor, rota, corpo).andExpect(status().isCreated());
		corpo.put("mediaIds", List.of(segunda, primeira));
		enviar(autor, rota, corpo).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DUPLICATE_CONTENT"));
	}

	private String criarMidia(TestAuthHelper.AuthSession autor, String estado) {
		String id = "dupmedia_" + UUID.randomUUID();
		jdbc.update("""
			insert into media_assets (id, owner_id, file_name, mime_type, size_bytes, purpose,
			object_key, cdn_url, status, delete_status, created_at, expires_at)
			values (?, ?, 'foto.png', 'image/png', 1024, 'POST_IMAGE', 'fixture/foto.png',
			'https://cdn.test/foto.png', 'READY', ?, ?, ?)
			""", id, autor.userId(), estado, Instant.now(), Instant.now().plusSeconds(3600));
		return id;
	}

	private ResultActions enviar(TestAuthHelper.AuthSession autor, String rota, Map<String, Object> corpo) throws Exception {
		return mockMvc.perform(post(rota).header("X-Session-Token", autor.token())
			.header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
			.content(mapper.writeValueAsString(corpo)));
	}
}
