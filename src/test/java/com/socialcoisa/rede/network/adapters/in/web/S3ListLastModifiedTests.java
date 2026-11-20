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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * O {@code LastModified} da listagem no formato que o SDK lê.
 *
 * <p>A S3 manda {@code LastModified} como {@code yyyy-MM-dd'T'HH:mm:ss.SSS'Z'} — sempre três casas
 * decimais e sempre {@code Z}. O gateway emitia {@code Instant.toString()}, que <b>omite os
 * milissegundos quando são zero</b> ({@code ...T08:00:00Z}) e emite até nove quando não são
 * ({@code ...T08:00:00.123456789Z}). Os dois são instantes válidos em Java, e é por isso que passaram:
 * o SDK AWS desserializa o campo com um {@code DateTimeFormatter} fixo de milissegundos, e um SDK
 * estrito recusa o documento inteiro — a listagem que funciona no navegador falha no cliente.</p>
 *
 * <p>O discriminante é o formato <b>nos dois extremos</b>: o instante redondo (sem fração, o caso que
 * aparecia saía sem as três casas) e o instante com nanossegundos (o caso que aparecia com nove).</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class S3ListLastModifiedTests {

	private static final String SECRET = "segredo-do-cenario-last-modified";
	private static final String PREFIX = "AKIAENOISLMD";
	private static final DateTimeFormatter AMZ = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
		.withZone(ZoneOffset.UTC);

	/** O formato da S3: data, hora, ponto, exatamente três casas e {@code Z}. */
	private static final Pattern S3_INSTANT = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");

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

	@BeforeEach
	void setUp() throws Exception {
		var conta = new TestAuthHelper(mockMvc, objectMapper).authenticate("s3_lmd_"
			+ UUID.randomUUID().toString().replace("-", "").substring(0, 8));
		accessKey = PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
		bucket = "lmd" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
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

	private MockHttpServletRequestBuilder assinar(MockHttpServletRequestBuilder builder, String payloadHash) {
		String amzDate = AMZ.format(Instant.now());
		builder.header("x-amz-date", amzDate).header("x-amz-content-sha256", payloadHash)
			.header("host", "localhost");
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

	private void gravar(String chave, Instant quando, String conteudo) throws Exception {
		byte[] corpo = conteudo.getBytes(StandardCharsets.UTF_8);
		var r = mockMvc.perform(assinar(put("/s3/" + bucket + "/" + chave).content(corpo), sha256(corpo)))
			.andReturn();
		assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
		jdbc.update("""
			update network_storage_objects set created_at = ?, updated_at = ? where bucket = ? and object_key = ?
			""", java.sql.Timestamp.from(quando), java.sql.Timestamp.from(quando), bucket, chave);
	}

	private String listar() throws Exception {
		var r = mockMvc.perform(assinar(get("/s3/" + bucket), sha256(new byte[0]))).andReturn();
		assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
		return r.getResponse().getContentAsString();
	}

	private static String lastModifiedDo(String xml, String chave) {
		Matcher m = Pattern.compile("<Key>" + Pattern.quote(chave) + "</Key><LastModified>([^<]*)</LastModified>")
			.matcher(xml);
		assertThat(m.find()).as("LastModified de " + chave + " no documento").isTrue();
		return m.group(1);
	}

	@Test
	void instante_redondo_sai_com_as_tres_casas() throws Exception {
		gravar("redondo.jpg", Instant.parse("2026-11-01T08:00:00Z"), "a");

		String escrito = lastModifiedDo(listar(), "redondo.jpg");

		assertThat(escrito).as("S3 exige milissegundos, mesmo quando sao zero").matches(S3_INSTANT);
		assertThat(escrito).isEqualTo("2026-11-01T08:00:00.000Z");
	}

	@Test
	void instante_com_nanossegundos_sai_em_milissegundos() throws Exception {
		gravar("nano.jpg", Instant.parse("2026-11-01T08:00:00.123456789Z"), "b");

		String escrito = lastModifiedDo(listar(), "nano.jpg");

		assertThat(escrito).matches(S3_INSTANT);
		assertThat(escrito).isEqualTo("2026-11-01T08:00:00.123Z");
	}
}
