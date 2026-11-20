package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.socialcoisa.rede.network.application.SigV4Verifier;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * A cota por participante na rota real do gateway S3 (doc 20 §10).
 *
 * <p>O teste de serviço prova a conta; este prova que o upload do cliente S3 <b>para</b> no teto
 * com o número na mensagem, e que a cota de um participante não fechou a porta do outro — que é o
 * dano real de não ter cota: quem enche primeiro faz o vizinho receber 507.</p>
 *
 * <p>A cota é configurada bem pequena porque o caminho real mede os bytes enviados: um teto de
 * megabytes faria o teste gravar megabytes só para chegar ao limite.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "rede.storage.max-bytes-per-owner=" + S3OwnerQuotaTests.COTA_BYTES)
class S3OwnerQuotaTests {

	/** Cabe um corpo de 12 bytes e não cabe o segundo. */
	static final long COTA_BYTES = 20;

	private static final String SECRET = "segredo-do-cenario-da-cota-0123456789";
	private static final String REGIAO = "enois-1";
	private static final DateTimeFormatter AMZ =
		DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(java.time.ZoneOffset.UTC);
	/**
	 * O relógio do gateway é o do sistema, e não um bean fixo: assinar com data distante do "agora"
	 * cairia na janela de tolerância e provaria a recusa em vez do caminho (mesmo critério do
	 * {@code S3GatewayTests}).
	 */
	private static Instant agora() {
		return Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private com.socialcoisa.rede.shared.security.SecretCipher cipher;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		jdbc.update("delete from network_storage_s3_credentials");
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	/** Credencial do dono `dono`, com PUT/GET no próprio bucket. */
	private String credencial(String dono) {
		String accessKey = "AKIAENOISQUOT"
			+ UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
		jdbc.update("""
			insert into network_storage_s3_credentials (id, access_key, secret_hash, secret_encrypted, owner_user_id,
				allowed_bucket, allowed_operations, created_at)
			values (?, ?, ?, ?, ?, null, 'GET PUT LIST DELETE', ?)
			""", "s3c_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12), accessKey,
			com.socialcoisa.rede.shared.security.Hashes.sha256Hex(SECRET), cipher.encrypt(SECRET), dono,
			java.sql.Timestamp.from(Instant.parse("2026-11-01T00:00:00Z")));
		return accessKey;
	}

	private static String sha(String texto) {
		return com.socialcoisa.rede.shared.security.Hashes.sha256Hex(texto);
	}

	/** PUT assinado como um SDK: cabeçalhos do SigV4 e o corpo do cliente. */
	private static MockHttpServletRequestBuilder putAssinado(String bucket, String key, String accessKey,
			String conteudo) {
		MockHttpServletRequestBuilder put = put("/s3/" + bucket + "/" + key)
			.content(conteudo.getBytes(StandardCharsets.UTF_8)).contentType("text/plain");
		String amzDate = AMZ.format(agora());
		String dateStamp = amzDate.substring(0, 8);
		String payloadHash = sha(conteudo);
		put.header("host", "localhost").header("x-amz-date", amzDate).header("x-amz-content-sha256", payloadHash)
			.header(HttpHeaders.AUTHORIZATION, assinar(put, payloadHash, accessKey, amzDate, dateStamp));
		return put;
	}

	/**
	 * A assinatura no formato do SDK: o caminho assinado é o do S3 ({@code /bucket/key}), sem o
	 * prefixo {@code /s3} da instalação — o mesmo critério do {@code S3GatewayTests}.
	 */
	private static String assinar(MockHttpServletRequestBuilder builder, String payloadHash, String accessKey,
			String amzDate, String dateStamp) {
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("host", "localhost");
		headers.put("x-amz-content-sha256", payloadHash);
		headers.put("x-amz-date", amzDate);
		String signedHeaders = "host;x-amz-content-sha256;x-amz-date";
		String uri = builder.buildRequest(null).getRequestURI();
		String caminho = uri.startsWith(S3GatewayController.PREFIX)
			? uri.substring(S3GatewayController.PREFIX.length()) : uri;
		var request = new SigV4Verifier.Request(builder.buildRequest(null).getMethod(), caminho, Map.of(), headers,
			payloadHash);
		var parsed = new SigV4Verifier.Parsed(accessKey, REGIAO, "s3", signedHeaders, "");
		String signature = SigV4Verifier.signature(request, parsed, SECRET, dateStamp);
		return "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + dateStamp + "/" + REGIAO + "/s3/aws4_request, "
			+ "SignedHeaders=" + signedHeaders + ", Signature=" + signature;
	}

	@Test
	void participante_cheio_para_no_507_e_o_vizinho_continua_gravando() throws Exception {
		var cheio = auth.authenticate("quota_s3_cheio_" + UUID.randomUUID().toString().substring(0, 8));
		var vizinho = auth.authenticate("quota_s3_vizinho_" + UUID.randomUUID().toString().substring(0, 8));
		String suf = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
		String bucketCheio = "qt" + suf;
		String bucketVizinho = "qv" + suf;
		String credCheio = credencial(cheio.userId());
		String credVizinho = credencial(vizinho.userId());

		mockMvc.perform(putAssinado(bucketCheio, "a.txt", credCheio, "123456789012")).andExpect(status().isOk());

		// A segunda gravação do mesmo dono não chega ao disco: o gateway devolve 507 com o número,
		// e não 403 (não é credencial) nem 500 (não é falha nossa).
		var recusa = mockMvc.perform(putAssinado(bucketCheio, "b.txt", credCheio, "123456789012")).andReturn();
		assertThat(recusa.getResponse().getStatus()).as(recusa.getResponse().getContentAsString()).isEqualTo(507);
		assertThat(recusa.getResponse().getContentAsString())
			.contains("<Code>QuotaExceeded</Code>").contains("bytes");

		// O vizinho, na mesma instalação, com a mesma cota, entra: cota cheia é do participante. Se a
		// cota fosse global ou por instância, este passo seria vermelho.
		mockMvc.perform(putAssinado(bucketVizinho, "c.txt", credVizinho, "123456789012"))
			.andExpect(status().isOk());
	}
}
