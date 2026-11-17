package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestDatabase;

import tools.jackson.databind.ObjectMapper;

/**
 * Entrega de objeto ao cliente (brief §13): cache por tipo, {@code ETag}, {@code Last-Modified},
 * {@code Range} e {@code 304}.
 *
 * <p>A rota é pública de propósito — é o que permite ao CDN e ao navegador servirem a imagem sem
 * voltar ao Control Plane. O que não é público é a <b>política</b>: só imagem/vídeo/áudio recebem
 * cache compartilhado, e um documento nunca.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class NetworkStorageDeliveryTests {

	private static final byte[] VIDEO = "0123456789abcdefghij".getBytes(StandardCharsets.UTF_8);

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
		jdbc.update("delete from network_storage_placements");
		jdbc.update("delete from network_node_keys");
		jdbc.update("delete from network_provision_tokens");
		jdbc.update("delete from network_nodes");
		jdbc.update("delete from network_storage_objects");
	}

	/** Imagem: cache público de um ano, ETag do digest e Last-Modified da gravação. */
	@Test
	void imagem_sai_com_cache_publico_e_etag() throws Exception {
		Stored stored = store("imagem-de-perfil.png", "image/png");

		MockHttpServletResponse response = content(stored.id(), Map.of());

		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).contains("public").contains("immutable");
		assertThat(response.getContentType()).isEqualTo("image/png");
		assertThat(response.getHeader(HttpHeaders.ETAG)).isEqualTo("\"" + stored.sha256() + "\"");
		assertThat(response.getHeader(HttpHeaders.LAST_MODIFIED)).isNotNull();
		assertThat(response.getHeader(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
	}

	/** Documento: nunca em cache compartilhado, mesmo servido pela mesma rota. */
	@Test
	void documento_sai_sem_cache_compartilhado() throws Exception {
		Stored stored = store("contrato.pdf", "application/pdf");

		MockHttpServletResponse response = content(stored.id(), Map.of());

		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).doesNotContain("public").contains("no-store");
	}

	/**
	 * {@code bytes=5-9} devolve 206 com exatamente esses bytes. É o pedido que o player de vídeo faz
	 * para retomar — e o que hoje só funciona porque o front aponta para o ImageKit.
	 */
	@Test
	void faixa_devolve_206_com_os_bytes_pedidos() throws Exception {
		Stored stored = store("clipe.mp4", "video/mp4");

		MockHttpServletResponse response = content(stored.id(), Map.of(HttpHeaders.RANGE, "bytes=5-9"));

		assertThat(response.getStatus()).isEqualTo(206);
		assertThat(response.getHeader(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 5-9/20");
		assertThat(response.getHeader(HttpHeaders.CONTENT_LENGTH)).isEqualTo("5");
		assertThat(response.getContentAsByteArray()).isEqualTo("56789".getBytes(StandardCharsets.UTF_8));
	}

	/** {@code bytes=15-} retoma até o fim: o formato que o player manda ao continuar um vídeo. */
	@Test
	void faixa_aberta_devolve_ate_o_fim() throws Exception {
		Stored stored = store("clipe2.mp4", "video/mp4");

		MockHttpServletResponse response = content(stored.id(), Map.of(HttpHeaders.RANGE, "bytes=15-"));

		assertThat(response.getStatus()).isEqualTo(206);
		assertThat(response.getHeader(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 15-19/20");
		assertThat(response.getContentAsByteArray()).isEqualTo("fghij".getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Faixa além do fim é <b>416</b> com {@code bytes *}/{@code total}. Servir 200 faria o player
	 * achar que recebeu o trecho e tocar do começo — o bug de vídeo "que reinicia".
	 */
	@Test
	void faixa_fora_do_objeto_devolve_416() throws Exception {
		Stored stored = store("clipe3.mp4", "video/mp4");

		MockHttpServletResponse response = content(stored.id(), Map.of(HttpHeaders.RANGE, "bytes=999-"));

		assertThat(response.getStatus()).isEqualTo(416);
		assertThat(response.getHeader(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes */20");
	}

	/** {@code If-None-Match} com o ETag devolve 304 sem corpo: o cliente já tem os bytes. */
	@Test
	void etag_igual_devolve_304() throws Exception {
		Stored stored = store("cache.png", "image/png");

		MockHttpServletResponse response = content(stored.id(),
				Map.of(HttpHeaders.IF_NONE_MATCH, "\"" + stored.sha256() + "\""));

		assertThat(response.getStatus()).isEqualTo(304);
		assertThat(response.getHeader(HttpHeaders.ETAG)).isEqualTo("\"" + stored.sha256() + "\"");
		assertThat(response.getContentAsByteArray()).isEmpty();
	}

	/** ETag diferente de outro conteúdo NÃO pode dar 304 — serviria a imagem errada. */
	@Test
	void etag_diferente_devolve_o_corpo() throws Exception {
		Stored stored = store("cache2.png", "image/png");

		MockHttpServletResponse response = content(stored.id(),
				Map.of(HttpHeaders.IF_NONE_MATCH, "\"outro-digest-qualquer\""));

		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(response.getContentAsByteArray()).isEqualTo(VIDEO);
	}

	private record Stored(String id, String sha256) {
	}

	/**
	 * Executa o GET e <b>espera o corpo assíncrono terminar</b>.
	 *
	 * <p>A rota devolve {@code StreamingResponseBody}: os bytes saem numa thread do executor do MVC
	 * depois que o {@code perform} já retornou. Ler o {@code MvcResult} sem despachar é uma corrida
	 * — passa com a máquina ociosa e devolve corpo vazio quando a suíte inteira está rodando (o
	 * sintoma foi exatamente {@code but was: []}). {@code asyncDispatch} entrega a resposta pronta.
	 * Resposta sem corpo (304, 416) completa de forma síncrona e não precisa do despacho.</p>
	 */
	private MockHttpServletResponse content(String objectId, Map<String, String> headers) throws Exception {
		var request = get("/network/storage/objects/" + objectId + "/content");
		headers.forEach(request::header);
		MvcResult result = mockMvc.perform(request).andReturn();
		if (result.getRequest().isAsyncStarted()) {
			result = mockMvc.perform(asyncDispatch(result)).andReturn();
		}
		return result.getResponse();
	}

	/** Objeto no catálogo com content-type escolhido, corpo gravado pelo caminho real do nó. */
	private Stored store(String key, String contentType) throws Exception {
		String admin = adminToken();
		Node node = node(admin);
		String bucket = "entrega-" + UUID.randomUUID().toString().substring(0, 8);
		String id = "sob_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
		Instant now = Instant.now();
		jdbc.update("""
			insert into network_storage_objects (id, bucket, object_key, content_type, state, created_by, created_at, updated_at)
			values (?, ?, ?, ?, 'PENDING', 'user-test', ?, ?)
			""", id, bucket, key, contentType, now, now);

		var put = mockMvc
			.perform(put("/network/storage/objects/" + id).header("X-Node-Id", node.id())
				.header("X-Node-Key", node.key()).contentType(MediaType.APPLICATION_OCTET_STREAM).content(VIDEO))
			.andExpect(status().isOk())
			.andReturn();
		String sha = objectMapper.readTree(put.getResponse().getContentAsString()).get("sha256").asString();
		return new Stored(id, sha);
	}

	private record Node(String id, String key) {
	}

	private String adminToken() throws Exception {
		var session = auth.authenticate("net_entrega_admin_" + System.nanoTime());
		TestDatabase.setAdmin(session.userId());
		return session.token();
	}

	private Node node(String admin) throws Exception {
		String name = "entrega-" + UUID.randomUUID().toString().substring(0, 8);
		var tokenResult = mockMvc
			.perform(post("/admin/network/nodes/provision-token").header("X-Session-Token", admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("displayName", name, "ownerUserId", "owner-" + name,
					"profiles", List.of("NODE_STORAGE")))))
			.andExpect(status().isOk())
			.andReturn();
		String provisionToken = objectMapper.readTree(tokenResult.getResponse().getContentAsString())
			.get("token").asString();
		var enroll = mockMvc
			.perform(post("/network/nodes/enroll").contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("provisionToken", provisionToken, "declared",
					Map.of("cpuCores", 2, "memoryMb", 4096, "diskMb", 51200, "bandwidthMbps", 50)))))
			.andExpect(status().isOk())
			.andReturn();
		String nodeId = objectMapper.readTree(enroll.getResponse().getContentAsString()).get("nodeId").asString();
		mockMvc.perform(post("/admin/network/nodes/" + nodeId + "/approve").header("X-Session-Token", admin))
			.andExpect(status().isOk());
		var keyResult = mockMvc
			.perform(post("/admin/network/nodes/" + nodeId + "/keys").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andReturn();
		return new Node(nodeId, objectMapper.readTree(keyResult.getResponse().getContentAsString())
			.get("apiKey").asString());
	}
}