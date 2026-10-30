package com.socialcoisa.rede.corporate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

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
 * O nametag do perfil profissional é o `@` E o fim da URL pública — e é independente do @
 * social. A mesma pessoa pode ser `social.da.mel` no social e `melsantos` no profissional.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CorporateNametagTests {

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
				.content(objectMapper.writeValueAsString(Map.of("username", username, "name", "Nome Profissional"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.username").value(username));
	}

	private void activate(TestAuthHelper.AuthSession session) throws Exception {
		mockMvc.perform(patch("/me/business-profile")
				.header("X-Session-Token", session.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("headline", "Desenvolvedora"))))
			.andExpect(status().isOk());
		mockMvc.perform(post("/me/business-profile/activate").header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ACTIVE"));
	}

	private void changeNametag(TestAuthHelper.AuthSession session, String username) throws Exception {
		mockMvc.perform(post("/me/business-profile/username")
				.header("X-Session-Token", session.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("username", username))))
			.andExpect(status().isOk());
	}

	/**
	 * Trocar o nametag muda o @ e a URL pública: o endereço antigo deixa de responder e o novo
	 * passa a ser do perfil. O @ social continua o mesmo.
	 */
	@Test
	void newNametagChangesThePublicUrlAndNotTheSocialHandle() throws Exception {
		var session = auth.authenticate("nametag_success");
		mockMvc.perform(patch("/me/profile")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "nametag-social-setup")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("username", "social.da.mel", "name", "Mel"))))
			.andExpect(status().isOk());
		createProfile(session, "corp.antigo");
		activate(session);

		String socialHandle = objectMapper.readTree(mockMvc.perform(get("/me").header("X-Session-Token", session.token()))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString())
			.get("username")
			.asString();
		org.junit.jupiter.api.Assertions.assertTrue(socialHandle.startsWith("social-da-mel"), socialHandle);

		mockMvc.perform(get("/corporate/corp.antigo"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.username").value("corp.antigo"));

		changeNametag(session, "melsantos");

		mockMvc.perform(get("/me/business-profile").header("X-Session-Token", session.token()))
			.andExpect(jsonPath("$.username").value("melsantos"));
		mockMvc.perform(get("/corporate/melsantos"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.username").value("melsantos"));
		mockMvc.perform(get("/corporate/corp.antigo"))
			.andExpect(status().isNotFound());

		// O @ social não é tocado pelo nametag profissional.
		mockMvc.perform(get("/users/" + socialHandle))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.username").value(socialHandle));
	}

	/** O nametag de outra pessoa é recusado e o perfil continua com o que já tinha. */
	@Test
	void takenNametagIsRefused() throws Exception {
		var dona = auth.authenticate("nametag_dona");
		createProfile(dona, "nametag.dona");

		var outra = auth.authenticate("nametag_outra");
		createProfile(outra, "nametag.outra");

		mockMvc.perform(post("/me/business-profile/username")
				.header("X-Session-Token", outra.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("username", "nametag.dona"))))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("USERNAME_TAKEN"));

		mockMvc.perform(get("/me/business-profile").header("X-Session-Token", outra.token()))
			.andExpect(jsonPath("$.username").value("nametag.outra"));
	}

	@Test
	void emptyNametagIsRefused() throws Exception {
		var session = auth.authenticate("nametag_empty");
		createProfile(session, "nametag.vazio");

		mockMvc.perform(post("/me/business-profile/username")
				.header("X-Session-Token", session.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("username", "  "))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_USERNAME"));
	}

	@Test
	void malformedNametagIsRefused() throws Exception {
		var session = auth.authenticate("nametag_invalido");
		createProfile(session, "nametag.valido");

		// Curto demais, com espaço e começando por separador: nenhum vira URL de perfil.
		for (String invalido : new String[] { "ab", "mel santos", ".melsantos", "melsantos.", "melsantos_" }) {
			mockMvc.perform(post("/me/business-profile/username")
					.header("X-Session-Token", session.token())
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(Map.of("username", invalido))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_USERNAME"));
		}
	}

	/** Mandar o nametag que já está lá não é erro nem muda a data de atualização do perfil. */
	@Test
	void sendingTheSameNametagIsAccepted() throws Exception {
		var session = auth.authenticate("nametag_mesmo");
		createProfile(session, "nametag.mesmo");

		changeNametag(session, "nametag.mesmo");

		mockMvc.perform(get("/me/business-profile").header("X-Session-Token", session.token()))
			.andExpect(jsonPath("$.username").value("nametag.mesmo"));
	}
}