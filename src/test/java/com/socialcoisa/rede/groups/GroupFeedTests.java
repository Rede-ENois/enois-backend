package com.socialcoisa.rede.groups;

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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Tópicos e feed privado do grupo.
 *
 * O que este teste tranca:
 *
 * - visitante não vê tópicos nem publicações (403), mesmo com o id do grupo;
 * - só quem administra cria/edita/arquiva/reordena tópicos; membro comum publica, não administra;
 * - a publicação fica presa ao grupo E ao tópico DAQUELE grupo (tópico de outro grupo = 404) —
 *   a V81 não tem mais a chave estrangeira composta, então a checagem é no serviço e é testada;
 * - tópico arquivado não recebe publicação;
 * - **isolamento**: a publicação de grupo NÃO aparece no feed principal.
 */
@SpringBootTest(properties = {"rede.media.storage=s3", "rede.media.s3.bucket=bucket-de-teste",
		"rede.media.s3.publicBaseUrl=https://cdn.exemplo.com"})
@AutoConfigureMockMvc
class GroupFeedTests {

	static {
		System.setProperty("aws.accessKeyId", "test");
		System.setProperty("aws.secretAccessKey", "test");
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private TestAuthHelper authHelper() {
		return new TestAuthHelper(mockMvc, objectMapper);
	}

	private String createGroup(TestAuthHelper.AuthSession owner, String name, String key) throws Exception {
		MvcResult result = mockMvc.perform(post("/groups")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", name, "joinMode", "OPEN"))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private void join(TestAuthHelper.AuthSession user, String groupId, String key) throws Exception {
		mockMvc.perform(post("/groups/" + groupId + "/join")
				.header("X-Session-Token", user.token())
				.header("Idempotency-Key", key))
			.andExpect(status().isOk());
	}

	private MvcResult createTopic(TestAuthHelper.AuthSession admin, String groupId, String name, String key)
			throws Exception {
		return mockMvc.perform(post("/groups/" + groupId + "/topics")
				.header("X-Session-Token", admin.token())
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", name, "description", "assunto"))))
			.andReturn();
	}

	private MvcResult publish(TestAuthHelper.AuthSession author, String groupId, String topicId, String content,
			String key) throws Exception {
		return mockMvc.perform(post("/groups/" + groupId + "/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"topicId", topicId, "content", content, "mediaIds", List.of()))))
			.andReturn();
	}

	@Test
	void visitanteNaoVeTopicosENemPublicacoes() throws Exception {
		var dono = authHelper().authenticate("gfeed-dono");
		var estranho = authHelper().authenticate("gfeed-estranho");
		String groupId = createGroup(dono, "Grupo Fechado Feed", "gfeed-create-1");
		MvcResult topic = createTopic(dono, groupId, "Geral", "gfeed-topic-1");
		assertThat(topic.getResponse().getStatus()).isEqualTo(201);
		String topicId = objectMapper.readTree(topic.getResponse().getContentAsString()).get("id").asText();

		mockMvc.perform(get("/groups/" + groupId + "/topics").header("X-Session-Token", estranho.token()))
			.andExpect(status().isForbidden());
		mockMvc.perform(get("/groups/" + groupId + "/posts").header("X-Session-Token", estranho.token()))
			.andExpect(status().isForbidden());
		// Sem sessão também não.
		mockMvc.perform(get("/groups/" + groupId + "/topics")).andExpect(status().isUnauthorized());

		// E não publica.
		mockMvc.perform(post("/groups/" + groupId + "/posts")
				.header("X-Session-Token", estranho.token())
				.header("Idempotency-Key", "gfeed-post-estranho")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"topicId", topicId, "content", "nao deveria entrar", "mediaIds", List.of()))))
			.andExpect(status().isForbidden());
	}

	@Test
	void membroPublicaMasNaoAdministraTopicos() throws Exception {
		var dono = authHelper().authenticate("gfeed-dono2");
		var membro = authHelper().authenticate("gfeed-membro2");
		String groupId = createGroup(dono, "Grupo Membro Feed", "gfeed-create-2");
		join(membro, groupId, "gfeed-join-2");
		String topicId = objectMapper.readTree(
				createTopic(dono, groupId, "Duvidas", "gfeed-topic-2").getResponse().getContentAsString())
			.get("id").asText();

		// Membro publica.
		mockMvc.perform(post("/groups/" + groupId + "/posts")
				.header("X-Session-Token", membro.token())
				.header("Idempotency-Key", "gfeed-post-2")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"topicId", topicId, "content", "Minha duvida no grupo", "mediaIds", List.of()))))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.authorUsername").value("gfeed-membro2"))
			.andExpect(jsonPath("$.topicId").value(topicId));

