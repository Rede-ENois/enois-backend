package com.socialcoisa.rede.colecoes.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

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
 * As Coleções pessoais de ponta a ponta: pastas privadas em que o dono organiza os posts que JÁ
 * salvou. Cobrirem-se aqui: validação e idempotência na criação, guardar exigindo o post salvo
 * no perfil E visível (a coleção não vira vitrine), coleção alheia respondendo 404 como se não
 * existisse, edição e remoção só do dono, tirar post da pasta, post apagado do feed saindo da
 * coleção sem quebrar a página — e o post continuando salvo no perfil.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ColecoesFlowTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void createsValidatesAndListsOwnCollectionsWithIdempotency() throws Exception {
		var owner = auth("colecao_owner");
		var outra = auth("colecao_outra");

		// Validação: sem título, título longo e descrição longa recusam.
		mockMvc.perform(post("/colecoes").header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "colecao-vazio-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("description", "sem título"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COLECAO_TITLE_REQUIRED"));
		mockMvc.perform(post("/colecoes").header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "colecao-longo-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("title", "c".repeat(121)))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COLECAO_TITLE_TOO_LONG"));
		mockMvc.perform(post("/colecoes").header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "colecao-desc-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("title", "receitas", "description", "d".repeat(501)))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("CONTENT_TOO_LONG"));

		// Sem Idempotency-Key a escrita nem começa.
		mockMvc.perform(post("/colecoes").header("X-Session-Token", owner.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("title", "sem chave"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));

		// Criação: id próprio, contagem zerada.
		MvcResult criado = mockMvc.perform(post("/colecoes").header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "colecao-cria-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("title", "Receitas da vó",
					"description", "Tudo que a comunidade postou e eu não quero perder"))))
			.andExpect(status().isCreated())
			.andReturn();
		JsonNode colecao = objectMapper.readTree(criado.getResponse().getContentAsString());
		String colecaoId = colecao.get("id").asText();
		org.assertj.core.api.Assertions.assertThat(colecaoId).startsWith("colecao_");
		org.assertj.core.api.Assertions.assertThat(colecao.get("postCount").asLong()).isZero();

		// Mesma chave = mesmo resultado: replay devolve a MESMA coleção, sem duplicar.
		String chave = "colecao-replay-" + UUID.randomUUID();
		MvcResult replay = mockMvc.perform(post("/colecoes").header("X-Session-Token", owner.token())
				.header("Idempotency-Key", chave)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("title", "replay"))))
			.andExpect(status().isCreated())
			.andReturn();
		MvcResult replay2 = mockMvc.perform(post("/colecoes").header("X-Session-Token", owner.token())
				.header("Idempotency-Key", chave)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("title", "replay"))))
			.andExpect(status().isCreated())
			.andReturn();
		org.assertj.core.api.Assertions
			.assertThat(objectMapper.readTree(replay2.getResponse().getContentAsString()).get("id").asText())
			.isEqualTo(objectMapper.readTree(replay.getResponse().getContentAsString()).get("id").asText());

		// A listagem é PESSOAL: traz a minha e não traz a de ninguém.
		String colecaoAlheia = createColecao(outra, "Coleção de outra pessoa");
		MvcResult lista = mockMvc.perform(get("/colecoes").header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andReturn();
		org.assertj.core.api.Assertions.assertThat(lista.getResponse().getContentAsString()).contains(colecaoId);
		org.assertj.core.api.Assertions.assertThat(lista.getResponse().getContentAsString())
			.doesNotContain(colecaoAlheia);

		// Detalhe da própria coleção.
		MvcResult detalhe = mockMvc.perform(get("/colecoes/" + colecaoId).header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andReturn();
		org.assertj.core.api.Assertions
			.assertThat(objectMapper.readTree(detalhe.getResponse().getContentAsString()).get("title").asText())
			.isEqualTo("Receitas da vó");
	}

	@Test
	void savingRequiresThePostSavedOnProfileAndVisible() throws Exception {
		var author = auth("colecao_postauthor");
		var owner = auth("colecao_mowner");
		var intruso = auth("colecao_intruso");
		String colecaoId = createColecao(owner, "Posts que me marcaram");
		String postId = publishPost(author, "Um texto que eu preciso guardar para reler depois");

		// Post não salvo no perfil: a coleção organiza salvos, não substitui o salvar.
		mockMvc.perform(post("/colecoes/" + colecaoId + "/posts")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "colecao-naosalvo-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("postId", postId))))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("NAO_SALVO"));

		// Post inexistente recusa: coleção não é vitrine de nada que não dá para ver.
		mockMvc.perform(post("/colecoes/" + colecaoId + "/posts")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "colecao-fantasma-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("postId", "post_inexistente"))))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("POST_NOT_FOUND"));

		// Salvo no perfil (o fluxo de sempre), então guardar funciona: 201, post vem inteiro.
		mockMvc.perform(post("/feed/posts/" + postId + "/saves")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "colecao-salva-" + UUID.randomUUID()))
			.andExpect(status().isOk());
		MvcResult guardado = mockMvc.perform(post("/colecoes/" + colecaoId + "/posts")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "colecao-guarda-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("postId", postId))))
			.andExpect(status().isCreated())
			.andReturn();
		JsonNode salvo = objectMapper.readTree(guardado.getResponse().getContentAsString());
		org.assertj.core.api.Assertions.assertThat(salvo.get("postId").asText()).isEqualTo(postId);
		org.assertj.core.api.Assertions.assertThat(salvo.get("post").get("content").asText())
			.contains("guardar para reler");

		// O mesmo post na mesma coleção não guarda de novo.
		mockMvc.perform(post("/colecoes/" + colecaoId + "/posts")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "colecao-guarda2-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("postId", postId))))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("JA_NA_COLECAO"));

		// A página de posts traz o post guardado; a contagem da coleção sobe.
		MvcResult pagina = mockMvc.perform(get("/colecoes/" + colecaoId + "/posts")
				.header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andReturn();
		org.assertj.core.api.Assertions.assertThat(pagina.getResponse().getContentAsString()).contains(postId);
		MvcResult detalhe = mockMvc.perform(get("/colecoes/" + colecaoId).header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andReturn();
		org.assertj.core.api.Assertions
			.assertThat(objectMapper.readTree(detalhe.getResponse().getContentAsString()).get("postCount").asLong())
			.isEqualTo(1);

		// Coleção é PRIVADA: intruso não vê detalhe, não lista posts e não guarda nela —
		// tudo 404, como se a coleção não existisse.
		mockMvc.perform(get("/colecoes/" + colecaoId).header("X-Session-Token", intruso.token()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("COLECAO_NOT_FOUND"));
		mockMvc.perform(get("/colecoes/" + colecaoId + "/posts").header("X-Session-Token", intruso.token()))
			.andExpect(status().isNotFound());
		mockMvc.perform(post("/colecoes/" + colecaoId + "/posts")
				.header("X-Session-Token", intruso.token())
				.header("Idempotency-Key", "colecao-intruso-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("postId", postId))))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("COLECAO_NOT_FOUND"));
	}

	/**
	 * Coleção não tem link público: o endereço de uma coleção aberto por quem NÃO é o dono não
	 * entrega nada — nem sessão, nem outra conta. É a diferença para o mural, que se compartilha.
	 */
	@Test
	void colecaoNaoAbrePorLinkDeFora() throws Exception {
		var dona = auth("colecao_linkdona");
		var outra = auth("colecao_linkoutra");
		var autor = auth("colecao_linkautor");
		String colecaoId = createColecao(dona, "Pasta só minha");
		String postId = publishPost(autor, "post guardado na pasta privada");
		save(dona, postId);
		guardar(dona, colecaoId, postId);

		// Sem sessão: 401 antes de qualquer busca — o endereço sozinho não abre a coleção.
		mockMvc.perform(get("/colecoes/" + colecaoId))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/colecoes/" + colecaoId + "/posts"))
			.andExpect(status().isUnauthorized());

		// Com sessão de outra pessoa: 404, sem confirmar que a coleção existe e sem vazar o post.
		mockMvc.perform(get("/colecoes/" + colecaoId).header("X-Session-Token", outra.token()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("COLECAO_NOT_FOUND"));
		mockMvc.perform(get("/colecoes/" + colecaoId + "/posts").header("X-Session-Token", outra.token()))
			.andExpect(status().isNotFound())
			.andExpect(result -> org.assertj.core.api.Assertions
				.assertThat(result.getResponse().getContentAsString())
				.doesNotContain(postId));

		// A dona continua abrindo normalmente.
		mockMvc.perform(get("/colecoes/" + colecaoId).header("X-Session-Token", dona.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.title").value("Pasta só minha"));
	}

	@Test
	void onlyOwnerEditsRemovesAndTakesPostsOut() throws Exception {
		var owner = auth("colecao_edowner");
		var intruso = auth("colecao_edintruso");
		var autor = auth("colecao_edauthor");
		String colecaoId = createColecao(owner, "Vai ser editada");
		String postId = publishPost(autor, "post de coleção para edição");
		save(owner, postId);
		guardar(owner, colecaoId, postId);

		// Coleção alheia: editar, tirar post e remover viram 404 (não 403 — nem a existência se confirma).
		mockMvc.perform(patch("/colecoes/" + colecaoId).header("X-Session-Token", intruso.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("title", "hackeada"))))
			.andExpect(status().isNotFound());
		mockMvc.perform(delete("/colecoes/" + colecaoId + "/posts/" + postId)
				.header("X-Session-Token", intruso.token())
				.header("Idempotency-Key", "colecao-tira-intruso-" + UUID.randomUUID()))
			.andExpect(status().isNotFound());
		mockMvc.perform(delete("/colecoes/" + colecaoId)
				.header("X-Session-Token", intruso.token())
				.header("Idempotency-Key", "colecao-del-intruso-" + UUID.randomUUID()))
			.andExpect(status().isNotFound());

		// Dono edita: título muda.
		mockMvc.perform(patch("/colecoes/" + colecaoId).header("X-Session-Token", owner.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("title", "Coleção editada", "description", "nova"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.title").value("Coleção editada"));

		// Dono tira o post da pasta: 200; tirar de novo é 404.
		mockMvc.perform(delete("/colecoes/" + colecaoId + "/posts/" + postId)
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "colecao-tira-" + UUID.randomUUID()))
			.andExpect(status().isOk());
		mockMvc.perform(delete("/colecoes/" + colecaoId + "/posts/" + postId)
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "colecao-tira2-" + UUID.randomUUID()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("POST_NAO_NA_COLECAO"));

		// Remover a coleção apaga a pasta de verdade: GET vira 404 em seguida.
		mockMvc.perform(delete("/colecoes/" + colecaoId)
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "colecao-del-" + UUID.randomUUID()))
			.andExpect(status().isOk());
		mockMvc.perform(get("/colecoes/" + colecaoId).header("X-Session-Token", owner.token()))
			.andExpect(status().isNotFound());
	}

	@Test
	void removeColecaoUtilizadaEmOutroAparelhoEOLinkAntigoViraNaoEncontrado() throws Exception {
		var owner = auth("colecao_doisaparelhos");
		String colecaoId = createColecao(owner, "Coleção que vai sumir");

		// Primeiro aparelho remove; segundo aparelho remove a mesma pasta com um link antigo.
		mockMvc.perform(delete("/colecoes/" + colecaoId)
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "colecao-del-a-" + UUID.randomUUID()))
			.andExpect(status().isOk());
		mockMvc.perform(delete("/colecoes/" + colecaoId)
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "colecao-del-b-" + UUID.randomUUID()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("COLECAO_NOT_FOUND"));

		// E a listagem não traz mais nada dela.
		MvcResult lista = mockMvc.perform(get("/colecoes").header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andReturn();
		org.assertj.core.api.Assertions.assertThat(lista.getResponse().getContentAsString()).doesNotContain(colecaoId);
	}

	@Test
	void deletedPostDropsOutOfCollectionButOthersStay() throws Exception {
		var author = auth("colecao_rauthor");
		var owner = auth("colecao_rownner");
		String colecaoId = createColecao(owner, "Coleção da remoção");
		String postId = publishPost(author, "post que o autor vai apagar");
		String postId2 = publishPost(author, "post que fica salvo e guardado");
		save(owner, postId);
		save(owner, postId2);
		guardar(owner, colecaoId, postId);
		guardar(owner, colecaoId, postId2);

		// O autor apaga o post do feed: o elo fica na coleção, mas o post sai da página…
		mockMvc.perform(delete("/feed/posts/" + postId)
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "colecao-apaga-" + UUID.randomUUID()))
			.andExpect(status().isOk());
		MvcResult pagina = mockMvc.perform(get("/colecoes/" + colecaoId + "/posts")
				.header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andReturn();
		org.assertj.core.api.Assertions.assertThat(pagina.getResponse().getContentAsString()).doesNotContain(postId);
		org.assertj.core.api.Assertions.assertThat(pagina.getResponse().getContentAsString()).contains(postId2);

		// …e sai também da listagem de salvos do perfil (o feed só lista o que ainda existe) —
		// o que permanece é o post 2, salvo e guardado.
		MvcResult salvos = mockMvc.perform(get("/feed/saved").header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andReturn();
		org.assertj.core.api.Assertions.assertThat(salvos.getResponse().getContentAsString()).doesNotContain(postId);
		org.assertj.core.api.Assertions.assertThat(salvos.getResponse().getContentAsString()).contains(postId2);
	}

	@Test
	void seletorDoPostListaSoAsMinhasColecoesComMarcaDeOndeOPostJaEsta() throws Exception {
		var dona = auth("colecao_seldona");
		var outra = auth("colecao_seloutra");
		var autor = auth("colecao_selautor");
		String minhaColecao = createColecao(dona, "Referências de design");
		String colecaoAlheia = createColecao(outra, "Coleção que NAO pode aparecer");
		String postId = publishPost(autor, "post que o seletor vai guardar");

		// Antes de guardar: a minha aparece com tem=false; a alheia não aparece.
		MvcResult antes = mockMvc.perform(get("/colecoes/minhas").header("X-Session-Token", dona.token())
				.param("postId", postId))
			.andExpect(status().isOk())
			.andReturn();
		String antesJson = antes.getResponse().getContentAsString();
		org.assertj.core.api.Assertions.assertThat(antesJson).contains(minhaColecao).doesNotContain(colecaoAlheia);
		JsonNode itemAntes = objectMapper.readTree(antesJson).get("items").get(0);
		org.assertj.core.api.Assertions.assertThat(itemAntes.get("tem").asBoolean()).isFalse();
		org.assertj.core.api.Assertions.assertThat(itemAntes.get("postCount").asLong()).isZero();

		// Guardado: a flag vira true e a contagem sobe.
		save(dona, postId);
		guardar(dona, minhaColecao, postId);
		MvcResult depois = mockMvc.perform(get("/colecoes/minhas").header("X-Session-Token", dona.token())
				.param("postId", postId))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode itemDepois = objectMapper.readTree(depois.getResponse().getContentAsString()).get("items").get(0);
		org.assertj.core.api.Assertions.assertThat(itemDepois.get("tem").asBoolean()).isTrue();
		org.assertj.core.api.Assertions.assertThat(itemDepois.get("postCount").asLong()).isEqualTo(1);

		// Outra pessoa tem a lista PRÓPRIA: nem vê a minha pasta.
		MvcResult deOutra = mockMvc.perform(get("/colecoes/minhas").header("X-Session-Token", outra.token())
				.param("postId", postId))
			.andExpect(status().isOk())
			.andReturn();
		org.assertj.core.api.Assertions.assertThat(deOutra.getResponse().getContentAsString())
			.contains(colecaoAlheia).doesNotContain(minhaColecao);

		// Sem sessão nem existe seletor: 401, como o resto do módulo.
		mockMvc.perform(get("/colecoes/minhas").param("postId", postId))
			.andExpect(status().isUnauthorized());
	}

	/** A busca acha pelo título ou pela descrição, sem ligar para maiúsculas — e só entre as minhas. */
	@Test
	void buscaAchaColecaoPorTituloOuDescricaoSoEntreAsMinhas() throws Exception {
		var dona = auth("colecao_bdona");
		var outra = auth("colecao_boutra");
		String receitas = createColecao(dona, "Receitas da vó");
		createColecao(dona, "Peladas do bairro");
		String deOutra = createColecao(outra, "Receitas alheias");

		// Título, sem ligar para maiúsculas; a coleção de outra pessoa não aparece.
		org.assertj.core.api.Assertions.assertThat(idsDaBusca(dona, "receitas")).containsExactly(receitas);
		org.assertj.core.api.Assertions.assertThat(idsDaBusca(dona, "RECEITAS")).containsExactly(receitas);
		org.assertj.core.api.Assertions.assertThat(idsDaBusca(dona, "alheias")).isEmpty();

		// A lista de outra pessoa continua só com as dela.
		org.assertj.core.api.Assertions.assertThat(idsDaBusca(outra, "receitas")).containsExactly(deOutra);

		// Termo inexistente é lista vazia; espaço em volta não atrapalha; `%` e `_` são texto.
		org.assertj.core.api.Assertions.assertThat(idsDaBusca(dona, "maratona")).isEmpty();
		org.assertj.core.api.Assertions.assertThat(idsDaBusca(dona, "  peladas  ")).hasSize(1);
		org.assertj.core.api.Assertions.assertThat(idsDaBusca(dona, "%")).isEmpty();
	}

	private java.util.List<String> idsDaBusca(TestAuthHelper.AuthSession dona, String termo) throws Exception {
		MvcResult lista = mockMvc
			.perform(get("/colecoes").param("search", termo).header("X-Session-Token", dona.token()))
			.andExpect(status().isOk())
			.andReturn();
		java.util.List<String> ids = new java.util.ArrayList<>();
		for (JsonNode item : objectMapper.readTree(lista.getResponse().getContentAsString()).get("items")) {
			ids.add(item.get("id").asText());
		}
		return ids;
	}

	private TestAuthHelper.AuthSession auth(String name) throws Exception {
		return new TestAuthHelper(mockMvc, objectMapper).authenticate(name);
	}

	private String createColecao(TestAuthHelper.AuthSession owner, String title) throws Exception {
		MvcResult criado = mockMvc.perform(post("/colecoes")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "colecao-flow-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("title", title))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(criado.getResponse().getContentAsString()).get("id").asText();
	}

	private String publishPost(TestAuthHelper.AuthSession author, String content) throws Exception {
		MvcResult criado = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "colecao-post-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA", "type", "INSIGHT",
					"content", content, "tags", java.util.List.of(), "mediaIds", java.util.List.of()))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(criado.getResponse().getContentAsString()).get("id").asText();
	}

	private void save(TestAuthHelper.AuthSession user, String postId) throws Exception {
		mockMvc.perform(post("/feed/posts/" + postId + "/saves")
				.header("X-Session-Token", user.token())
				.header("Idempotency-Key", "colecao-sflow-" + UUID.randomUUID()))
			.andExpect(status().isOk());
	}

	private void guardar(TestAuthHelper.AuthSession user, String colecaoId, String postId) throws Exception {
		mockMvc.perform(post("/colecoes/" + colecaoId + "/posts")
				.header("X-Session-Token", user.token())
				.header("Idempotency-Key", "colecao-gflow-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("postId", postId))))
			.andExpect(status().isCreated());
	}
}
