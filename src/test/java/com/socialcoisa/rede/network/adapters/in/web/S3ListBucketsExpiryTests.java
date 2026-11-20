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
 * O {@code ListBuckets} não pode mostrar um bucket que o cliente já não consegue nem listar.
 *
 * <p>O ciclo 81 fez a listagem de objeto esconder o que venceu. O {@code ListBuckets}, que lista os
 * <b>buckets</b>, seguiu com o critério antigo (só lápide fora): um bucket cujo único objeto venceu
 * continuava aparecendo, e o cliente que o abrisse recebia a lista vazia — \"o bucket existe, mas não
 * tem nada\", que é justamente o que ele não é. O {@code DELETE} desse bucket responde {@code 409}
 * dizendo que ainda tem objetos, e o {@code GET} de qualquer objeto dele dá {@code 404}: três
 * respostas contraditórias sobre o mesmo nome.</p>
 *
 * <p>O discriminante é o par: o bucket esvaziado por vencimento sai da lista, e o bucket que ainda tem
 * um objeto vivo (inclusive com outro também vencido) fica.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class S3ListBucketsExpiryTests {

	private static final String SECRET = "segredo-do-cenario-de-buckets";
	private static final String PREFIX = "AKIAENOISBKT";
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
	private String sufixo;

	@BeforeEach
	void setUp() throws Exception {
		var conta = new TestAuthHelper(mockMvc, objectMapper).authenticate("s3_bkt_"
			+ UUID.randomUUID().toString().replace("-", "").substring(0, 8));
		accessKey = PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
		sufixo = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
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

	private void gravar(String bucket, String chave, String conteudo) throws Exception {
		byte[] corpo = conteudo.getBytes(StandardCharsets.UTF_8);
		var r = mockMvc.perform(assinar(put("/s3/" + bucket + "/" + chave).content(corpo), sha256(corpo)))
			.andReturn();
		assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
	}

	private String buckets() throws Exception {
		var r = mockMvc.perform(assinar(get("/s3/"), sha256(new byte[0]))).andReturn();
		assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
		return r.getResponse().getContentAsString();
	}

	private void vencer(String bucket, String chave) {
		jdbc.update("""
			update network_storage_objects set expires_at = ? where bucket = ? and object_key = ?
			""", java.sql.Timestamp.from(Instant.now().minusSeconds(600)), bucket, chave);
	}

	@Test
	void bucket_sem_objeto_vivo_sai_da_lista() throws Exception {
		gravar("velho" + sufixo, "a.jpg", "vencido");
		vencer("velho" + sufixo, "a.jpg");

		// A régua que a lista de buckets tem de seguir: a listagem de objeto desse bucket já é vazia.
		var objetos = mockMvc.perform(assinar(get("/s3/velho" + sufixo), sha256(new byte[0]))).andReturn();
		assertThat(objetos.getResponse().getContentAsString()).doesNotContain("a.jpg");

		assertThat(buckets()).as("bucket sem objeto vivo nao pode aparecer")
			.doesNotContain("<Name>" + "velho" + sufixo + "</Name>");
	}

	@Test
	void bucket_com_um_objeto_vivo_continua_listado() throws Exception {
		gravar("misto" + sufixo, "vivo.jpg", "vivo");
		gravar("misto" + sufixo, "vencido.jpg", "vencido");
		vencer("misto" + sufixo, "vencido.jpg");

		assertThat(buckets()).contains("<Name>" + "misto" + sufixo + "</Name>");
	}

	/**
	 * O discriminante que faltava: a lista é do dono da credencial. Sem este caso, um
	 * {@code bucketsOf} que listasse o catálogo inteiro passaria nos outros três — o próprio bucket
	 * continuaria lá.
	 */
	@Test
	void bucket_de_outro_dono_nao_aparece() throws Exception {
		gravar("meu" + sufixo, "a.jpg", "meu");
		jdbc.update("""
			insert into network_storage_objects
				(id, bucket, object_key, content_type, bytes, sha256, state, created_by, created_at, updated_at)
			values (?, 'alheio_' || ?, 'a.jpg', 'image/jpeg', 5, ?, 'STORED', 'outra_conta', now(3), now(3))
			""", "sob_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10), sufixo, "a".repeat(64));

		String corpo = buckets();
		assertThat(corpo).contains("<Name>" + "meu" + sufixo + "</Name>");
		assertThat(corpo).as("bucket de outro dono nao pode aparecer")
			.doesNotContain("alheio_" + sufixo);
	}

	@Test
	void bucket_de_objeto_sem_prazo_continua_listado() throws Exception {
		gravar("fresco" + sufixo, "a.jpg", "sem prazo");

		assertThat(buckets()).contains("<Name>" + "fresco" + sufixo + "</Name>");
	}
}
