package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.socialcoisa.rede.network.application.SigV4Verifier;
import com.socialcoisa.rede.shared.security.Hashes;
import com.socialcoisa.rede.shared.security.IpRateLimiter;
import com.socialcoisa.rede.shared.security.SecretCipher;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * O teto de requisição <b>legítima</b> por Access Key (brief §3.3, "controle de acesso").
 *
 * <p>O que já existia era o teto de <b>assinatura inválida</b> (doc 19 §3.2): protege o segredo
 * contra chute. Nada limitava o consumo de quem provou o segredo — e todo upload e download do
 * gateway passa pelo mesmo Control Plane. Uma credencial vazada ou um cliente em laço ocupava a
 * instalação inteira para todo mundo.</p>
 *
 * <p>O discriminante: passado o teto, a credencial recebe {@code 503 SlowDown}; <b>outra</b>
 * credencial no mesmo instante continua servida (o balde é da credencial, não do processo nem do IP);
 * e a conta de assinatura não foi gasta (uma requisição válida não pode bloquear a próxima
 * autenticação).</p>
 */
@SpringBootTest(properties = "rede.security.rate-limit.s3-request.limit=3")
@AutoConfigureMockMvc
class S3RateLimitTests {

	private static final String SECRET = "segredo-do-cenario-ritmo";
	private static final String ACCESS_KEY_PREFIX = "AKIAENOISRTM";
	private static final DateTimeFormatter AMZ = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
		.withZone(ZoneOffset.UTC);

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private SecretCipher cipher;

	@Autowired
	private IpRateLimiter tentativas;

	private String accessKey;
	private String ownerUserId;
	private String token;
	private String bucket;

	@BeforeEach
	void setUp() throws Exception {
		tentativas.limpar();
		var conta = new TestAuthHelper(mockMvc, objectMapper).authenticate("s3_ritmo_"
			+ UUID.randomUUID().toString().replace("-", "").substring(0, 8));
		ownerUserId = conta.userId();
		token = conta.token();
		accessKey = ACCESS_KEY_PREFIX
			+ UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
		bucket = "ritmo" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
		credencial(accessKey, ownerUserId);
	}

	private void credencial(String chave, String ownerUserId) {
		jdbc.update("""
			insert into network_storage_s3_credentials (id, access_key, secret_hash, secret_encrypted, owner_user_id,
				allowed_bucket, allowed_operations, created_at)
			values (?, ?, ?, ?, ?, null, 'GET PUT LIST DELETE', ?)
			""", "s3c_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12), chave,
			Hashes.sha256Hex(SECRET), cipher.encrypt(SECRET), ownerUserId,
			java.sql.Timestamp.from(Instant.parse("2026-11-01T00:00:00Z")));
	}

