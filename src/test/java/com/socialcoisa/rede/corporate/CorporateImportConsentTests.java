package com.socialcoisa.rede.corporate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.ObjectMapper;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

/**
 * A regra da dona: o perfil profissional NÃO é cópia automática do social. A importação mostra
 * a prévia e só aplica os campos escolhidos explicitamente — nada em bloco, nada por padrão.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CorporateImportConsentTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	private void createProfile(TestAuthHelper.AuthSession session, String username) throws Exception {
		mockMvc.perform(post("/me/business-profile")
				.header("X-Session-Token", session.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of(
					"username", username, "name", "Nome Profissional"))))
			.andExpect(status().isOk());
	}

	@Test
	void bannerHtmlRoundTripsThroughTheProfessionalProfile() throws Exception {
		var session = auth.authenticate("corporate_banner_html");
		createProfile(session, "capa.html");

		mockMvc.perform(patch("/me/business-profile")
				.header("X-Session-Token", session.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of(
					"headline", "Headline", "bannerHtml", "<div style=\"color:red\">capa</div>"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.bannerHtml").value("<div style=\"color:red\">capa</div>"));

		mockMvc.perform(get("/me/business-profile").header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.bannerHtml").value("<div style=\"color:red\">capa</div>"));
	}

	@Test
	void previewShowsSocialFieldsWithoutWritingAnything() throws Exception {
		var session = auth.authenticate("import_preview");
		mockMvc.perform(patch("/me/profile")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "imp-social-setup")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of(
					"headline", "Headline do social", "bio", "Bio do social", "name", "Nome Social"))))
			.andExpect(status().isOk());
		createProfile(session, "import.preview");

		mockMvc.perform(get("/me/business-profile")
				.header("X-Session-Token", session.token()))
			.andExpect(jsonPath("$.headline").value(""));

		// A prévia mostra o que viria — e não grava nada.
		mockMvc.perform(get("/me/business-profile/import-preview").header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.headline").value("Headline do social"))
			.andExpect(jsonPath("$.bio").value("Bio do social"));

		mockMvc.perform(get("/me/business-profile").header("X-Session-Token", session.token()))
			.andExpect(jsonPath("$.headline").value(""))
			.andExpect(jsonPath("$.bio").value(""));
	}

	@Test
	void onlyTheChosenFieldsAreApplied() throws Exception {
		var session = auth.authenticate("import_consent");
		mockMvc.perform(patch("/me/profile")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "imp-consent-setup")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of(
					"headline", "Headline do social", "bio", "Bio do social", "name", "Nome Social"))))
			.andExpect(status().isOk());
		createProfile(session, "import.consent");

		// Só a headline foi escolhida: a bio continua vazia, mesmo existindo no social.
		mockMvc.perform(post("/me/business-profile/import")
				.header("X-Session-Token", session.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of(
					"fields", java.util.List.of("headline")))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.headline").value("Headline do social"))
			.andExpect(jsonPath("$.bio").value(""))
			.andExpect(jsonPath("$.name").value("Nome Profissional"));
	}

	/**
	 * A capa em HTML é IMPORTÁVEL. O campo é escolhido pelo nome que a prévia mostra
	 * (`bannerHtml`), e a lista de campos aceitos é comparada sem diferenciar maiúsculas — sem
	 * isso o pedido caía em "campo desconhecido" justamente na capa.
	 */
	@Test
	void bannerHtmlIsImportedWhenChosen() throws Exception {
		var session = auth.authenticate("import_banner_html");
		mockMvc.perform(patch("/me/profile")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "imp-banner-setup")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of(
					"username", "capa.social",
					"headline", "Headline do social",
					"bannerHtml", "<section class=\"capa\">capa do social</section>"))))
			.andExpect(status().isOk());
		createProfile(session, "import.banner");

		mockMvc.perform(get("/me/business-profile/import-preview").header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.bannerHtml").value("<section class=\"capa\">capa do social</section>"));

		mockMvc.perform(post("/me/business-profile/import")
				.header("X-Session-Token", session.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of(
					"fields", java.util.List.of("bannerHtml")))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.bannerHtml").value("<section class=\"capa\">capa do social</section>"))
			.andExpect(jsonPath("$.headline").value(""));
	}

	@Test
	void emptySelectionIsRefused() throws Exception {
		var session = auth.authenticate("import_empty");
		createProfile(session, "import.empty");

		mockMvc.perform(post("/me/business-profile/import")
				.header("X-Session-Token", session.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of("fields", java.util.List.of()))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("NO_FIELDS_SELECTED"));
	}

	@Test
	void unknownFieldIsRefused() throws Exception {
		var session = auth.authenticate("import_unknown");
		createProfile(session, "import.unknown");

		mockMvc.perform(post("/me/business-profile/import")
				.header("X-Session-Token", session.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of(
					"fields", java.util.List.of("senha")))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_IMPORT_FIELD"));
	}
}
