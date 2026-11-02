package com.socialcoisa.rede.profile.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * Cidade/UF do perfil: o dono sempre ve; terceiros so veem quando `showCity` esta ligado (nasce
 * desligado). Cidade e UF vao juntas ou nao vao.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProfileCityPrivacyTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private void patchProfile(TestAuthHelper.AuthSession who, Map<String, Object> body, String key) throws Exception {
		mockMvc.perform(patch("/me/profile")
				.header("X-Session-Token", who.token())
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(body)))
			.andExpect(status().isOk());
	}

	private String username(TestAuthHelper.AuthSession who) throws Exception {
		String json = mockMvc.perform(get("/me").header("X-Session-Token", who.token()))
			.andReturn().getResponse().getContentAsString();
		return objectMapper.readTree(json).get("username").asText();
	}

	@Test
	void cityIsPrivateUntilTheOwnerOptsIn() throws Exception {
		var user = new TestAuthHelper(mockMvc, objectMapper).authenticate("pcity-1");
		patchProfile(user, Map.of("city", "Belo Horizonte", "state", "MG"), "pcity-1a");
		String username = username(user);

		mockMvc.perform(get("/me").header("X-Session-Token", user.token()))
			.andExpect(jsonPath("$.city").value("Belo Horizonte"))
			.andExpect(jsonPath("$.state").value("MG"))
			.andExpect(jsonPath("$.showCity").value(false));
		mockMvc.perform(get("/users/" + username))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.city").doesNotExist())
			.andExpect(jsonPath("$.state").doesNotExist());

		patchProfile(user, Map.of("showCity", true), "pcity-1b");
		mockMvc.perform(get("/users/" + username))
			.andExpect(jsonPath("$.city").value("Belo Horizonte"))
			.andExpect(jsonPath("$.state").value("MG"));

		patchProfile(user, Map.of("showCity", false), "pcity-1c");
		mockMvc.perform(get("/users/" + username))
			.andExpect(jsonPath("$.city").doesNotExist());
	}

	@Test
	void refusesCityWithoutStateAndClearsWithEmptyStrings() throws Exception {
		var user = new TestAuthHelper(mockMvc, objectMapper).authenticate("pcity-2");
		mockMvc.perform(patch("/me/profile")
				.header("X-Session-Token", user.token())
				.header("Idempotency-Key", "pcity-2a")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("state", "SP"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("CITY_STATE_INCOMPLETE"));

		patchProfile(user, Map.of("city", "Recife", "state", "PE"), "pcity-2b");
		patchProfile(user, Map.of("city", "", "state", ""), "pcity-2c");
		mockMvc.perform(get("/me").header("X-Session-Token", user.token()))
			.andExpect(jsonPath("$.city").doesNotExist());
	}
}