		// Membro não cria tópico.
		mockMvc.perform(post("/groups/" + groupId + "/topics")
				.header("X-Session-Token", membro.token())
				.header("Idempotency-Key", "gfeed-topic-membro")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", "Nao posso"))))
			.andExpect(status().isForbidden());

		// A listagem por tópico devolve a publicação.
		mockMvc.perform(get("/groups/" + groupId + "/posts")
				.param("topicId", topicId)
				.header("X-Session-Token", membro.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].content").value("Minha duvida no grupo"));
	}

	@Test
	void publicacaoDeGrupoNaoVazaParaOFeedPrincipal() throws Exception {
		var dono = authHelper().authenticate("gfeed-dono3");
		String groupId = createGroup(dono, "Grupo Isolado", "gfeed-create-3");
		String topicId = objectMapper.readTree(
				createTopic(dono, groupId, "Geral", "gfeed-topic-3").getResponse().getContentAsString())
			.get("id").asText();

		String marcador = "conteudo-secreto-do-grupo-isolado";
		mockMvc.perform(post("/groups/" + groupId + "/posts")
				.header("X-Session-Token", dono.token())
				.header("Idempotency-Key", "gfeed-post-3")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"topicId", topicId, "content", marcador, "mediaIds", List.of()))))
			.andExpect(status().isCreated());

		// O feed principal (a mesma sessão do autor) não conhece o texto.
		MvcResult feed = mockMvc.perform(get("/feed").header("X-Session-Token", dono.token()))
			.andExpect(status().isOk())
			.andReturn();
		assertThat(feed.getResponse().getContentAsString()).doesNotContain(marcador);
	}

	@Test
	void topicoDeOutroGrupoEArquivadoSaoRecusados() throws Exception {
		var dono = authHelper().authenticate("gfeed-dono4");
		var membro = authHelper().authenticate("gfeed-membro4");
		String grupoA = createGroup(dono, "Grupo A Feed", "gfeed-create-4a");
		String grupoB = createGroup(dono, "Grupo B Feed", "gfeed-create-4b");
		join(membro, grupoA, "gfeed-join-4");
		String topicoDeB = objectMapper.readTree(
				createTopic(dono, grupoB, "Geral B", "gfeed-topic-4b").getResponse().getContentAsString())
			.get("id").asText();

		// Tópico de outro grupo: 404, não "publica no grupo errado".
		mockMvc.perform(post("/groups/" + grupoA + "/posts")
				.header("X-Session-Token", membro.token())
				.header("Idempotency-Key", "gfeed-cross-4")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"topicId", topicoDeB, "content", "cruzando grupos", "mediaIds", List.of()))))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("TOPIC_NOT_FOUND"));

		// Tópico arquivado não recebe publicação.
		String topicoA = objectMapper.readTree(
				createTopic(dono, grupoA, "Avisos", "gfeed-topic-4a").getResponse().getContentAsString())
			.get("id").asText();
		mockMvc.perform(post("/groups/" + grupoA + "/topics/" + topicoA + "/archive")
				.header("X-Session-Token", dono.token())
				.header("Idempotency-Key", "gfeed-archive-4"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ARCHIVED"));

		mockMvc.perform(post("/groups/" + grupoA + "/posts")
				.header("X-Session-Token", membro.token())
				.header("Idempotency-Key", "gfeed-post-arquivado")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"topicId", topicoA, "content", "topico arquivado", "mediaIds", List.of()))))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("TOPIC_ARCHIVED"));
	}

	@Test
	void reordenarExigeAOrdemCompletaDoGrupo() throws Exception {
		var dono = authHelper().authenticate("gfeed-dono5");
		String groupId = createGroup(dono, "Grupo Ordem", "gfeed-create-5");
		String primeiro = objectMapper.readTree(
				createTopic(dono, groupId, "Primeiro", "gfeed-topic-5a").getResponse().getContentAsString())
			.get("id").asText();
		String segundo = objectMapper.readTree(
				createTopic(dono, groupId, "Segundo", "gfeed-topic-5b").getResponse().getContentAsString())
			.get("id").asText();

		// Ordem incompleta é recusada.
		mockMvc.perform(post("/groups/" + groupId + "/topics/reorder")
				.header("X-Session-Token", dono.token())
				.header("Idempotency-Key", "gfeed-order-5a")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topicIds", List.of(primeiro)))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_ORDER"));

		// Ordem completa inverte.
		mockMvc.perform(post("/groups/" + groupId + "/topics/reorder")
				.header("X-Session-Token", dono.token())
				.header("Idempotency-Key", "gfeed-order-5b")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topicIds", List.of(segundo, primeiro)))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].id").value(segundo))
			.andExpect(jsonPath("$.items[0].position").value(0));

		// Editar o nome de um tópico também funciona (e o nome repetido é recusado).
		mockMvc.perform(patch("/groups/" + groupId + "/topics/" + primeiro)
				.header("X-Session-Token", dono.token())
				.header("Idempotency-Key", "gfeed-patch-5")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", "Renomeado"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Renomeado"));

		mockMvc.perform(patch("/groups/" + groupId + "/topics/" + primeiro)
				.header("X-Session-Token", dono.token())
				.header("Idempotency-Key", "gfeed-patch-5b")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", "Segundo"))))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("TOPIC_EXISTS"));
	}

	private String uploadMedia(TestAuthHelper.AuthSession owner, String key) throws Exception {
		MvcResult result = mockMvc.perform(post("/media/uploads")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"fileName", "grupo.png", "mimeType", "image/png", "size", 2048, "purpose", "POST_IMAGE"))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private org.springframework.test.web.servlet.ResultActions publishWithMedia(TestAuthHelper.AuthSession author,
			String groupId, String topicId, String content, List<String> mediaIds, String key) throws Exception {
		return mockMvc.perform(post("/groups/" + groupId + "/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"topicId", topicId, "content", content, "mediaIds", mediaIds))));
	}

	@Test
	void publicacaoDeGrupoLevaImagemDoProprioAutor() throws Exception {
		var dono = authHelper().authenticate("gfeed-media-dono");
		String groupId = createGroup(dono, "Grupo Com Imagem", "gfeed-media-create");
		String topicId = objectMapper.readTree(
				createTopic(dono, groupId, "Fotos", "gfeed-media-topic").getResponse().getContentAsString())
			.get("id").asText();
		String mediaId = uploadMedia(dono, "gfeed-media-upload");

		// Só imagem, sem texto: quem manda a foto não precisa escrever nada para publicar.
		publishWithMedia(dono, groupId, topicId, "", List.of(mediaId), "gfeed-media-post")
			.andExpect(status().isCreated());

		MvcResult lista = mockMvc.perform(get("/groups/" + groupId + "/posts")
				.header("X-Session-Token", dono.token()))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode post = objectMapper.readTree(lista.getResponse().getContentAsString()).get("items").get(0);
		assertThat(post.get("mediaIds").get(0).asText()).isEqualTo(mediaId);
	}

	@Test
	void publicacaoDeGrupoRecusaImagemDeOutraConta() throws Exception {
		var dono = authHelper().authenticate("gfeed-media-dono2");
		var intruso = authHelper().authenticate("gfeed-media-intruso");
		String groupId = createGroup(dono, "Grupo Alheio", "gfeed-media-create2");
		join(intruso, groupId, "gfeed-media-join2");
		String topicId = objectMapper.readTree(
				createTopic(dono, groupId, "Geral", "gfeed-media-topic2").getResponse().getContentAsString())
			.get("id").asText();
		String mediaDoDono = uploadMedia(dono, "gfeed-media-upload2");

		// O grupo não pode virar atalho para anexar arquivo que não é de quem publica.
		publishWithMedia(intruso, groupId, topicId, "olha essa foto", List.of(mediaDoDono),
				"gfeed-media-post2")
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("MEDIA_OWNER_MISMATCH"));
	}

	@Test
	void publicacaoDeGrupoSemTextoNemImagemERecusadaEMuitasMidiasTambem() throws Exception {
		var dono = authHelper().authenticate("gfeed-media-dono3");
		String groupId = createGroup(dono, "Grupo Vazio", "gfeed-media-create3");
		String topicId = objectMapper.readTree(
				createTopic(dono, groupId, "Geral", "gfeed-media-topic3").getResponse().getContentAsString())
			.get("id").asText();

		publishWithMedia(dono, groupId, topicId, "", List.of(), "gfeed-media-empty")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("EMPTY_CONTENT"));

		List<String> seis = java.util.stream.IntStream.rangeClosed(1, 6)
			.mapToObj(i -> "media_teste_" + i).toList();
		publishWithMedia(dono, groupId, topicId, "seis", seis, "gfeed-media-seis")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("TOO_MANY_MEDIA"));
	}
}