	private String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (java.security.NoSuchAlgorithmException impossivel) {
			throw new IllegalStateException(impossivel);
		}
	}

	private MockHttpServletRequestBuilder assinar(MockHttpServletRequestBuilder builder, String chave,
			String payloadHash) throws Exception {
		String amzDate = AMZ.format(Instant.now());
		builder.header("x-amz-date", amzDate).header("x-amz-content-sha256", payloadHash);
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("host", "localhost");
		headers.put("x-amz-content-sha256", payloadHash);
		headers.put("x-amz-date", amzDate);
		String signedHeaders = "host;x-amz-content-sha256;x-amz-date";
		String uri = builder.buildRequest(null).getRequestURI();
		var request = new SigV4Verifier.Request(builder.buildRequest(null).getMethod(),
			uri.startsWith("/s3") ? uri.substring(3) : uri, Map.of(), headers, payloadHash);
		var parsed = new SigV4Verifier.Parsed(chave, "enois-1", "s3", signedHeaders, "");
		String signature = SigV4Verifier.signature(request, parsed, SECRET, amzDate.substring(0, 8));
		builder.header(HttpHeaders.AUTHORIZATION, "AWS4-HMAC-SHA256 Credential=" + chave + "/"
			+ amzDate.substring(0, 8) + "/enois-1/s3/aws4_request, SignedHeaders=" + signedHeaders
			+ ", Signature=" + signature);
		return builder;
	}

	/**
	 * Um {@code PUT} de objeto: operação que não depende de o bucket já existir (gravar numa chave
	 * nova cria o bucket do dono). {@code LIST} não serve — ele confere a donagem antes, e o bucket
	 * ainda não é de ninguém na primeira chamada.
	 */
	private int gravar(String chave) throws Exception {
		byte[] corpo = ("conteudo-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
		return mockMvc.perform(assinar(put("/s3/" + bucket + "/" + UUID.randomUUID() + ".txt").content(corpo),
			chave, sha256(corpo))).andReturn().getResponse().getStatus();
	}

	@Test
	void passado_o_teto_a_credencial_recebe_503_e_outra_continua_servida() throws Exception {
		// Mesmo dono, outra Access Key — o caso real de quem tem duas credenciais (um job e uma
		// pessoa). Um dono diferente cairia em 403 de donagem antes de chegar ao ritmo.
		String outra = ACCESS_KEY_PREFIX + "OUTRA" + UUID.randomUUID().toString().replace("-", "").substring(0, 5)
			.toUpperCase();
		credencial(outra, ownerUserId);

		assertThat(gravar(accessKey)).isEqualTo(200);
		assertThat(gravar(accessKey)).isEqualTo(200);
		assertThat(gravar(accessKey)).isEqualTo(200);
		var barrada = mockMvc.perform(assinar(get("/s3/" + bucket), accessKey, sha256(new byte[0]))).andReturn();

		assertThat(barrada.getResponse().getStatus()).isEqualTo(503);
		assertThat(barrada.getResponse().getContentAsString()).contains("SlowDown");
		// O balde é da credencial: a outra não paga pelo laço da primeira.
		assertThat(gravar(outra)).as("credencial vizinha continua servida").isEqualTo(200);
	}

	/**
	 * A URL pré-assinada é a credencial portada por quem recebeu o link: ela também consume o Control
	 * Plane e entra na mesma conta. Sem isto, bastava emitir um link para furar o teto.
	 */
	@Test
	void a_url_pre_assinada_tambem_entra_no_ritmo() throws Exception {
		// O bucket nasce no primeiro PUT; o presign confere a donagem antes, então ele tem de existir.
		assertThat(gravar(accessKey)).isEqualTo(200);
		String chave = "links/" + UUID.randomUUID() + ".txt";
		var emissao = mockMvc.perform(post("/s3/" + bucket + "/presign").param("key", chave)
			.param("method", "PUT").header("X-Session-Token", token)).andReturn();
		assertThat(emissao.getResponse().getStatus()).as(emissao.getResponse().getContentAsString()).isEqualTo(200);
		String url = (String) objectMapper.readValue(emissao.getResponse().getContentAsString(), Map.class).get("url");
		// A URL emitida é relativa ao endpoint S3; o gateway vive sob `/s3`. O `Host` é o que o SigV4
		// exige — o MockMvc não manda sozinho.
		String caminho = url.substring(url.indexOf("/s3/"));

		for (int i = 0; i < 3; i++) {
			gravar(accessKey);
		}
		var barrado = mockMvc.perform(put(caminho).header("Host", "localhost")).andReturn();

		assertThat(barrado.getResponse().getStatus()).as(barrado.getResponse().getContentAsString()).isEqualTo(503);
		assertThat(barrado.getResponse().getContentAsString()).contains("SlowDown");
	}

	@Test
	void a_requisicao_valida_nao_gasta_o_orcamento_de_assinatura() throws Exception {
		for (int i = 0; i < 3; i++) {
			gravar(accessKey);
		}
		// Se a requisição válida consumisse o balde de autenticação, o cliente legítimo seria barrado
		// pelo próprio sucesso — o erro que o doc 19 §3.2 registra como evitado.
		assertThat(tentativas.permiteTentarAutenticacao(IpRateLimiter.S3_AUTH, accessKey)).isTrue();
	}

	@Test
	void o_get_tambem_entra_no_ritmo() throws Exception {
		for (int i = 0; i < 3; i++) {
			gravar(accessKey);
		}
		var barrado = mockMvc.perform(assinar(get("/s3/" + bucket + "/nao-existe.txt"), accessKey,
			sha256(new byte[0]))).andReturn();

		assertThat(barrado.getResponse().getStatus()).as("leitura não escapa do teto").isEqualTo(503);
		assertThat(barrado.getResponse().getContentAsString()).contains("SlowDown");
	}

	@Test
	void assinatura_invalida_continua_com_a_conta_propria() throws Exception {
		// Quatro assinaturas erradas não fazem a quinta requisição VÁLIDA parecer laço: o teto desta
		// rota é de requisição aceita, não de tentativa.
		String outra = ACCESS_KEY_PREFIX + "ERRADA" + UUID.randomUUID().toString().replace("-", "").substring(0, 4)
			.toUpperCase();
		for (int i = 0; i < 4; i++) {
			mockMvc.perform(get("/s3/" + bucket).header(HttpHeaders.AUTHORIZATION,
				"AWS4-HMAC-SHA256 Credential=" + outra + "/20261101/enois-1/s3/aws4_request, "
					+ "SignedHeaders=host, Signature=00"))
				.andReturn();
		}
		assertThat(gravar(accessKey)).isEqualTo(200);
	}
}