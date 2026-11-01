package com.socialcoisa.rede.releasenotes.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/** The "what's new" release notes: anyone reads, only admins write, items are validated plain text. */
@SpringBootTest
@AutoConfigureMockMvc
class ReleaseNoteTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	private TestAuthHelper auth;

	@DynamicPropertySource
	static void adminAllowlist(DynamicPropertyRegistry registry) {
		registry.add("rede.admin.emails", () -> "release_admin@test.com");
	}

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
		jdbc.update("delete from release_note");
	}

	@Test
	void adminPublishesAndAnyVisitorReadsWithoutSession() throws Exception {
		mockMvc.perform(get("/novidades/atual")).andExpect(status().isNoContent());
		var admin = auth.authenticate("release_admin");

		mockMvc.perform(publish(admin.token(), item("Enquetes", "Agora da para criar enquete no feed.")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value("ativo"))
			.andExpect(jsonPath("$.items[0].title").value("Enquetes"));

		mockMvc.perform(get("/novidades/atual"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value("ativo"))
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].title").value("Enquetes"))
			.andExpect(jsonPath("$.items[0].description").value("Agora da para criar enquete no feed."))
			.andExpect(jsonPath("$.publishedAt").isNotEmpty())
			.andExpect(jsonPath("$.updatedAt").isNotEmpty());
	}

	@Test
	void regularAccountCannotPublishNorRetire() throws Exception {
		var person = auth.authenticate("release_plain_person");

		mockMvc.perform(publish(person.token(), item("Titulo", "Descricao")))
			.andExpect(status().isForbidden());
		mockMvc.perform(delete("/admin/novidades").header("X-Session-Token", person.token()))
			.andExpect(status().isForbidden());
	}

	@Test
	void itemsAreTrimmedAndDefused() throws Exception {
		var admin = auth.authenticate("release_admin");

		mockMvc.perform(publish(admin.token(), item("  Perfil​ novo  ", " Mais " + "a".repeat(30) + " ")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].title").value("Perfil novo"))
			.andExpect(jsonPath("$.items[0].description").value("Mais " + "a".repeat(12)));
	}

	@Test
	void invalidItemsFailWith400() throws Exception {
		var admin = auth.authenticate("release_admin");
		String token = admin.token();

		expectCode(publishRaw(token, Map.of()), "INVALID_RELEASE_ITEMS");
		expectCode(publishRaw(token, Map.of("items", List.of())), "INVALID_RELEASE_ITEMS");
		expectCode(publish(token, Collections.nCopies(9, item("T", "D")).toArray(Map[]::new)),
			"INVALID_RELEASE_ITEMS");
		expectCode(publish(token, item("   ", "D")), "RELEASE_ITEM_TITLE_REQUIRED");
		expectCode(publish(token, item("​", "D")), "RELEASE_ITEM_TITLE_REQUIRED");
		expectCode(publish(token, item("ab".repeat(41), "D")), "RELEASE_ITEM_TITLE_TOO_LONG");
		expectCode(publish(token, item("T", " ")), "RELEASE_ITEM_DESCRIPTION_REQUIRED");
		expectCode(publish(token, item("T", "ab".repeat(121))), "RELEASE_ITEM_DESCRIPTION_TOO_LONG");

		// Limits are inclusive: 8 items, 80-char title and 240-char description publish.
		mockMvc.perform(publish(token, Collections.nCopies(8, item("ab".repeat(40), "ab".repeat(120)))
				.toArray(Map[]::new)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(8));
	}

	@Test
	void republishKeepsPublishedAtAndBumpsUpdatedAt() throws Exception {
		var admin = auth.authenticate("release_admin");
		mockMvc.perform(publish(admin.token(), item("Versao 1", "Primeira"))).andExpect(status().isOk());

		// Backdate the row so the second publish is provably later, whatever the clock resolution.
		jdbc.update("update release_note set published_at = dateadd('DAY', -1, published_at),"
			+ " updated_at = dateadd('DAY', -1, updated_at)");
		String first = mockMvc.perform(get("/novidades/atual"))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();

		String second = mockMvc.perform(publish(admin.token(), item("Versao 2", "Segunda")))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();

		assertThat(field(second, "publishedAt")).isEqualTo(field(first, "publishedAt"));
		assertThat(Instant.parse(field(second, "updatedAt"))).isAfter(Instant.parse(field(first, "updatedAt")));
		assertThat(second).contains("Versao 2").doesNotContain("Versao 1");
		assertThat(jdbc.queryForObject("select count(*) from release_note", Integer.class)).isEqualTo(1);
	}

	@Test
	void retireTakesItDownAndIsIdempotent() throws Exception {
		var admin = auth.authenticate("release_admin");
		mockMvc.perform(publish(admin.token(), item("No ar", "Visivel"))).andExpect(status().isOk());

		mockMvc.perform(delete("/admin/novidades").header("X-Session-Token", admin.token()))
			.andExpect(status().isNoContent());
		mockMvc.perform(get("/novidades/atual")).andExpect(status().isNoContent());
		mockMvc.perform(delete("/admin/novidades").header("X-Session-Token", admin.token()))
			.andExpect(status().isNoContent());
	}

	private static Map<String, String> item(String title, String description) {
		return Map.of("title", title, "description", description);
	}

	@SafeVarargs
	private RequestBuilder publish(String token, Map<String, String>... items) {
		return publishRaw(token, Map.of("items", List.of(items)));
	}

	private RequestBuilder publishRaw(String token, Map<String, ?> body) {
		return put("/admin/novidades")
			.header("X-Session-Token", token)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(body));
	}

	private void expectCode(RequestBuilder request, String code) throws Exception {
		mockMvc.perform(request)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(code));
	}

	private String field(String json, String name) {
		return objectMapper.readTree(json).get(name).asString();
	}
}
