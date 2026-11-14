package com.socialcoisa.rede.feed.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.media.domain.MediaAsset;
import com.socialcoisa.rede.media.domain.MediaStorageProvider;
import com.socialcoisa.rede.media.application.port.out.MediaAssetRepository;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

/**
 * Tabs de categorias no perfil: ALL retrocompativel, REPLIES privado, tab invalida,
 * e midia alcancavel alem da janela de busca.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProfileTabsTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private MediaAssetRepository mediaAssets;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	private String usernameOf(TestAuthHelper.AuthSession session) throws Exception {
		var result = mockMvc.perform(get("/me")
				.header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString())
			.get("username").asText();
	}

	private String createMediaAsset(String ownerId) {
		String id = "media_proftabs" + UUID.randomUUID().toString().replace("-", "");
		Instant now = Instant.now();
		mediaAssets.save(MediaAsset.pending(id, new UserId(ownerId), "file", "image/png", 10,
			"POST_IMAGE", "uploads/" + id + "/file",
			"https://enois.exemplo.com/api/storage/uploads/" + id + "/file",
			now, now.plus(1, ChronoUnit.HOURS), MediaStorageProvider.S3));
		return id;
	}

	private void publishPost(String token, String content, List<String> mediaIds) throws Exception {
		mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", token)
				.header("Idempotency-Key", "proftabs-" + UUID.randomUUID())
				.header("X-Client-Origin", "https://enois.ia.br")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(
					java.util.Map.of("topic", "TECNOLOGIA", "type", "INSIGHT", "content", content,
						"tags", List.of(), "mediaIds", mediaIds))))
			.andExpect(status().isCreated());
	}

	@Test
	void abaDefaultRetrocompativel() throws Exception {
		var session = auth.authenticate("proftabs_compat");
		String username = usernameOf(session);

		mockMvc.perform(get("/users/" + username + "/posts")
				.header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items").isArray());

		mockMvc.perform(get("/users/" + username + "/posts?tab=ALL")
				.header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items").isArray());
	}

	@Test
	void repliesTabNoEndpointAntigoRetorna400() throws Exception {
		var owner = auth.authenticate("proftabs_dono");
		String ownerUsername = usernameOf(owner);

		mockMvc.perform(get("/users/" + ownerUsername + "/posts?tab=REPLIES")
				.header("X-Session-Token", owner.token()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("USE_REPLIES_ENDPOINT"));
	}

	@Test
	void repliesEndpointExigemViewerDono() throws Exception {
		var owner = auth.authenticate("proftabs_dono2");
		var visitor = auth.authenticate("proftabs_visit2");
		String ownerUsername = usernameOf(owner);

		mockMvc.perform(get("/users/" + ownerUsername + "/replies")
				.header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items").isArray());

		mockMvc.perform(get("/users/" + ownerUsername + "/replies")
				.header("X-Session-Token", visitor.token()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("REPLIES_PRIVATE"));

		mockMvc.perform(get("/users/" + ownerUsername + "/replies"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("REPLIES_PRIVATE"));
	}

	@Test
	void tabInvalidaRetorna400() throws Exception {
		var session = auth.authenticate("proftabs_invalid");
		String username = usernameOf(session);

		mockMvc.perform(get("/users/" + username + "/posts?tab=INEXISTENTE"))
			.andExpect(status().isBadRequest());
	}

	/**
	 * Post com midia alem da janela: 4 posts sem midia + 1 com midia. Com limit=2 (janela=6),
	 * a primeira pagina pode nao ter midia, mas o cursor deve continuar buscando e a segunda
	 * pagina deve retornar o post com midia.
	 */
	@Test
	void mediaPaginaAlemDaJanela() throws Exception {
		var session = auth.authenticate("proftabs_media");
		String username = usernameOf(session);

		// 4 posts sem midia.
		for (int i = 0; i < 4; i++) {
			publishPost(session.token(), "Post sem midia " + i, List.of());
		}

		// 1 post com midia (o mais antigo, fica alem da janela pequena).
		// Publicado ANTES dos outros pra ficar no fundo da timeline — mas como todos publicam
		// rapido, usamos a ordem natural: o primeiro publicado e o mais antigo.
		// Na verdade, os 4 acima foram publicados DEPOIS, entao este fica no inicio.
		// Invertendo: publicamos o com midia PRIMEIRO.
		// Recriamos: midia primeiro, depois os sem midia.

		// Limpa e refaz na ordem certa.
		// (os posts ja criados acima ficam; vamos criar mais um com midia no inicio)
		String mediaId = createMediaAsset(session.userId());
		publishPost(session.token(), "Post com midia no fundo", List.of(mediaId));

		// Agora: 5 posts. O com midia e o mais recente (ultimo criado).
		// tab=MEDIA&limit=1 deve retornar o post com midia na primeira pagina.
		// Mas o cenario do bug e: midia no FUNDO, nao no topo.
		// Vamos criar mais 4 sem midia DEPOIS do com midia:
		for (int i = 0; i < 4; i++) {
			publishPost(session.token(), "Post recente sem midia " + i, List.of());
		}

		// Agora: ~9 posts. O com midia esta no meio/fundo.
		// tab=MEDIA&limit=1: janela busca 3 posts (limit*3), filtra por midia.
		// Se o com midia nao esta nos 3 primeiros, pagina 1 esvazia mas cursor continua.
		var page1 = mockMvc.perform(get("/users/" + username + "/posts?tab=MEDIA&limit=1")
				.header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode body1 = objectMapper.readTree(page1.getResponse().getContentAsString());

		// Eventualmente o post com midia aparece: ou na pagina 1 ou seguindo o cursor.
		boolean foundMedia = body1.path("items").size() > 0;
		String cursor = body1.path("nextCursor").asText(null);

		// Segue paginando ate achar ou o cursor acabar (max 10 paginas pra nao travar).
		int pages = 1;
		while (!foundMedia && cursor != null && pages < 10) {
			var next = mockMvc.perform(get("/users/" + username + "/posts?tab=MEDIA&limit=1&cursor=" + cursor)
					.header("X-Session-Token", session.token()))
				.andExpect(status().isOk())
				.andReturn();
			JsonNode bodyN = objectMapper.readTree(next.getResponse().getContentAsString());
			if (bodyN.path("items").size() > 0) {
				foundMedia = true;
			}
			cursor = bodyN.path("nextCursor").asText(null);
			pages++;
		}

		assertThat(foundMedia)
			.as("O post com midia deve ser alcancavel via paginacao da tab MEDIA")
			.isTrue();
	}
}
