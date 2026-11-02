package com.socialcoisa.rede.search.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.blog.application.port.out.BlogRepository;
import com.socialcoisa.rede.blog.domain.Blog;
import com.socialcoisa.rede.mentoring.application.port.out.MentorRepository;
import com.socialcoisa.rede.mentoring.domain.Mentor;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** A busca acontece no banco, antes do limite; caracteres de LIKE sao texto literal. */
@SpringBootTest
@AutoConfigureMockMvc
class CatalogSearchTests {
	@Autowired private MockMvc mockMvc;
	@Autowired private ObjectMapper mapper;
	@Autowired private BlogRepository blogs;
	@Autowired private MentorRepository mentors;
	@Autowired private UserProfileRepository profiles;

	@Test
	void blogsSearchTitleAndDescriptionBeforeLimitAndClearWithBlankQuery() throws Exception {
		String tag = "catalog" + UUID.randomUUID().toString().substring(0, 8);
		var auth = new TestAuthHelper(mockMvc, mapper);
		var first = auth.authenticate(tag + "a");
		var second = auth.authenticate(tag + "b");
		var other = auth.authenticate(tag + "c");
		Instant now = Instant.now();
		blogs.save(Blog.create("blog_" + tag + "a", first.userId(), tag + "a", "Diario " + tag,
			null, now.minusSeconds(3)));
		blogs.save(Blog.create("blog_" + tag + "b", second.userId(), tag + "b", "Outro diario",
			"Sobre " + tag, now.minusSeconds(2)));
		blogs.save(Blog.create("blog_" + tag + "c", other.userId(), tag + "c", "Sem correspondencia",
			null, now));

		JsonNode found = search("/blogs", "  " + tag.toUpperCase() + "  ", 50);
		assertThat(found.valueStream().map(item -> item.get("slug").asText()).toList())
			.containsExactly(tag + "b", tag + "a");
		assertThat(search("/blogs", tag, 1).get(0).get("slug").asText()).isEqualTo(tag + "b");
		assertThat(search("/blogs", tag + "missing", 50).isEmpty()).isTrue();
		assertThat(search("/blogs", "   ", 100).valueStream().map(item -> item.get("slug").asText()).toList())
			.contains(tag + "a", tag + "b", tag + "c");
	}

	@Test
	void mentorsSearchNamesUsernamesAndExpertiseAndKeepCursorAndExpertiseFilters() throws Exception {
		String tag = "mentor" + UUID.randomUUID().toString().substring(0, 8);
		var auth = new TestAuthHelper(mockMvc, mapper);
		var first = auth.authenticate(tag + "named");
		var second = auth.authenticate("catalog_second_" + tag);
		var other = auth.authenticate("catalog_other_" + tag);
		Instant now = Instant.now();
		String firstId = "mentor_" + tag + "a";
		String secondId = "mentor_" + tag + "b";
		mentors.save(Mentor.create(firstId, first.userId(), List.of("Java"), "Gratuito", now.minusSeconds(3)));
		mentors.save(Mentor.create(secondId, second.userId(), List.of(tag, "React"), "Gratuito", now.minusSeconds(2)));
		mentors.save(Mentor.create("mentor_" + tag + "c", other.userId(), List.of("Outra area"), "Gratuito", now));
		String username = profiles.findById(first.userId()).orElseThrow().username();

		JsonNode byName = search("/mentors", tag + "named", 20).get("items");
		assertThat(byName.get(0).get("id").asText()).isEqualTo(firstId);
		JsonNode byUsername = search("/mentors", username.toUpperCase(), 20).get("items");
		assertThat(byUsername.valueStream().map(item -> item.get("id").asText()).toList()).contains(firstId);
		JsonNode byExpertise = search("/mentors", "rEaCt", 200).get("items");
		assertThat(byExpertise.valueStream().map(item -> item.get("id").asText()).toList()).contains(secondId);

		JsonNode firstPage = search("/mentors", tag, 1);
		assertThat(firstPage.get("items").get(0).get("id").asText()).isEqualTo("mentor_" + tag + "c");
		JsonNode nextPage = mapper.readTree(mockMvc.perform(get("/mentors")
			.param("query", tag).param("limit", "1").param("cursor", firstPage.get("nextCursor").asText()))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
		assertThat(nextPage.get("items").get(0).get("id").asText()).isEqualTo(secondId);
		JsonNode combined = mapper.readTree(mockMvc.perform(get("/mentors")
			.param("query", tag).param("expertise", "Java"))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
		assertThat(combined.get("items").valueStream().map(item -> item.get("id").asText()).toList())
			.containsExactly(firstId);
		assertThat(search("/mentors", tag + "missing", 20).get("items").isEmpty()).isTrue();
		assertThat(search("/mentors", " ", 200).get("items").valueStream()
			.map(item -> item.get("id").asText()).toList()).contains(firstId, secondId);
	}

	@Test
	void wildcardCharactersAreLiteralForBothCatalogs() throws Exception {
		String tag = "literal" + UUID.randomUUID().toString().substring(0, 8);
		var owner = new TestAuthHelper(mockMvc, mapper).authenticate(tag);
		String literal = tag + "%_!";
		blogs.save(Blog.create("blog_" + tag, owner.userId(), tag, literal, null, Instant.now()));
		mentors.save(Mentor.create("mentor_" + tag, owner.userId(), List.of(literal), "Gratuito", Instant.now()));
		assertThat(search("/blogs", "%_!", 100).valueStream()
			.map(item -> item.get("slug").asText()).toList()).containsExactly(tag);
		assertThat(search("/mentors", "%_!", 200).get("items").valueStream()
			.map(item -> item.get("id").asText()).toList()).containsExactly("mentor_" + tag);
		JsonNode byExpertise = mapper.readTree(mockMvc.perform(get("/mentors").param("expertise", literal))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
		assertThat(byExpertise.get("items").valueStream().map(item -> item.get("id").asText()).toList())
			.containsExactly("mentor_" + tag);
	}

	private JsonNode search(String path, String query, int limit) throws Exception {
		return mapper.readTree(mockMvc.perform(get(path).param("query", query).param("limit", String.valueOf(limit)))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
	}
}
