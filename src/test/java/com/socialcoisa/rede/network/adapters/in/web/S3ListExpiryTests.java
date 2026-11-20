package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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
import com.socialcoisa.rede.shared.security.SecretCipher;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * O {@code ListObjects} não mostra o que o {@code GET} já nega (brief §14, expiração).
 *
 * <p>O download de um objeto vencido responde {@code 404} desde cedo — a leitura filtra o prazo.
 * A listagem, não: {@code LIST} só exclui lápide ({@code state <> 'DELETED'}), então aparecia ali
 * uma chave que o cliente não consegue baixar. Para quem espera a varredura passar, é "existe mas
 * dá 404"; para um sincronizador de diretório, é pior — ele pede, recebe 404 e repete para sempre, ou
 * apaga a cópia local achando que o remoto venceu.</p>
 *
 * <p>O discriminante é o mesmo do {@code GET}: o que está vencido sai da lista, e o que ainda não
 * venceu fica. Sem o segundo, "listar nada" passaria.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class S3ListExpiryTests {

	private static final String SECRET = "segredo-do-cenario-da-listagem";
	private static final String PREFIX = "AKIAENOISLIST";
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

	private String accessKey;
	private String bucket;
	private String token;

	@BeforeEach
	void setUp() throws Exception {
		var conta = new TestAuthHelper(mockMvc, objectMapper).authenticate("s3_lista_"
			+ UUID.randomUUID().toString().replace("-", "").substring(0, 8));
		token = conta.token();
		accessKey = PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
		bucket = "lista" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
		jdbc.update("""
			insert into network_storage_s3_credentials (id, access_key, secret_hash, secret_encrypted, owner_user_id,
				allowed_bucket, allowed_operations, created_at)
			values (?, ?, ?, ?, ?, null, 'GET PUT LIST DELETE', ?)
			""", "s3c_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12), accessKey,
			Hashes.sha256Hex(SECRET), cipher.encrypt(SECRET), conta.userId(),
			java.sql.Timestamp.from(Instant.parse("2026-11-01T00:00:00Z")));
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (java.security.NoSuchAlgorithmException impossivel) {
			throw new IllegalStateException(impossivel);
		}
	}

	private MockHttpServletRequestBuilder assinar(MockHttpServletRequestBuilder builder, String payloadHash)
			throws Exception {
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
		var parsed = new SigV4Verifier.Parsed(accessKey, "enois-1", "s3", signedHeaders, "");
		String signature = SigV4Verifier.signature(request, parsed, SECRET, amzDate.substring(0, 8));
		builder.header(HttpHeaders.AUTHORIZATION, "AWS4-HMAC-SHA256 Credential=" + accessKey + "/"
			+ amzDate.substring(0, 8) + "/enois-1/s3/aws4_request, SignedHeaders=" + signedHeaders
			+ ", Signature=" + signature);
		return builder;
	}

	private void gravar(String chave, String conteudo) throws Exception {
		byte[] corpo = conteudo.getBytes(StandardCharsets.UTF_8);
		mockMvc.perform(assinar(put("/s3/" + bucket + "/" + chave).content(corpo), sha256(corpo)))
			.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
	}

	private String listar() throws Exception {
		var r = mockMvc.perform(assinar(get("/s3/" + bucket), sha256(new byte[0]))).andReturn();
		assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
		return r.getResponse().getContentAsString();
	}

	/** Marca o prazo direto no catálogo: o cenário é a leitura, não a configuração da política. */
	private void vencer(String chave) {
		jdbc.update("""
			update network_storage_objects set expires_at = ? where bucket = ? and object_key = ?
			""", java.sql.Timestamp.from(Instant.now().minusSeconds(600)), bucket, chave);
	}

	@Test
	void a_listagem_nao_mostra_o_objeto_vencido_que_o_get_nega() throws Exception {
		gravar("fotos/viva.jpg", "conteudo vivo");
		gravar("fotos/vencida.jpg", "conteudo vencido");
		vencer("fotos/vencida.jpg");

		// A leitura já nega: é a régua que a listagem tem de seguir.
		var get = mockMvc.perform(assinar(get("/s3/" + bucket + "/fotos/vencida.jpg"), sha256(new byte[0])))
			.andReturn();
		assertThat(get.getResponse().getStatus()).as("GET vencido").isEqualTo(404);

		String corpo = listar();
		assertThat(corpo).contains("fotos/viva.jpg");
		assertThat(corpo).as("a listagem nao pode mostrar o que o GET nega").doesNotContain("fotos/vencida.jpg");
	}

	@Test
	void objeto_dentro_do_prazo_continua_listado() throws Exception {
		gravar("fotos/futura.jpg", "conteudo futuro");
		jdbc.update("""
			update network_storage_objects set expires_at = ? where bucket = ? and object_key = ?
			""", java.sql.Timestamp.from(Instant.now().plusSeconds(3600)), bucket, "fotos/futura.jpg");

		assertThat(listar()).contains("fotos/futura.jpg");
	}

	@Test
	void objeto_sem_prazo_continua_listado() throws Exception {
		gravar("fotos/sem-prazo.jpg", "conteudo");
		assertThat(listar()).contains("fotos/sem-prazo.jpg");
	}
}
