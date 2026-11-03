package com.socialcoisa.rede.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.ObjectMapper;

/** Links rendered as {@code href} only carry http, https or mailto, however the scheme is disguised. */
@SpringBootTest
@AutoConfigureMockMvc
class LinkSchemeAllowlistTests {

	private static final List<String> DISGUISED_SCRIPT_URLS = List.of(
		"javascript:alert(1)",
		"  JaVaScRiPt:alert(1)",
		"java\tscript:alert(1)",
		"java\nscript:alert(1)",
		"\u0001javascript:alert(1)",
		"java​script:alert(1)",
		"java\\script:alert(1)",
		"vbscript:msgbox(1)",
		"data:text/html,<script>alert(1)</script>");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void linkTargetAllowsOnlyHttpHttpsMailtoOrNoScheme() {
		for (String url : DISGUISED_SCRIPT_URLS) {
			assertThat(SafeUrl.isSafeLinkTarget(url)).as(url).isFalse();
		}
		for (String url : List.of("https://enois.ia.br", "http://x.test/a?b=c", "mailto:a@b.test", "/perfil/ana",
				"#topo", "")) {
			assertThat(SafeUrl.isSafeLinkTarget(url)).as(url).isTrue();
		}
	}

	@Test
	void companyWebsiteRejectsScriptSchemes() throws Exception {
		var owner = new TestAuthHelper(mockMvc, objectMapper).authenticate("linkscheme_owner");
		MvcResult created = mockMvc.perform(post("/empresas")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "linkscheme-company")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", "Link Scheme Ltda",
					"description", "Empresa de teste.", "sector", "Tecnologia", "size", "1-10",
					"location", "Remoto"))))
			.andExpect(status().isOk())
			.andReturn();
		var company = objectMapper.readTree(created.getResponse().getContentAsString());
		String companyId = company.get("id").asString();

		int attempt = 0;
		for (String website : DISGUISED_SCRIPT_URLS) {
			mockMvc.perform(patch("/empresas/" + companyId)
					.header("X-Session-Token", owner.token())
					.header("Idempotency-Key", "linkscheme-patch-" + attempt++)
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(Map.of("name", "Link Scheme Ltda", "website", website))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_URL"));
		}
		mockMvc.perform(patch("/empresas/" + companyId)
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "linkscheme-patch-ok")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", "Link Scheme Ltda",
					"website", "https://linkscheme.test"))))
			.andExpect(status().isOk());

		mockMvc.perform(get("/empresa/" + company.get("slug").asString()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.website").value("https://linkscheme.test"));

		// Rows stored before the allowlist are neutralized by the migration (re-run here on a planted row).
		for (String unsafe : DISGUISED_SCRIPT_URLS) {
			if (unsafe.contains("\u200b") || unsafe.contains("\\")) {
				continue; // not a scheme for a browser either: the link resolves as a relative path
			}
			assertThat(websiteAfterMigration(companyId, unsafe)).as(unsafe).isNull();
		}
		for (String unsafe : List.of("javascript:1/alert(1)//", "javascript:1?alert(1):0", "\u0001 javascript:alert(1)",
				"FILE:///etc/passwd", "  \u001f\tvbscript:x")) {
			assertThat(websiteAfterMigration(companyId, unsafe)).as(unsafe).isNull();
		}
		// Scheme-less values, host:port and allowed schemes stay.
		for (String kept : List.of("meusite.com", "meusite.com:8080", "meusite.com:8080/a", "localhost:3000",
				"www.site.com/a:b", "HTTPS://X.TEST", "mailto:a@b.test", "Data e hora",
				"https://x.test/?q=javascript:1")) {
			assertThat(websiteAfterMigration(companyId, kept)).as(kept).isEqualTo(kept);
		}
	}

	private String websiteAfterMigration(String companyId, String website) {
		jdbc.update("update companies set website = ? where id = ?", website, companyId);
		jdbc.execute((java.sql.Connection connection) -> {
			ScriptUtils.executeSqlScript(connection,
				new ClassPathResource("db/migration/V180__neutralize_unsafe_link_schemes.sql"));
			return null;
		});
		return jdbc.queryForObject("select website from companies where id = ?", String.class, companyId);
	}

	@Test
	void markdownLinksInAnyBodyFieldRejectScriptSchemes() throws Exception {
		var author = new TestAuthHelper(mockMvc, objectMapper).authenticate("linkscheme_author");
		int attempt = 0;
		for (String url : DISGUISED_SCRIPT_URLS) {
			if (url.contains("\n")) {
				continue; // the front's parser never spans lines
			}
			String target = url.replaceAll("\\(.*", ""); // the destination ends at the first `)`
			mockMvc.perform(post("/feed/posts")
					.header("X-Session-Token", author.token())
					.header("Idempotency-Key", "linkscheme-post-" + attempt++)
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA", "type", "INSIGHT",
						"content", "veja [aqui](" + target + ") e ![img](" + target + ")", "tags", List.of(),
						"mediaIds", List.of()))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("UNSAFE_LINK"));
		}

		// A JSON escape cannot hide the scheme: the check runs on decoded strings.
		mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "linkscheme-post-escaped")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"topic\":\"TECNOLOGIA\",\"type\":\"INSIGHT\",\"content\":\"[x](\\u006aavascript:alert(1))\",\"tags\":[],"
					+ "\"mediaIds\":[]}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("UNSAFE_LINK"));

		mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "linkscheme-post-ok")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA", "type", "INSIGHT",
					"content", "Data: amanha. Leia [o guia](https://enois.ia.br/guia) ou [escreva](mailto:a@b.test)",
					"tags", List.of(), "mediaIds", List.of()))))
			.andExpect(status().isCreated());
	}
}
