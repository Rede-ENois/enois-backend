package com.socialcoisa.rede.corporate.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

/**
 * Fase 12 — segurança da identidade profissional. A pergunta que este arquivo responde:
 * o perfil profissional de OUTRA conta serve para alguma coisa quando a minha sessão é a
 * que assina? Não — a autoria sai sempre da sessão, o namespace é protegido e o post de
 * uma identidade não aceita comando de terceiros.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CorporateIdentitySecurityTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	private void createAndActivate(TestAuthHelper.AuthSession user, String username, String name)
			throws Exception {
		mockMvc.perform(post("/me/business-profile")
				.header("X-Session-Token", user.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("username", username, "name", name))))
			.andExpect(status().isOk());
		mockMvc.perform(patch("/me/business-profile")
				.header("X-Session-Token", user.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("headline", "Headline " + name))))
			.andExpect(status().isOk());
		mockMvc.perform(post("/me/business-profile/activate").header("X-Session-Token", user.token()))
			.andExpect(status().isOk());
	}

	private String publishBusiness(TestAuthHelper.AuthSession author, String content) throws Exception {
		MvcResult result = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "corpsec-" + System.nanoTime())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"type", "BUSINESS", "content", content, "tags", List.of(), "mediaIds", List.of()))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	@Test
	void businessProfileEndpointsRequireASession() throws Exception {
		mockMvc.perform(get("/me/business-profile"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		mockMvc.perform(post("/me/business-profile")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("username", "sem.sessao", "name", "Sem Sessão"))))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(post("/me/business-profile/activate"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void aProfileOfOneAccountDoesNotPublishForAnother() throws Exception {
		var owner = auth.authenticate("corpsec_owner");
		var stranger = auth.authenticate("corpsec_stranger");
		createAndActivate(owner, "corpsec.owner", "Owner Profissional");

		// A conta estranha não tem perfil: BUSINESS é recusado mesmo com o perfil da outra
		// ativa e conhecido — nenhum campo do payload consegue apontar para um perfil alheio.
		mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", stranger.token())
				.header("Idempotency-Key", "corpsec-stranger-publish")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"type", "BUSINESS", "content", "tentativa de publicar como outro",
					"tags", List.of(), "mediaIds", List.of(),
					"username", "corpsec.owner", "authorUsername", "corpsec.owner"))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NO_ACTIVE_CORPORATE_PROFILE"));

		// E o @ da outra conta continua reservado: não dá para registrar por cima.
		mockMvc.perform(post("/me/business-profile")
				.header("X-Session-Token", stranger.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"username", "corpsec.owner", "name", "Clone"))))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("USERNAME_TAKEN"));
	}

	@Test
	void onlyTheOwnerActsOnABusinessPost() throws Exception {
		var owner = auth.authenticate("corpsec_post_owner");
		var stranger = auth.authenticate("corpsec_post_stranger");
		createAndActivate(owner, "corpsec.posts", "Dona do Post");
		String postId = publishBusiness(owner, "Post corporativo de teste.");

		// Terceiros não editam nem apagam a publicação de uma identidade profissional.
		mockMvc.perform(patch("/feed/posts/" + postId)
				.header("X-Session-Token", stranger.token())
				.header("Idempotency-Key", "corpsec-editar-alheio")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"type", "BUSINESS", "content", "tentativa de edição alheia", "tags", List.of()))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("POST_OWNER_MISMATCH"));

		mockMvc.perform(delete("/feed/posts/" + postId)
				.header("X-Session-Token", stranger.token())
				.header("Idempotency-Key", "corpsec-apagar-alheio"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("POST_OWNER_MISMATCH"));

		// O post segue no ar para a dona.
		mockMvc.perform(get("/feed/posts/" + postId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(postId));
	}

	@Test
	void draftProfileHasNoPublicPageAndCannotPublish() throws Exception {
		var author = auth.authenticate("corpsec_draft");
		mockMvc.perform(post("/me/business-profile")
				.header("X-Session-Token", author.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"username", "corpsec.draft", "name", "Em Construção"))))
			.andExpect(status().isOk());

		// Rascunho não tem página pública...
		mockMvc.perform(get("/corporate/corpsec.draft/posts"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PROFILE_NOT_FOUND"));

		// ...nem publica BUSINESS.
		mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "corpsec-draft-publish")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"type", "BUSINESS", "content", "rascunho publicando", "tags", List.of(), "mediaIds", List.of()))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NO_ACTIVE_CORPORATE_PROFILE"));
	}

	@Test
	void deactivatedProfileStopsPublishingButKeepsPosts() throws Exception {
		var author = auth.authenticate("corpsec_deact");
		createAndActivate(author, "corpsec.deact", "Desativada");
		String postId = publishBusiness(author, "Post antes de desativar.");

		mockMvc.perform(post("/me/business-profile/deactivate").header("X-Session-Token", author.token()))
			.andExpect(status().isOk());

		mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "corpsec-pos-desativar")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"type", "BUSINESS", "content", "tentativa pos-desativar", "tags", List.of(), "mediaIds", List.of()))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NO_ACTIVE_CORPORATE_PROFILE"));

		// Desativar não apaga: o post continua acessível (regra dos módulos/personalização
		// aplicada à identidade — nada é destruído por uma decisão de exibição).
		mockMvc.perform(get("/feed/posts/" + postId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(postId));
	}

	@Test
	void sharedAccountValuesAreNotDuplicatedByTheCorporateIdentity() throws Exception {
		var author = auth.authenticate("corpsec_shared");

		MvcResult before = mockMvc.perform(get("/me").header("X-Session-Token", author.token()))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode social = objectMapper.readTree(before.getResponse().getContentAsString());
		int reputation = social.get("reputation").asInt();
		int followers = social.get("followersCount").asInt();
		int connections = social.get("connectionsCount").asInt();

		createAndActivate(author, "corpsec.shared", "Mesma Conta");

		// A diretriz da dona: é a MESMA conta. Criar/ativar a identidade profissional não
		// zera, duplica nem muda os valores da conta.
		mockMvc.perform(get("/me").header("X-Session-Token", author.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.reputation").value(reputation))
			.andExpect(jsonPath("$.followersCount").value(followers))
			.andExpect(jsonPath("$.connectionsCount").value(connections));

		// E o perfil profissional não carrega uma segunda cópia desses valores: eles vivem
		// na conta, não na apresentação.
		mockMvc.perform(get("/corporate/corpsec.shared"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.username").value("corpsec.shared"))
			.andExpect(jsonPath("$.reputation").doesNotExist())
			.andExpect(jsonPath("$.followersCount").doesNotExist())
			.andExpect(jsonPath("$.connectionsCount").doesNotExist())
			.andExpect(jsonPath("$.email").doesNotExist())
			// A pagina de trabalho mostra o portfolio publico da conta, sem o @ social.
			.andExpect(jsonPath("$.identityVerified").isBoolean())
			.andExpect(jsonPath("$.memberSince").isString())
			.andExpect(jsonPath("$.skills").isArray())
			.andExpect(jsonPath("$.experiences").isArray())
			.andExpect(jsonPath("$.educations").isArray())
			.andExpect(jsonPath("$.certifications").isArray())
			.andExpect(jsonPath("$.socialUsername").doesNotExist());
	}
}
