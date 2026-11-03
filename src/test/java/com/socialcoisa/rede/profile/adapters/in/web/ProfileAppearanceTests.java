package com.socialcoisa.rede.profile.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Aparência da vitrine: o visual que o dono escolhe para quem VISITA o perfil.
 *
 * O que este teste protege: a escolha volta nas duas projeções (a do dono, que alimenta o
 * formulário, e a pública, que é a que aparece na tela), social e profissional são
 * independentes, limpar devolve o padrão e valor fora da lista fechada é recusado com 400 —
 * nunca gravado.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProfileAppearanceTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	private static Map<String, Object> look(String theme, String accent, String font, String header,
			Boolean textures, String borders, Integer decoration) {
		Map<String, Object> look = new LinkedHashMap<>();
		look.put("theme", theme);
		look.put("accent", accent);
		look.put("font", font);
		look.put("header", header);
		look.put("textures", textures);
		look.put("borders", borders);
		look.put("decoration", decoration);
		return look;
	}

	private static Map<String, Object> appearance(Map<String, Object> social, Map<String, Object> professional) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("social", social);
		body.put("professional", professional);
		return body;
	}

	@Test
	void savesBothLooksAndReadsThemBackInBothViews() throws Exception {
		var user = auth.authenticate("user_look1");

		mockMvc.perform(put("/me/profile-appearance")
				.header("X-Session-Token", user.token())
				.header("Idempotency-Key", "look-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(appearance(
					look("arcade-8bit", "#ff00aa", "mono", "solid", true, "square", 2),
					look("workbench", null, "serif", "clean", false, "rounded", 0)))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.social.theme").value("arcade-8bit"))
			.andExpect(jsonPath("$.professional.theme").value("workbench"));

		// Dono: o formulário de edição lê os dois lados daqui.
		MvcResult me = mockMvc.perform(get("/me").header("X-Session-Token", user.token()))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode mine = objectMapper.readTree(me.getResponse().getContentAsString());
		assertThat(mine.get("profileAppearance").get("social").get("theme").asText()).isEqualTo("arcade-8bit");
		assertThat(mine.get("profileAppearance").get("professional").get("theme").asText()).isEqualTo("workbench");

		// Público: a projeção que a tela do perfil usa carrega só o lado SOCIAL.
		mockMvc.perform(get("/users/" + mine.get("username").asText()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.profileLook.theme").value("arcade-8bit"))
			.andExpect(jsonPath("$.profileLook.accent").value("#ff00aa"));
	}

	@Test
	void changingOneSideLeavesTheOtherAlone() throws Exception {
		var user = auth.authenticate("user_look2");

		mockMvc.perform(put("/me/profile-appearance")
				.header("X-Session-Token", user.token())
				.header("Idempotency-Key", "look-2a")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(appearance(
					look("swing-metal", "#123456", "sans", "gradient", true, "rounded", 1),
					look("terminal-green", null, "mono", "solid", false, "square", 2)))))
			.andExpect(status().isOk());

		// Só o social muda; o profissional vai como está.
		mockMvc.perform(put("/me/profile-appearance")
				.header("X-Session-Token", user.token())
				.header("Idempotency-Key", "look-2b")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(appearance(
					look("web2000", "#abcdef", "sans", "solid", true, "rounded", 0),
					look("terminal-green", null, "mono", "solid", false, "square", 2)))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.social.theme").value("web2000"))
			.andExpect(jsonPath("$.professional.theme").value("terminal-green"));
	}

	@Test
	void emptyBodyClearsBothLooks() throws Exception {
		var user = auth.authenticate("user_look3");

		mockMvc.perform(put("/me/profile-appearance")
				.header("X-Session-Token", user.token())
				.header("Idempotency-Key", "look-3a")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(appearance(
					look("win95", "#123456", "mono", "solid", true, "square", 2), null))))
			.andExpect(status().isOk());

		mockMvc.perform(put("/me/profile-appearance")
				.header("X-Session-Token", user.token())
				.header("Idempotency-Key", "look-3b")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(appearance(null, null))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.social.theme").doesNotExist())
			.andExpect(jsonPath("$.professional.theme").doesNotExist());
	}

	@Test
	void refusesThemeOutsideTheCatalog() throws Exception {
		var user = auth.authenticate("user_look4");

		mockMvc.perform(put("/me/profile-appearance")
				.header("X-Session-Token", user.token())
				.header("Idempotency-Key", "look-4")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(appearance(
					look("tema-inventado", null, null, null, null, null, null), null))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_PROFILE_THEME"));
	}

	@Test
	void refusesAccentThatIsNotHex() throws Exception {
		var user = auth.authenticate("user_look5");

		mockMvc.perform(put("/me/profile-appearance")
				.header("X-Session-Token", user.token())
				.header("Idempotency-Key", "look-5")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(appearance(
					look(null, "red; background:url(x)", null, null, null, null, null), null))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_PROFILE_ACCENT"));
	}

	@Test
	void requiresASession() throws Exception {
		mockMvc.perform(put("/me/profile-appearance")
				.header("Idempotency-Key", "look-6")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(appearance(null, null))))
			.andExpect(status().isUnauthorized());
	}
}
