package com.socialcoisa.rede.communities.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Comunidades por cidade: cidade + UF opcionais (os dois ou nenhum), filtro sem acento e o
 * `/communities/local`, que ordena pela cidade do perfil e depois pelo estado. Cada teste usa uma
 * UF propria e nomes com prefixo para nao depender do resto da base compartilhada.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LocalCommunitiesTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private TestAuthHelper auth() {
		return new TestAuthHelper(mockMvc, objectMapper);
	}

	private String create(TestAuthHelper.AuthSession owner, String name, String city, String state, String key)
			throws Exception {
		Map<String, Object> body = new HashMap<>();
		body.put("name", name);
		if (city != null) {
			body.put("city", city);
			body.put("state", state);
		}
		String json = mockMvc.perform(post("/communities")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(body)))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		return objectMapper.readTree(json).get("id").asText();
	}

	private void join(TestAuthHelper.AuthSession who, String communityId, String key) throws Exception {
		mockMvc.perform(post("/communities/" + communityId + "/members")
				.header("X-Session-Token", who.token())
				.header("Idempotency-Key", key))
			.andExpect(status().isOk());
	}

	private void setProfilePlace(TestAuthHelper.AuthSession who, String city, String state, String key)
			throws Exception {
		mockMvc.perform(patch("/me/profile")
				.header("X-Session-Token", who.token())
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("city", city, "state", state))))
			.andExpect(status().isOk());
	}

	private List<String> ids(String json, List<String> mine) throws Exception {
		List<String> ids = new ArrayList<>();
		for (JsonNode item : objectMapper.readTree(json).get("items")) {
			if (mine.contains(item.get("id").asText())) {
				ids.add(item.get("id").asText());
			}
		}
		return ids;
	}

	@Test
	void createsWithCityAndStateAndReturnsThem() throws Exception {
		var owner = auth().authenticate("lc-create");
		mockMvc.perform(post("/communities")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "lc-create-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", "lc Devs de BH", "city", " Belo Horizonte ",
					"state", "mg"))))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.city").value("Belo Horizonte"))
			.andExpect(jsonPath("$.state").value("MG"));
	}

	@Test
	void refusesOnlyOneOfCityAndStateAndAnUnknownState() throws Exception {
		var owner = auth().authenticate("lc-invalid");
		mockMvc.perform(post("/communities")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "lc-invalid-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", "lc so cidade", "city", "Recife"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("CITY_STATE_INCOMPLETE"));
		mockMvc.perform(post("/communities")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "lc-invalid-2")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", "lc uf ruim", "city", "Recife",
					"state", "XX"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_STATE"));
	}

	@Test
	void filterMatchesTheCityIgnoringAccentsAndCase() throws Exception {
		var owner = auth().authenticate("lc-filter");
		String sp = create(owner, "lc Devs de Sampa", "São Paulo", "SP", "lc-filter-1");
		String campinas = create(owner, "lc Devs de Campinas", "Campinas", "SP", "lc-filter-2");
		List<String> mine = List.of(sp, campinas);

		String byCity = mockMvc.perform(get("/communities").param("city", "sao paulo").param("state", "sp")
				.param("limit", "50"))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat(ids(byCity, mine)).containsExactly(sp);

		String byState = mockMvc.perform(get("/communities").param("state", "SP").param("limit", "50"))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat(ids(byState, mine)).containsExactlyInAnyOrder(sp, campinas);

		mockMvc.perform(get("/communities").param("city", "Campinas"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("CITY_STATE_INCOMPLETE"));
	}

	@Test
	void editKeepsWhenAbsentAndClearsWithEmptyStrings() throws Exception {
		var owner = auth().authenticate("lc-edit");
		String id = create(owner, "lc Edit", "Natal", "RN", "lc-edit-1");
		mockMvc.perform(patch("/communities/" + id)
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "lc-edit-2")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("description", "nova"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.city").value("Natal"));
		mockMvc.perform(patch("/communities/" + id)
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "lc-edit-3")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("city", "", "state", ""))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.city").doesNotExist());
	}

	@Test
	void localListsTheViewerCityFirstThenTheStateByMembers() throws Exception {
		var viewer = auth().authenticate("lc-local-viewer");
		var a = auth().authenticate("lc-local-a");
		var b = auth().authenticate("lc-local-b");
		// Uberlandia (mesmo estado, mais membros) nao passa na frente de quem e da cidade do perfil.
		String bhSmall = create(a, "lc BH pequena", "Belo Horizonte", "MG", "lc-local-1");
		String bhBig = create(a, "lc BH grande", "belo horizonte", "MG", "lc-local-2");
		String udi = create(a, "lc Uberlandia", "Uberlândia", "MG", "lc-local-3");
		String rio = create(a, "lc Rio", "Rio de Janeiro", "RJ", "lc-local-4");
		join(b, bhBig, "lc-local-j1");
		join(b, udi, "lc-local-j2");
		join(viewer, udi, "lc-local-j3");
		setProfilePlace(viewer, "Belo Horizonte", "MG", "lc-local-p1");

		String json = mockMvc.perform(get("/communities/local").header("X-Session-Token", viewer.token())
				.param("limit", "50"))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat(ids(json, List.of(bhSmall, bhBig, udi, rio))).containsExactly(bhBig, bhSmall, udi);
	}

	@Test
	void localIsEmptyWithoutACityOnTheProfileAndNeedsASession() throws Exception {
		var viewer = auth().authenticate("lc-local-empty");
		mockMvc.perform(get("/communities/local").header("X-Session-Token", viewer.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items").isEmpty());
		mockMvc.perform(get("/communities/local"))
			.andExpect(status().isUnauthorized());
	}
}
