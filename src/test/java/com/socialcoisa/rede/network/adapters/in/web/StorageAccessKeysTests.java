package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.socialcoisa.rede.network.application.SigV4Verifier;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * Access Keys do gateway S3 criadas pelo painel.
 *
 * <p><b>O defeito que estes testes fixam:</b> criar credencial S3 exigia SQL direto no banco — o
 * segredo passava por terminal e histórico, e só quem tinha acesso ao banco conseguia abrir um
 * bucket. Agora o dono cria pela sessão e recebe o segredo <b>uma vez</b>.</p>
 *
 * <p>As asserções discriminam o que importa: o segredo <b>não</b> volta na listagem; a credencial
 * criada <b>funciona de verdade</b> no gateway (assinatura SigV4 aceita); revogada, <b>para</b> de
 * funcionar; e credencial de outra conta <b>não</b> é revogável por quem não é dono.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class StorageAccessKeysTests {

	/** O sha-256 de corpo vazio (o GET não tem corpo). */
	private static final String VAZIO = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

	private static final DateTimeFormatter AMZ = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
		.withZone(ZoneOffset.UTC);

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private ObjectMapper objectMapper;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	@Test
	void a_credencial_criada_assinatura_e_le_o_bucket_do_dono() throws Exception {
		var dono = auth.authenticate("ak_dono_" + System.nanoTime());
		var criada = criar(dono.token(), "{\"operations\":\"GET PUT LIST\"}");
		String accessKey = criada.accessKey();
		String secret = criada.secretKey();
		assertThat(secret).hasSizeGreaterThan(20);
		assertThat(criada.operations()).isEqualTo("GET PUT LIST");

		// A prova real: a credencial criada pelo painel autentica no gateway S3.
		// A prova real de que a credencial criada pelo painel autentica no gateway: a assinatura passa
		// e a recusa vem por AUTORIZAÇÃO ("bucket não é do dono"), nunca por assinatura/credencial.
		// Um cliente S3 usa este mesmo caminho para qualquer chave (`GET /bucket/key`).
		var resposta = mockMvc.perform(assinar(get("/s3/bucket-x/objeto"), accessKey, secret)).andReturn();
		assertThat(resposta.getResponse().getContentAsString()).as(resposta.getResponse().getContentAsString())
			.contains("Este bucket nao e do dono da credencial");
		assertThat(resposta.getResponse().getContentAsString()).doesNotContain("InvalidAccessKeyId")
			.doesNotContain("SignatureDoesNotMatch");
	}

	@Test
	void a_listagem_mostra_a_chave_e_nunca_o_segredo() throws Exception {
		var dono = auth.authenticate("ak_list_" + System.nanoTime());
		var criada = criar(dono.token(), "{}");
		String secreto = criada.secretKey();

		String corpo = mockMvc.perform(get("/network/storage/access-keys").header("X-Session-Token", dono.token()))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat(corpo).contains(criada.accessKey());
		// O segredo em claro não pode aparecer nem por acidente de serialização.
		assertThat(corpo).doesNotContain(secreto);
		assertThat(corpo).doesNotContain("secretKey");
		// Do banco também não sai em claro: o que está gravado é o cifrado.
		String gravado = jdbc.queryForObject(
			"select secret_encrypted from network_storage_s3_credentials where id = ?", String.class,
			criada.id());
		assertThat(gravado).isNotEqualTo(secreto);
	}

	@Test
	void revogada_a_credencial_para_de_autenticar() throws Exception {
		var dono = auth.authenticate("ak_rev_" + System.nanoTime());
		var criada = criar(dono.token(), "{}");
		String accessKey = criada.accessKey();
		String secret = criada.secretKey();
		// Autenticada e autorizada: o bucket não é do dono, e é ISSO que a resposta diz. Um 403 de
		// assinatura (`SignatureDoesNotMatch`) seria recusa da credencial — o teste distingue os dois.
		var antes = mockMvc.perform(assinar(get("/s3/bucket-x/objeto"), accessKey, secret)).andReturn();
		assertThat(antes.getResponse().getContentAsString()).as("antes de revogar, a credencial autentica")
			.contains("Este bucket nao e do dono da credencial");

		var revoke = mockMvc.perform(delete("/network/storage/access-keys/" + criada.id())
			.header("X-Session-Token", dono.token())).andReturn();
		assertThat(revoke.getResponse().getStatus()).isEqualTo(200);

		var depois = mockMvc.perform(assinar(get("/s3/bucket-x/objeto"), accessKey, secret)).andReturn();
		assertThat(depois.getResponse().getStatus()).isEqualTo(403);
		assertThat(depois.getResponse().getContentAsString()).contains("InvalidAccessKeyId");
	}

	@Test
	void credencial_de_outra_conta_nao_e_revogavel() throws Exception {
		var dono = auth.authenticate("ak_um_" + System.nanoTime());
		var outro = auth.authenticate("ak_dois_" + System.nanoTime());
		var criada = criar(dono.token(), "{}");

		// A tentativa do outro leva o id de uma credencial que, PARA ELE, não existe: 404 (um 403
		// confirmaria o id). E a resposta de sucesso jamais sai — é o que separa "não achou" de
		// "achou e não deixou".
		var recusa = mockMvc.perform(delete("/network/storage/access-keys/" + criada.id())
			.header("X-Session-Token", outro.token())).andReturn();
		assertThat(recusa.getResponse().getStatus()).isEqualTo(404);
		assertThat(recusa.getResponse().getContentAsString()).contains("STORAGE_ACCESS_KEY_NOT_FOUND");
		// A credencial do dono continua viva: autentica no gateway.
		assertThat(mockMvc.perform(assinar(get("/s3/bucket-x/objeto"), criada.accessKey(), criada.secretKey()))
			.andReturn().getResponse().getContentAsString()).contains("Este bucket nao e do dono da credencial");
	}

	@Test
	void verbo_desconhecido_nao_cria_credencial() throws Exception {
		var dono = auth.authenticate("ak_verbo_" + System.nanoTime());
		long antes = jdbc.queryForObject("select count(*) from network_storage_s3_credentials where owner_user_id = ?",
			Long.class, dono.userId());

		var recusa = mockMvc.perform(post("/network/storage/access-keys").header("X-Session-Token", dono.token())
			.contentType(MediaType.APPLICATION_JSON).content("{\"operations\":\"GET PURGE\"}")).andReturn();
		assertThat(recusa.getResponse().getStatus()).isEqualTo(400);
		// Recusar depois de gravar deixaria uma credencial que "tem" um verbo que ninguém entende.
		assertThat(jdbc.queryForObject("select count(*) from network_storage_s3_credentials where owner_user_id = ?",
			Long.class, dono.userId())).isEqualTo(antes);
	}

	@Test
	void sem_sessao_nao_cria_nem_lista() throws Exception {
		assertThat(mockMvc.perform(post("/network/storage/access-keys").contentType(MediaType.APPLICATION_JSON)
			.content("{}")).andReturn().getResponse().getStatus()).isEqualTo(401);
		assertThat(mockMvc.perform(get("/network/storage/access-keys")).andReturn().getResponse().getStatus())
			.isEqualTo(401);
	}

	// ------------------------------------------------------------------ apoio


	private AccessKeyView criar(String token, String corpo) throws Exception {
		var resposta = mockMvc.perform(post("/network/storage/access-keys").header("X-Session-Token", token)
			// Chave de idempotência única por chamada: o `POST` é o mesmo em todo teste, e o filtro de
			// duplo clique usa sessão + rota + corpo como identidade — sem chave nova, a segunda
			// criação receberia a resposta da primeira (mesmo objeto, sem criar nada) ou o 409 dela.
			.header("Idempotency-Key", java.util.UUID.randomUUID().toString())
			.contentType(MediaType.APPLICATION_JSON).content(corpo)).andExpect(status().isOk()).andReturn();
		var raiz = objectMapper.readTree(resposta.getResponse().getContentAsString());
		// `criar` devolve `{credential:{...}, secretKey:"..."}`: os testes querem o par num objeto só,
		// porque é assim que o cliente usa — a chave e o segredo na mesma mão. O segredo vem do nó
		// original; os campos da credencial são lidos pelo nome, no lugar de reconstruir a árvore.
		var credencial = raiz.get("credential");
		return new AccessKeyView(credencial.get("id").asString(), credencial.get("accessKey").asString(),
			credencial.get("allowedOperations").asString(), raiz.get("secretKey").asString());
	}

	/** O par que interessa ao teste: o id, a chave, o escopo e o segredo que o painel mostrou. */
	private record AccessKeyView(String id, String accessKey, String operations, String secretKey) {
	}

	/** Assina SigV4 como o SDK: o caminho S3 sem o prefixo `/s3` da instalação. */
	private MockHttpServletRequestBuilder assinar(MockHttpServletRequestBuilder builder, String accessKey,
			String secret) {
		return assinar(builder, accessKey, secret, null);
	}

	private MockHttpServletRequestBuilder assinar(MockHttpServletRequestBuilder builder, String accessKey,
			String secret, String chave) {
		String amzDate = AMZ.format(Clock.systemUTC().instant());
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("host", "localhost");
		headers.put("x-amz-content-sha256", VAZIO);
		headers.put("x-amz-date", amzDate);
		String signedHeaders = "host;x-amz-content-sha256;x-amz-date";
		String uri = builder.buildRequest(null).getRequestURI();
		// `builder.buildRequest(null)` devolve uma requisição NOVA a cada chamada — e o `%s` da chave
		// só é resolvido quando a requisição é construída. Sem o curinga abaixo, o caminho assinado
		// (`/s3`) não é o caminho enviado (`/s3/<bucket>/<key>`) e a assinatura não confere.
		String caminho = uri.startsWith("/s3") ? uri.substring(3) : uri;
		caminho = caminho.replace("%s", chave == null ? "" : chave);
		var request = new SigV4Verifier.Request(builder.buildRequest(null).getMethod(), caminho, Map.of(), headers,
			VAZIO);
		var parsed = new SigV4Verifier.Parsed(accessKey, "enois-1", "s3", signedHeaders, "");
		String signature = SigV4Verifier.signature(request, parsed, secret, amzDate.substring(0, 8));
		builder.header("x-amz-date", amzDate).header("x-amz-content-sha256", VAZIO)
			.header(HttpHeaders.AUTHORIZATION,
				"AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + amzDate.substring(0, 8)
					+ "/enois-1/s3/aws4_request, SignedHeaders=" + signedHeaders + ", Signature=" + signature);
		return builder;
	}
}