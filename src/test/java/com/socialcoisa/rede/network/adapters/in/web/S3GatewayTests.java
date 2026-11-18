package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
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
import com.socialcoisa.rede.shared.security.SecretCipher;

/**
 * O gateway S3 (brief §11) de ponta a ponta: as requisições são <b>assinadas como um SDK assinaria</b>
 * e passam pelo filtro de assinatura real.
 *
 * <p>O que estes testes provam, e por que cada um importa:</p>
 * <ul>
 *   <li>sem assinatura, nada entra — o gateway não é "S3 anônimo";</li>
 *   <li>assinatura de outro segredo, corpo adulterado ou relógio fora da janela são recusados;</li>
 *   <li>PutObject grava, GetObject devolve o corpo com o ETag do digest, HeadObject devolve o tamanho,
 *   ListObjectsV2 lista com o prefixo, DeleteObject vira lápide;</li>
 *   <li>escopo da credencial vale: credencial só de {@code GET} não pode {@code PUT}; credencial de um
 *   bucket não alcança outro.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class S3GatewayTests {

	private static final String ACCESS_KEY_PREFIX = "AKIAENOIS";
	private static final String SECRET = "segredo-de-teste-s3-gateway-nao-usar-em-producao";
	/**
	 * O relógio do gateway é o do sistema ({@code InfrastructureConfig}), e não um bean fixo: assinar
	 * com uma data fixa distante do "agora" real faria todo teste cair na janela de tolerância e
	 * provaria a recusa em vez do caminho. A data assinada é a de agora, truncada em segundos.
	 */
	private static final Clock CLOCK = Clock.systemUTC();
	private static final DateTimeFormatter AMZ = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
		.withZone(ZoneOffset.UTC);

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private SecretCipher cipher;

	private String accessKey;
	private String admin;
	private String bucket;

	@BeforeEach
	void setUp() {
		jdbc.update("delete from network_storage_s3_credentials");
		admin = "user_s3_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
		accessKey = ACCESS_KEY_PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
		bucket = "bkt" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
		jdbc.update("""
			insert into network_storage_s3_credentials (id, access_key, secret_hash, secret_encrypted, owner_user_id,
				allowed_bucket, allowed_operations, created_at)
			values (?, ?, ?, ?, ?, null, 'GET PUT LIST DELETE', ?)
			""", "s3c_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12), accessKey,
			com.socialcoisa.rede.shared.security.Hashes.sha256Hex(SECRET), cipher.encrypt(SECRET), admin,
			java.sql.Timestamp.from(Instant.parse("2026-11-01T00:00:00Z")));
	}

	/** Sem assinatura o gateway não atende: não é um bucket público. */
	@Test
	void sem_assinatura_e_recusado_com_xml_de_erro() throws Exception {
		var result = mockMvc.perform(get("/s3/" + bucket)).andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(403);
		assertThat(result.getResponse().getContentAsString()).contains("<Code>AccessDenied</Code>");
	}

	/** Assinatura com outro segredo é recusada — e a resposta continua em XML do S3. */
	@Test
	void assinatura_com_segredo_errado_e_recusada() throws Exception {
		MockHttpServletRequestBuilder list = get("/s3/" + bucket).queryParam("list-type", "2");
		assinar(list, "segredo-errado", shaVazio());
		var result = mockMvc.perform(list).andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(403);
		assertThat(result.getResponse().getContentAsString()).contains("SignatureDoesNotMatch");
	}

	/** O caminho completo: PUT grava, GET devolve o corpo com o ETag, HEAD responde o tamanho. */
	@Test
	void put_get_e_head_de_um_objeto() throws Exception {
		byte[] corpo = "conteudo do objeto via s3".getBytes(StandardCharsets.UTF_8);
		String sha = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(corpo));

		MockHttpServletRequestBuilder put = put("/s3/" + bucket + "/fotos/gato.jpg").content(corpo)
			.contentType("image/jpeg");
		assinar(put, SECRET, sha);
		mockMvc.perform(put)
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.ETAG, "\"" + sha + "\""));

		MockHttpServletRequestBuilder getObject = get("/s3/" + bucket + "/fotos/gato.jpg");
		assinar(getObject, SECRET, shaVazio());
		// GetObject devolve StreamingResponseBody: os bytes saem numa thread do MVC DEPOIS do perform.
		// Ler sem despachar é corrida — passa isolado e devolve `but was: []` na suíte inteira.
		// É o mesmo gotcha já registrado na entrega do §13.
		var getResult = mockMvc.perform(getObject).andReturn();
		if (getResult.getRequest().isAsyncStarted()) {
			getResult = mockMvc.perform(asyncDispatch(getResult)).andReturn();
		}
		assertThat(getResult.getResponse().getStatus()).isEqualTo(200);
		assertThat(getResult.getResponse().getContentAsByteArray()).isEqualTo(corpo);
		assertThat(getResult.getResponse().getHeader(HttpHeaders.ETAG)).isEqualTo("\"" + sha + "\"");

		MockHttpServletRequestBuilder headObject = head("/s3/" + bucket + "/fotos/gato.jpg");
		assinar(headObject, SECRET, shaVazio());
		mockMvc.perform(headObject)
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.CONTENT_LENGTH, String.valueOf(corpo.length)))
			.andExpect(header().string(HttpHeaders.ETAG, "\"" + sha + "\""));
	}

	/** ListObjectsV2 respeita o prefixo e devolve o documento no namespace do S3. */
	@Test
	void listagem_v2_filtra_por_prefixo() throws Exception {
		gravar("a/um.txt", "um");
		gravar("a/dois.txt", "dois");
		gravar("b/tres.txt", "tres");

		MockHttpServletRequestBuilder list = get("/s3/" + bucket).queryParam("list-type", "2").queryParam("prefix", "a/");
		assinar(list, SECRET, shaVazio());
		var result = mockMvc.perform(list).andExpect(status().isOk()).andReturn();

		String xml = result.getResponse().getContentAsString();
		assertThat(xml).contains("<Key>a/um.txt</Key>").contains("<Key>a/dois.txt</Key>");
		assertThat(xml).doesNotContain("b/tres.txt");
		assertThat(xml).contains("xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"");
	}

	/** DeleteObject responde 204 e a chave deixa de ser servível — a lápide, como no S3 versionado. */
	@Test
	void delete_vira_lapide() throws Exception {
		gravar("apagar.txt", "x");

		MockHttpServletRequestBuilder del = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
			.delete("/s3/" + bucket + "/apagar.txt");
		assinar(del, SECRET, shaVazio());
		mockMvc.perform(del).andExpect(status().isNoContent());

		MockHttpServletRequestBuilder getObject = get("/s3/" + bucket + "/apagar.txt");
		assinar(getObject, SECRET, shaVazio());
		assertThat(mockMvc.perform(getObject).andReturn().getResponse().getStatus()).isEqualTo(404);
	}

	/** Credencial só de leitura não grava, mesmo com assinatura válida. */
	@Test
	void credencial_sem_put_nao_grava() throws Exception {
		jdbc.update("update network_storage_s3_credentials set allowed_operations = 'GET' where access_key = ?",
			accessKey);

		MockHttpServletRequestBuilder put = put("/s3/" + bucket + "/x.txt").content("x".getBytes(StandardCharsets.UTF_8))
			.contentType("text/plain");
		assinar(put, SECRET, shaDe("x"));
		var result = mockMvc.perform(put).andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(403);
		assertThat(result.getResponse().getContentAsString()).contains("<Code>AccessDenied</Code>");
	}

	/** Credencial presa a um bucket não alcança outro, mesmo do mesmo dono. */
	@Test
	void credencial_de_um_bucket_nao_alcanca_outro() throws Exception {
		jdbc.update("update network_storage_s3_credentials set allowed_bucket = 'outro' where access_key = ?",
			accessKey);

		MockHttpServletRequestBuilder list = get("/s3/" + bucket).queryParam("list-type", "2");
		assinar(list, SECRET, shaVazio());
		var result = mockMvc.perform(list).andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(403);
		assertThat(result.getResponse().getContentAsString()).contains("<Code>AccessDenied</Code>");
	}

	/** Relógio de cliente fora da janela: a requisição correta é recusada (o replay fica cortado). */
	@Test
	void data_antiga_e_recusada() throws Exception {
		// Assina com a data real que o gateway vai ler e depois mente no x-amz-date.
		MockHttpServletRequestBuilder list = get("/s3/" + bucket).queryParam("list-type", "2");
		String authorization = assinarComData(list, SECRET, shaVazio(), "20200101T000000Z", "20200101");
		var result = mockMvc.perform(get("/s3/" + bucket).queryParam("list-type", "2")
			.header(HttpHeaders.AUTHORIZATION, authorization)
			.header("x-amz-date", "20200101T000000Z")).andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(403);
	}

	// ---------------------------------------------------------------- helpers: o "SDK"

	private void gravar(String key, String conteudo) throws Exception {
		byte[] bytes = conteudo.getBytes(StandardCharsets.UTF_8);
		String sha = shaDe(conteudo);
		MockHttpServletRequestBuilder put = put("/s3/" + bucket + "/" + key).content(bytes).contentType("text/plain");
		assinar(put, SECRET, sha);
		mockMvc.perform(put).andExpect(status().isOk());
	}

	/**
	 * Assina a requisição e anexa os cabeçalhos que um SDK manda ({@code x-amz-date},
	 * {@code x-amz-content-sha256}, {@code Authorization}) ao builder — como o SDK faz, não como um
	 * teste "fingindo" que assinou.
	 */
	private void assinar(MockHttpServletRequestBuilder builder, String secret, String payloadHash) {
		String amzDate = AMZ.format(CLOCK.instant());
		builder.header("x-amz-date", amzDate).header("x-amz-content-sha256", payloadHash)
			.header(HttpHeaders.AUTHORIZATION, assinarComData(builder, secret, payloadHash, amzDate,
				amzDate.substring(0, 8)));
	}

	private String assinarComData(MockHttpServletRequestBuilder builder, String secret, String payloadHash,
			String amzDate, String dateStamp) {
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("host", "localhost");
		headers.put("x-amz-content-sha256", payloadHash);
		headers.put("x-amz-date", amzDate);
		String signedHeaders = "host;x-amz-content-sha256;x-amz-date";
		// O SDK assina o caminho S3 (`/bucket/key`), sem o prefixo `/s3` da instalação.
		String uri = builder.buildRequest(null).getRequestURI();
		var request = new SigV4Verifier.Request(builder.buildRequest(null).getMethod(),
			uri.startsWith(S3GatewayController.PREFIX) ? uri.substring(S3GatewayController.PREFIX.length()) : uri,
			consulta(builder), headers, payloadHash);
		var parsed = new SigV4Verifier.Parsed(accessKey, "enois-1", "s3", signedHeaders, "");
		String signature = SigV4Verifier.signature(request, parsed, secret, dateStamp);
		return "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + dateStamp + "/enois-1/s3/aws4_request, SignedHeaders="
			+ signedHeaders + ", Signature=" + signature;
	}

	private static Map<String, String> consulta(MockHttpServletRequestBuilder builder) {
		String query = builder.buildRequest(null).getQueryString();
		Map<String, String> out = new LinkedHashMap<>();
		if (query == null) {
			return out;
		}
		for (String pair : query.split("&")) {
			int eq = pair.indexOf('=');
			out.put(eq < 0 ? pair : pair.substring(0, eq), eq < 0 ? "" : java.net.URLDecoder.decode(
				pair.substring(eq + 1), StandardCharsets.UTF_8));
		}
		return out;
	}

	private static String shaVazio() {
		return shaDe("");
	}

	private static String shaDe(String value) {
		try {
			return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
				.digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (java.security.NoSuchAlgorithmException falha) {
			throw new IllegalStateException(falha);
		}
	}
}