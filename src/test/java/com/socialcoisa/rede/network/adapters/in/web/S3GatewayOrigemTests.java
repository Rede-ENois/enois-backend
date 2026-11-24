package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * O gateway S3 não passa pelo gate de origem: a credencial SigV4 é a porta.
 *
 * <p><b>Antes:</b> o {@code RequestOriginFilter} exige {@code Origin} (navegador) ou
 * {@code X-Client-Origin} (servidor) em toda rota, e não isentava {@code /s3}. Com
 * {@code rede.origins.enforce=true} — o valor de produção — <b>todo</b> cliente S3 de verdade
 * (aws-cli, SDK, rclone, o backup do nó) recebia:</p>
 *
 * <pre>
 * PUT /s3/&lt;b&gt;/x.txt   Authorization: AWS4-HMAC-SHA256 ...   (sem Origin)
 * → 403 ORIGIN_REQUIRED   "Toda requisicao precisa de origem cadastrada."
 * </pre>
 *
 * <p>Na suíte isso não aparecia porque os testes rodam com {@code enforce=false}. O gateway nunca
 * foi um cliente de navegador: ele tem assinatura própria, e a origem de quem chama não acrescenta
 * nada à autorização que o SigV4 já decidiu.</p>
 *
 * <p><b>Agora:</b> {@code /s3/**} está na lista de rotas com autenticação própria (§4.59) — como
 * {@code /network/}, que também fala por token. O gate de origem continua valendo para o resto da
 * API, e um cliente S3 com origem inventada não ganha nada: a assinatura é que decide.</p>
 */
@Tag("critico")
@SpringBootTest(properties = "rede.origins.enforce=true")
@AutoConfigureMockMvc
class S3GatewayOrigemTests {

	private static final String SECRET = "segredo-de-teste-do-gateway";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private com.socialcoisa.rede.shared.security.SecretCipher cipher;

	@Test
	void cliente_s3_sem_origem_usa_o_gateway_com_enforcement_ligado() throws Exception {
		var dono = new TestAuthHelper(mockMvc, objectMapper).authenticate("s3_sem_origem",
			request -> {
				request.addHeader("X-Client-Origin", "https://enois.ia.br");
				return request;
			});
		String accessKey = "AKIAENOISORIG" + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
		String bucket = "origem" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
		jdbc.update("""
			insert into network_storage_s3_credentials (id, access_key, secret_hash, secret_encrypted, owner_user_id,
				allowed_bucket, allowed_operations, created_at)
			values (?, ?, ?, ?, ?, ?, 'GET PUT', ?)
			""", "s3c_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12), accessKey,
			com.socialcoisa.rede.shared.security.Hashes.sha256Hex(SECRET), cipher.encrypt(SECRET), dono.userId(),
			bucket, java.sql.Timestamp.from(Instant.parse("2026-11-01T00:00:00Z")));

		String criado = gateway("PUT", "/s3/" + bucket, "", accessKey);
		assertThat(criado).as("o bucket sem Origin").isEqualTo("200");
		String gravado = gateway("PUT", "/s3/" + bucket + "/x.txt", "conteudo", accessKey);
		assertThat(gravado).as("o PUT sem Origin").isEqualTo("200");
		String lido = gateway("GET", "/s3/" + bucket + "/x.txt", "", accessKey);
		assertThat(lido).as("o GET sem Origin").isEqualTo("200");
	}

	/** Faz a requisição com SigV4 e SEM Origin nem X-Client-Origin, como um cliente S3 real. */
	private String gateway(String verbo, String caminho, String corpo, String accessKey) throws Exception {
		String amzDate = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
			.withZone(java.time.ZoneOffset.UTC).format(Instant.now());
		String dateStamp = amzDate.substring(0, 8);
		String payloadHash = java.util.HexFormat.of().formatHex(
			java.security.MessageDigest.getInstance("SHA-256").digest(corpo.getBytes(StandardCharsets.UTF_8)));
		MockHttpServletRequestBuilder b = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
			.request(org.springframework.http.HttpMethod.valueOf(verbo), caminho)
			.header("Host", "localhost").header("x-amz-date", amzDate).header("x-amz-content-sha256", payloadHash);
		if (!corpo.isEmpty()) {
			b.content(corpo.getBytes(StandardCharsets.UTF_8));
		}
		var pedido = new com.socialcoisa.rede.network.application.SigV4Verifier.Request(verbo,
			caminho.substring(S3GatewayController.PREFIX.length()), java.util.Map.of(),
			java.util.Map.of("host", "localhost", "x-amz-date", amzDate, "x-amz-content-sha256", payloadHash),
			payloadHash);
		var escopo = new com.socialcoisa.rede.network.application.SigV4Verifier.Parsed(accessKey, "enois-1", "s3",
			"host;x-amz-content-sha256;x-amz-date", "");
		String assinatura = com.socialcoisa.rede.network.application.SigV4Verifier.signature(pedido, escopo, SECRET,
			dateStamp);
		b.header("Authorization", "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + dateStamp
			+ "/enois-1/s3/aws4_request, SignedHeaders=host;x-amz-content-sha256;x-amz-date, Signature=" + assinatura);
		var rr = mockMvc.perform(b).andReturn();
		var resp = rr.getRequest().isAsyncStarted()
			? mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(rr))
				.andReturn().getResponse()
			: rr.getResponse();
		if (resp.getStatus() != 200) {
			System.out.println("SONDA149B[" + verbo + " " + caminho + "]=" + resp.getStatus() + " "
				+ resp.getContentAsString());
		}
		return String.valueOf(resp.getStatus());
	}
}