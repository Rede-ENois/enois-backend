package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.network.application.SigV4Verifier;
import com.socialcoisa.rede.shared.security.Hashes;
import com.socialcoisa.rede.shared.security.SecretCipher;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

/**
 * Multipart Upload do gateway S3 (V365).
 *
 * <p><b>O defeito que estes testes fixam:</b> o gateway recusava {@code CreateMultipartUpload} com
 * 501. O SDK de qualquer linguagem usa multipart por padrão acima de 5–8 MB — então um arquivo
 * grande enviado por SDK falhava, não por falta de espaço, mas por falta de protocolo. A correção
 * não é "aceitar e gravar o arquivo inteiro": é montar o corpo das partes, na ordem que o cliente
 * pediu, e só então gravar.</p>
 *
 * <p>As asserções discriminam: parte ausente recusa a conclusão (não serve arquivo truncado), parte
 * pequena que não é a última recusa (o mínimo de 5 MiB do S3), reenvio de parte substitui, e o
 * objeto só aparece no catálogo depois da conclusão.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class S3MultipartTests {

	private static final String SECRET = "segredo-do-cenario-multipart";
	private static final String ACCESS_KEY_PREFIX = "AKIAENOISMPU";
	private static final DateTimeFormatter AMZ = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
		.withZone(ZoneOffset.UTC);
	private static final Clock CLOCK = Clock.systemUTC();

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private SecretCipher cipher;

	@Autowired
	private tools.jackson.databind.ObjectMapper objectMapper;

	private String accessKey;
	private String ownerUserId;
	private String bucket;

	@BeforeEach
	void setUp() throws Exception {
		jdbc.update("delete from network_storage_multipart_parts");
		jdbc.update("delete from network_storage_multipart_uploads");
		var conta = new TestAuthHelper(mockMvc, objectMapper).authenticate("mpu_cenario_"
			+ UUID.randomUUID().toString().replace("-", "").substring(0, 8));
		ownerUserId = conta.userId();
		accessKey = ACCESS_KEY_PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
		bucket = "mpu" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
		jdbc.update("""
			insert into network_storage_s3_credentials (id, access_key, secret_hash, secret_encrypted, owner_user_id,
				allowed_bucket, allowed_operations, created_at)
			values (?, ?, ?, ?, ?, null, 'GET PUT LIST DELETE', ?)
			""", "s3c_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12), accessKey,
			Hashes.sha256Hex(SECRET), cipher.encrypt(SECRET), ownerUserId,
			java.sql.Timestamp.from(Instant.parse("2026-11-01T00:00:00Z")));
	}

	@Test
	void o_arquivo_montado_das_partes_volta_inteiro_no_get() throws Exception {
		String chave = "videos/%s.mp4".formatted(sufixo());
		String uploadId = abrir(chave);
		byte[] parte1 = bytes('a', 6 * 1024 * 1024);
		byte[] parte2 = "final-pequeno".getBytes(StandardCharsets.UTF_8);

		String etag1 = enviarParte(chave, uploadId, 1, parte1);
		String etag2 = enviarParte(chave, uploadId, 2, parte2);

		String conclusao = concluir(chave, uploadId, 1, 2);
		assertThat(conclusao).contains("<Key>" + chave + "</Key>");

		// O que o GET devolve é a concatenação exata — não "as partes que chegaram".
		// O GET do gateway responde em streaming (`StreamingResponseBody`): o MockMvc devolve o
		// resultado assíncrono, que precisa de um segundo dispatch para o corpo ser escrito.
		var leitura = mockMvc.perform(assinar(get("/s3/" + bucket + "/" + chave), shaVazio()))
			.andExpect(request().asyncStarted()).andReturn();
		leitura = mockMvc.perform(asyncDispatch(leitura)).andExpect(status().isOk()).andReturn();
		byte[] servido = leitura.getResponse().getContentAsByteArray();
		assertThat(servido.length).isEqualTo(parte1.length + parte2.length);
		assertThat(servido[0]).isEqualTo((byte) 'a');
		assertThat(new String(servido, parte1.length, parte2.length, StandardCharsets.UTF_8))
			.isEqualTo("final-pequeno");
		assertThat(etag1).isNotEqualTo(etag2);
	}

	@Test
	void parte_fora_de_ordem_e_reenvio_substituem_sem_perder_o_conteudo() throws Exception {
		String chave = "outros/%s.bin".formatted(sufixo());
		String uploadId = abrir(chave);
		byte[] p1 = bytes('b', 6 * 1024 * 1024);
		byte[] p2 = bytes('c', 6 * 1024 * 1024);
		byte[] p3 = "ultima".getBytes(StandardCharsets.UTF_8);

		// Chegada 3, 1, 2 — a ordem é o número, não a chegada.
		enviarParte(chave, uploadId, 3, p3);
		enviarParte(chave, uploadId, 1, p1);
		enviarParte(chave, uploadId, 2, p2);
		// Reenvio da 2 com conteúdo diferente antes da conclusão: substitui.
		byte[] p2Novo = bytes('d', 6 * 1024 * 1024);
		enviarParte(chave, uploadId, 2, p2Novo);

		concluir(chave, uploadId, 1, 2, 3);

		var leitura = mockMvc.perform(assinar(get("/s3/" + bucket + "/" + chave), shaVazio()))
			.andExpect(request().asyncStarted()).andReturn();
		byte[] servido = mockMvc.perform(asyncDispatch(leitura)).andExpect(status().isOk()).andReturn()
			.getResponse().getContentAsByteArray();
		assertThat(servido.length).isEqualTo(p1.length + p2Novo.length + p3.length);
		assertThat(servido[p1.length]).isEqualTo((byte) 'd');
	}

	@Test
	void parte_ausente_recusa_a_conclusao_e_o_upload_continua_aberto() throws Exception {
		String chave = "outros/%s.bin".formatted(sufixo());
		String uploadId = abrir(chave);
		enviarParte(chave, uploadId, 1, bytes('e', 6 * 1024 * 1024));

		var recusa = mockMvc.perform(assinar(post("/s3/" + bucket + "/" + chave).param("uploadId", uploadId),
			shaDe(xmlDePartes(1, 2))).contentType("application/xml")
			.content(xmlDePartes(1, 2))).andReturn();
		assertThat(recusa.getResponse().getStatus()).isEqualTo(400);
		assertThat(recusa.getResponse().getContentAsString()).contains("InvalidPart");
		// O upload não foi abortado: o cliente reenvia a 2 e conclui.
		enviarParte(chave, uploadId, 2, "fim".getBytes(StandardCharsets.UTF_8));
		concluir(chave, uploadId, 1, 2);
	}

	@Test
	void parte_pequena_que_nao_e_a_ultima_e_recusada() throws Exception {
		String chave = "outros/%s.bin".formatted(sufixo());
		String uploadId = abrir(chave);
		enviarParte(chave, uploadId, 1, "curta".getBytes(StandardCharsets.UTF_8));
		enviarParte(chave, uploadId, 2, "tambem-curta".getBytes(StandardCharsets.UTF_8));

		var recusa = mockMvc.perform(assinar(post("/s3/" + bucket + "/" + chave).param("uploadId", uploadId),
			shaDe(xmlDePartes(1, 2))).contentType("application/xml")
			.content(xmlDePartes(1, 2))).andReturn();
		assertThat(recusa.getResponse().getStatus()).isEqualTo(400);
		assertThat(recusa.getResponse().getContentAsString()).contains("EntityTooSmall");
	}

	@Test
	void antes_da_conclusao_a_chave_nao_existe_no_catalogo() throws Exception {
		String chave = "outros/%s.bin".formatted(sufixo());
		String uploadId = abrir(chave);
		enviarParte(chave, uploadId, 1, "conteudo".getBytes(StandardCharsets.UTF_8));

		assertThat(jdbc.queryForObject("select count(*) from network_storage_objects where bucket = ? and object_key = ?",
			Integer.class, bucket, chave)).isZero();
	}

	@Test
	void upload_de_outra_conta_nao_aceita_parte_nem_conclusao() throws Exception {
		String chave = "outros/%s.bin".formatted(sufixo());
		String uploadId = abrir(chave);

		String outraChave;
		do {
			outraChave = ACCESS_KEY_PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 10)
				.toUpperCase();
		}
		while (outraChave.equals(accessKey));
		jdbc.update("""
			insert into network_storage_s3_credentials (id, access_key, secret_hash, secret_encrypted, owner_user_id,
				allowed_bucket, allowed_operations, created_at)
			values (?, ?, ?, ?, ?, null, 'GET PUT LIST DELETE', ?)
			""", "s3c_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12), outraChave,
			Hashes.sha256Hex(SECRET), cipher.encrypt(SECRET), "outra_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
			java.sql.Timestamp.from(Instant.parse("2026-11-01T00:00:00Z")));

		String anterior = accessKey;
		accessKey = outraChave;
		try {
			var recusa = mockMvc.perform(assinar(put("/s3/" + bucket + "/" + chave).param("partNumber", "1")
				.param("uploadId", uploadId).content("x".getBytes(StandardCharsets.UTF_8)),
				shaDe("x"))).andReturn();
			assertThat(recusa.getResponse().getStatus()).isEqualTo(404);
			assertThat(recusa.getResponse().getContentAsString()).contains("NoSuchUpload");
		}
		finally {
			accessKey = anterior;
		}
	}

	@Test
	void abortar_apaga_as_partes_e_o_upload_deixa_de_existir() throws Exception {
		String chave = "outros/%s.bin".formatted(sufixo());
		String uploadId = abrir(chave);
		enviarParte(chave, uploadId, 1, "conteudo".getBytes(StandardCharsets.UTF_8));

		mockMvc.perform(assinar(delete("/s3/" + bucket + "/" + chave).param("uploadId", uploadId), shaVazio()))
			.andExpect(status().isNoContent());
		assertThat(jdbc.queryForObject("select count(*) from network_storage_multipart_parts where upload_id = ?",
			Integer.class, uploadId)).isZero();

		var depois = mockMvc.perform(assinar(put("/s3/" + bucket + "/" + chave).param("partNumber", "1")
			.param("uploadId", uploadId).content("x".getBytes(StandardCharsets.UTF_8)),
			shaDe("x"))).andReturn();
		assertThat(depois.getResponse().getStatus()).isEqualTo(404);
	}

	// ------------------------------------------------------------------ apoio

	private String abrir(String chave) throws Exception {
		var resposta = mockMvc.perform(assinar(post("/s3/" + bucket + "/" + chave).param("uploads", "")
			.contentType("video/mp4"), shaVazio())).andReturn();
		String corpo = resposta.getResponse().getContentAsString();
		assertThat(resposta.getResponse().getStatus()).as(corpo).isEqualTo(200);
		// O XML é serializado com namespace e prefixo: `<UploadId xmlns="...">x</UploadId>`. Um regex
		// `[^<]+` não casa com o atributo e devolveria o documento inteiro como "id".
		// O prefixo pode ser `ns2:` — o writer declara o namespace e reescreve o elemento.
		java.util.regex.Matcher m = java.util.regex.Pattern
			.compile("<(?:\\w+:)?UploadId[^>]*>([^<]+)</(?:\\w+:)?UploadId>").matcher(corpo);
		assertThat(m.find()).as(corpo).isTrue();
		return m.group(1);
	}

	private String enviarParte(String chave, String uploadId, int numero, byte[] conteudo) throws Exception {
		String sha = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(conteudo));
		MockHttpServletRequestBuilder requisicao = put("/s3/" + bucket + "/" + chave).content(conteudo)
			.param("partNumber", String.valueOf(numero)).param("uploadId", uploadId);
		var resposta = mockMvc.perform(assinar(requisicao, shaOfBytes(conteudo))).andReturn();
		assertThat(resposta.getResponse().getStatus()).as(resposta.getResponse().getContentAsString())
			.isEqualTo(200);
		return resposta.getResponse().getHeader("ETag");
	}

	private String concluir(String chave, String uploadId, int... partes) throws Exception {
		String xml = xmlDePartes(partes);
		String sha = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
			.digest(xml.getBytes(StandardCharsets.UTF_8)));
		var resposta = mockMvc.perform(assinar(post("/s3/" + bucket + "/" + chave).param("uploadId", uploadId)
			.contentType("application/xml").content(xml), sha)).andReturn();
		assertThat(resposta.getResponse().getStatus()).isEqualTo(200);
		return resposta.getResponse().getContentAsString();
	}

	private static String xmlDePartes(int... partes) {
		StringBuilder xml = new StringBuilder("<CompleteMultipartUpload><Part>");
		for (int i = 0; i < partes.length; i++) {
			if (i > 0) {
				xml.append("</Part><Part>");
			}
			xml.append("<PartNumber>").append(partes[i]).append("</PartNumber>");
		}
		return xml.append("</Part></CompleteMultipartUpload>").toString();
	}

	private static byte[] bytes(char c, int tamanho) {
		byte[] dados = new byte[tamanho];
		java.util.Arrays.fill(dados, (byte) c);
		return dados;
	}

	private static String sufixo() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}

	private MockHttpServletRequestBuilder assinar(MockHttpServletRequestBuilder builder, String payloadHash) {
		String amzDate = AMZ.format(CLOCK.instant());
		builder.header("x-amz-date", amzDate).header("x-amz-content-sha256", payloadHash)
			.header(HttpHeaders.AUTHORIZATION, authorization(builder, payloadHash, amzDate));
		return builder;
	}

	private String authorization(MockHttpServletRequestBuilder builder, String payloadHash, String amzDate) {
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("host", "localhost");
		headers.put("x-amz-content-sha256", payloadHash);
		headers.put("x-amz-date", amzDate);
		String signedHeaders = "host;x-amz-content-sha256;x-amz-date";
		String uri = builder.buildRequest(null).getRequestURI();
		var request = new SigV4Verifier.Request(builder.buildRequest(null).getMethod(),
			uri.startsWith("/s3") ? uri.substring(3) : uri, consulta(builder), headers, payloadHash);
		var parsed = new SigV4Verifier.Parsed(accessKey, "enois-1", "s3", signedHeaders, "");
		String signature = SigV4Verifier.signature(request, parsed, SECRET, amzDate.substring(0, 8));
		return "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + amzDate.substring(0, 8)
			+ "/enois-1/s3/aws4_request, SignedHeaders=" + signedHeaders + ", Signature=" + signature;
	}

	private static Map<String, String> consulta(MockHttpServletRequestBuilder builder) {
		String query = builder.buildRequest(null).getQueryString();
		Map<String, String> out = new LinkedHashMap<>();
		if (query == null) {
			// `.param(...)` não vira query string no MockMvc, mas é o que o servidor enxerga como parâmetro:
			// o cliente real manda os dois na mesma URL, e o servidor canonicaliza pelos parâmetros.
			builder.buildRequest(null).getParameterMap().forEach((nome, valores) -> {
				if (valores != null && valores.length > 0) {
					out.put(nome, valores[0]);
				}
			});
			return out;
		}
		for (String pair : query.split("&")) {
			int eq = pair.indexOf('=');
			out.put(eq < 0 ? pair : pair.substring(0, eq), eq < 0 ? "" : java.net.URLDecoder.decode(
				pair.substring(eq + 1), StandardCharsets.UTF_8));
		}
		return out;
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

	private static String shaOfBytes(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (java.security.NoSuchAlgorithmException falha) {
			throw new IllegalStateException(falha);
		}
	}

	private static String shaVazio() {
		return shaDe("");
	}
}