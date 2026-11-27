package com.socialcoisa.rede.portfolio.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestAuthHelper.AuthSession;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A lista pública de vitrines (`GET /portfolio`): só o que está no ar, sem rascunho, sem conta
 * banida e com o que a lista precisa saber — o título da página e a autoria.
 *
 * <p>A base é compartilhada com o resto da suíte, então cada caso olha só as contas que criou, e os
 * casos de paginação trabalham com páginas pequenas em vez de com a lista inteira.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class PortfolioCatalogTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	private String usernameOf(AuthSession session) {
		return jdbc.queryForObject("select username from users where id = ?", String.class, session.userId());
	}

	/** Cria a conta com a página que quiser e devolve a sessão; publicar fica com o caso. */
	private AuthSession account(String handle) throws Exception {
		return auth.authenticate(handle);
	}

	private void saveDraft(AuthSession session, String html) throws Exception {
		mockMvc.perform(put("/me/portfolio/draft")
			.header("X-Session-Token", session.token())
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("html", html))))
			.andExpect(status().isOk());
	}

	private void publish(AuthSession session) throws Exception {
		mockMvc.perform(post("/me/portfolio/publish").header("X-Session-Token", session.token()))
			.andExpect(status().isOk());
	}

	private void publishedWith(AuthSession session, String html) throws Exception {
		saveDraft(session, html);
		publish(session);
	}

	private JsonNode list(String cursor, int limit) throws Exception {
		var request = get("/portfolio").param("limit", String.valueOf(limit));
		if (cursor != null) {
			request.param("cursor", cursor);
		}
		return objectMapper.readTree(mockMvc.perform(request)
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString());
	}

	private static List<String> usernamesOf(JsonNode page) {
		List<String> usernames = new ArrayList<>();
		page.path("items").forEach(item -> usernames.add(item.path("username").asText()));
		return usernames;
	}

	private static JsonNode itemOf(JsonNode page, String username) {
		for (JsonNode item : page.path("items")) {
			if (username.equals(item.path("username").asText())) {
				return item;
			}
		}
		return null;
	}

	@Test
	void aListaEDoPublicoETrazOTituloDaPaginaComAAutoria() throws Exception {
		var owner = account("pf_lista_vitrine");
		publishedWith(owner, "<h1>Design de produto</h1><p>texto que nao vai na lista</p>");
		String username = usernameOf(owner);

		// Sem sessão: a lista é aberta a quem visita, como a leitura por username.
		JsonNode page = list(null, 50);
		JsonNode item = itemOf(page, username);

		assertThat(item).as("a própria vitrine publicada aparece").isNotNull();
		assertThat(item.path("name").asText()).isEqualTo(
			jdbc.queryForObject("select name from users where id = ?", String.class, owner.userId()));
		assertThat(item.path("version").asInt()).isEqualTo(1);
		assertThat(item.path("title").asText()).isEqualTo("Design de produto");
		assertThat(item.path("publishedAt").asText()).isNotBlank();
		// Só o título: HTML e peças continuam na leitura por username.
		assertThat(item.has("html")).isFalse();
		assertThat(item.has("blocks")).isFalse();
	}

	@Test
	void rascunhoNaoEntraNaLista() throws Exception {
		var owner = account("pf_lista_rascunho");
		saveDraft(owner, "<h1>Ainda editando</h1>");

		JsonNode page = list(null, 50);
		assertThat(usernamesOf(page)).doesNotContain(usernameOf(owner));
	}

	@Test
	void vitrineDeContaBanidaSaiDaLista() throws Exception {
		var owner = account("pf_lista_banido");
		publishedWith(owner, "<h1>Vitrine de conta banida</h1>");
		String username = usernameOf(owner);
		assertThat(usernamesOf(list(null, 50))).contains(username);

		jdbc.update("""
			insert into user_sanctions (id, user_id, type, reason, status, created_at)
			values (?, ?, 'BAN', 'Banimento de teste da moderação.', 'ACTIVE', now())
			""", "san_pf_lista_banido", owner.userId());

		assertThat(usernamesOf(list(null, 50))).doesNotContain(username);
	}

	@Test
	void tituloVemDaVersaoNoArENaoDoRascunho() throws Exception {
		var owner = account("pf_lista_rascunho");
		publishedWith(owner, "<h1>No ar</h1>");
		// O dono continua editando: o rascunho é privado e o público segue na versão 1.
		saveDraft(owner, "<h1>Rascunho</h1>");

		JsonNode item = itemOf(list(null, 50), usernameOf(owner));
		assertThat(item).isNotNull();
		assertThat(item.path("version").asInt()).isEqualTo(1);
		assertThat(item.path("title").asText()).isEqualTo("No ar");
		mockMvc.perform(get("/portfolio/{username}", usernameOf(owner)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.html").value("<h1>No ar</h1>"));
	}

	@Test
	void republicarAtualizaATituloEOContadorDeVersao() throws Exception {
		var owner = account("pf_lista_versao");
		publishedWith(owner, "<h1>Primeira versao</h1>");
		publishedWith(owner, "<h1>Segunda versao</h1>");

		JsonNode item = itemOf(list(null, 50), usernameOf(owner));
		assertThat(item).isNotNull();
		assertThat(item.path("version").asInt()).isEqualTo(2);
		assertThat(item.path("title").asText()).isEqualTo("Segunda versao");
	}

	@Test
	void paginaVaziaTemListaVaziaENenhumCursor() throws Exception {
		// Uma chave anterior a qualquer publicação possível: o banco não tem nada depois dela.
		JsonNode page = objectMapper.readTree(mockMvc.perform(get("/portfolio")
			.param("limit", "5")
			.param("cursor", "2000-01-01T00:00:00Z"))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString());

		assertThat(page.path("items")).isEmpty();
		assertThat(page.path("nextCursor").isNull()).isTrue();
	}

	@Test
	void caminharPelasPaginasPassaUmaVezPorCadaVitrine() throws Exception {
		var first = account("pf_lista_pagina_a");
		var second = account("pf_lista_pagina_b");
		var third = account("pf_lista_pagina_c");
		publishedWith(first, "<h1>Pagina A</h1>");
		publishedWith(second, "<h2>Pagina B</h2>");
		publishedWith(third, "<h1>Pagina C</h1>");

		List<String> seen = new ArrayList<>();
		String cursor = null;
		int guard = 0;
		do {
			// Página de 1: cada passo anda exatamente uma vitrine da lista.
			JsonNode page = list(cursor, 1);
			seen.addAll(usernamesOf(page));
			cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asText();
			assertThat(++guard).as("a lista termina").isLessThan(20_000);
		}
		while (cursor != null);

		List<String> mine = List.of(usernameOf(first), usernameOf(second), usernameOf(third));
		assertThat(seen).doesNotHaveDuplicates().containsAll(mine);
	}

	@Test
	void cursorInvalidoERecusado() throws Exception {
		mockMvc.perform(get("/portfolio").param("cursor", "nao-e-cursor"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
	}

	@Test
	void limiteForaDaFaixaNaoEstouraALista() throws Exception {
		// O limit é limitado no serviço: um valor absurdo não vira uma varredura enorme.
		JsonNode page = list(null, 100_000);
		assertThat(page.path("items").size()).isLessThanOrEqualTo(50);

		JsonNode zero = list(null, 0);
		assertThat(zero.path("items").size()).isLessThanOrEqualTo(1);
	}
}