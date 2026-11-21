package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
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
 * A paginação do {@code ListObjects}: {@code IsTruncated} diz a verdade.
 *
 * <p>O S3 trunca quando <b>há mais</b>, não quando a página veio cheia. O gateway decidia por
 * {@code found.size() == maxKeys}, que confunde as duas coisas: um bucket com exatamente
 * {@code max-keys} objetos saía com {@code IsTruncated=true} e um {@code NextContinuationToken}, e o
 * cliente — SDK, sincronizador, {@code aws s3 sync} — pedia a página seguinte, recebia zero, e às
 * vezes registrava um erro de "token inválido" no meio do caminho. O custo é uma requisição a mais em
 * todo diretório do tamanho exato da página; o preço é o cliente não saber quando terminou.</p>
 *
 * <p>O discriminante é a página <b>exatamente cheia</b> e a última: {@code IsTruncated=false} e
 * nenhum token. Para não pagar o custo no caso comum, o serviço pede {@code maxKeys + 1} linha ao
 * banco: se vier a extra, há próxima página; se não vier, a atual é a última.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class S3ListPaginationTests {

	private static final String SECRET = "segredo-do-cenario-de-paginacao";
	private static final String PREFIX = "AKIAENOISPAG";
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

	@BeforeEach
	void setUp() throws Exception {
		var conta = new TestAuthHelper(mockMvc, objectMapper).authenticate("s3_pag_"
			+ UUID.randomUUID().toString().replace("-", "").substring(0, 8));
		accessKey = PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
		bucket = "pag" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
		jdbc.update("""
			insert into network_storage_s3_credentials (id, access_key, secret_hash, secret_encrypted, owner_user_id,
				allowed_bucket, allowed_operations, created_at)
			values (?, ?, ?, ?, ?, null, 'GET PUT LIST DELETE', ?)
			""", "s3c_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12), accessKey,
			Hashes.sha256Hex(SECRET), cipher.encrypt(SECRET), conta.userId(),
			java.sql.Timestamp.from(Instant.parse("2026-11-01T00:00:00Z")));
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
			uri.startsWith("/s3") ? uri.substring(3) : uri, consulta(builder), headers, payloadHash);
		var parsed = new SigV4Verifier.Parsed(accessKey, "enois-1", "s3", signedHeaders, "");
		String signature = SigV4Verifier.signature(request, parsed, SECRET, amzDate.substring(0, 8));
		builder.header(HttpHeaders.AUTHORIZATION, "AWS4-HMAC-SHA256 Credential=" + accessKey + "/"
			+ amzDate.substring(0, 8) + "/enois-1/s3/aws4_request, SignedHeaders=" + signedHeaders
			+ ", Signature=" + signature);
		return builder;
	}

	/**
	 * A query que o servidor canonicaliza: o {@code .param(...)} do MockMvc não vira query string,
	 * mas o cliente real manda os dois na mesma URL — o teste assina o que o servidor enxerga.
	 */
	private static Map<String, String> consulta(MockHttpServletRequestBuilder builder) {
		String query = builder.buildRequest(null).getQueryString();
		Map<String, String> out = new LinkedHashMap<>();
		if (query == null) {
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

	private void gravar(String chave) throws Exception {
		byte[] corpo = chave.getBytes(StandardCharsets.UTF_8);
		String sha = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(corpo));
		var r = mockMvc.perform(assinar(put("/s3/" + bucket + "/" + chave).content(corpo), sha)).andReturn();
		assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
	}

	/** As chaves de uma página, na ordem do documento. */
	private static List<String> chaves(String xml) {
		Matcher m = Pattern.compile("<Key>([^<]*)</Key>").matcher(xml);
		List<String> out = new ArrayList<>();
		while (m.find()) {
			out.add(m.group(1));
		}
		return out;
	}

	/**
	 * Listar <b>arquivando</b> a mesma chave no meio da paginação: o cursor é a chave, então a chave
	 * gravada de novo muda de lugar na ordem e uma chave pode cair antes do cursor.
	 *
	 * <p><b>Antes:</b> a listagem só excluía lápide ({@code state <> 'DELETED'}), e a chave
	 * reescrita volta a {@code PENDING} — servível na listagem, mas ainda não dá para baixar. Se isso
	 * acontece com uma chave <b>antes</b> do cursor, ela sai <b>depois</b> dele na ordem, e a página
	 * seguinte nunca a alcança: o sincronizador termina o laço e a chave fica fora da cópia local até
	 * a próxima passada inteira. Registro que chegou pela metade é o que uma sincronização não pode
	 * ter.</p>
	 *
	 * <p><b>Agora:</b> {@code PENDING} fica de fora da listagem, como o {@code GET} já recusava. A
	 * chave reaparece quando a gravação termina, e nenhuma página entrega o que não abre.</p>
	 */
	@Test
	void chave_reescrita_no_meio_da_paginacao_nao_escapa_da_listagem() throws Exception {
		for (String nome : List.of("a.jpg", "b.jpg", "c.jpg", "d.jpg")) {
			gravar(nome);
		}

		// `a.jpg` é regravada como o `beginUpload` faz: a linha nova chega em PENDING e a antiga sai
		// da chave com `superseded_by`, sem virar lápide. O cursor da primeira página é `b.jpg`,
		// então `a.jpg` está na ordem ANTES do cursor.
		String idNovo = "sto_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		String autor = jdbc.queryForObject(
			"select created_by from network_storage_objects where object_key = 'a.jpg' and bucket = ?",
			String.class, bucket);
		// A versão corrente sai da chave primeiro (uma corrente por chave), e a nova entra em PENDING.
		jdbc.update("update network_storage_objects set superseded_by = ?, updated_at = now()"
			+ " where bucket = ? and object_key = 'a.jpg'", idNovo, bucket);
		jdbc.update("""
			insert into network_storage_objects (id, bucket, object_key, content_type, state, created_by, created_at, updated_at)
			values (?, ?, 'a.jpg', 'application/octet-stream', 'PENDING', ?, now(), now())
			""", idNovo, bucket, autor);

		String primeira = pagina("2", null);
		assertThat(chaves(primeira)).containsExactly("b.jpg", "c.jpg");
		String segunda = pagina("2", tokenDe(primeira));

		// Nenhuma página entrega a chave que não abre — nem em lugar nenhum depois do cursor — e
		// nenhuma chave aparece duas vezes na mesma página (o que travava o laço do cliente).
		assertThat(chaves(primeira)).containsExactly("b.jpg", "c.jpg");
		assertThat(chaves(segunda)).containsExactly("d.jpg");
		assertThat(segunda).contains("<IsTruncated>false</IsTruncated>");

		// O que reapareceu não é só a linha: o download da chave listada tem de funcionar. (A linha
		// gravada aqui é sintética, então o GET acusa a cópia ausente — o que se prova é que a
		// listagem nunca entrega o que o GET recusaria por *ainda não existir*.)
		jdbc.update("update network_storage_objects set state = 'STORED', bytes = 3, sha256 = sha256"
			+ " where state = 'PENDING' and bucket = ? and object_key = 'a.jpg'", bucket);
		var completo = pagina("10", null);
		assertThat(chaves(completo)).containsExactly("a.jpg", "b.jpg", "c.jpg", "d.jpg");
	}

	private String pagina(String maxKeys, String token) throws Exception {
		MockHttpServletRequestBuilder pedido = get("/s3/" + bucket).param("list-type", "2")
			.param("max-keys", maxKeys);
		if (token != null) {
			pedido.param("continuation-token", token);
		}
		var r = mockMvc.perform(assinar(pedido, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"))
			.andReturn();
		assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
		return r.getResponse().getContentAsString();
	}

	private static String tokenDe(String xml) {
		Matcher m = Pattern.compile("<NextContinuationToken>([^<]*)</NextContinuationToken>").matcher(xml);
		return m.find() ? m.group(1) : null;
	}

	@Test
	void pagina_exatamente_cheia_e_ultima_nao_diz_que_ha_mais() throws Exception {
		for (String nome : List.of("a.jpg", "b.jpg", "c.jpg")) {
			gravar(nome);
		}

		String xml = pagina("3", null);

		assertThat(chaves(xml)).containsExactly("a.jpg", "b.jpg", "c.jpg");
		assertThat(xml).as("tres de tres: nao ha proxima pagina").contains("<IsTruncated>false</IsTruncated>");
		assertThat(tokenDe(xml)).as("sem token quando nao ha proxima pagina").isNull();
	}

	@Test
	void pagina_cheia_com_mais_um_item_diz_que_ha_mais_e_a_seguinte_conclui() throws Exception {
		for (String nome : List.of("a.jpg", "b.jpg", "c.jpg", "d.jpg")) {
			gravar(nome);
		}

		String primeira = pagina("3", null);
		assertThat(chaves(primeira)).containsExactly("a.jpg", "b.jpg", "c.jpg");
		assertThat(primeira).contains("<IsTruncated>true</IsTruncated>");
		String token = tokenDe(primeira);
		assertThat(token).as("ha proxima pagina: o token tem de vir").isNotNull();

		String segunda = pagina("3", token);
		assertThat(chaves(segunda)).containsExactly("d.jpg");
		assertThat(segunda).as("a ultima pagina e a ultima").contains("<IsTruncated>false</IsTruncated>");
		assertThat(tokenDe(segunda)).isNull();
	}

	/**
	 * Bucket não existe enquanto não tem objeto (não há tabela de buckets): com um só objeto, a
	 * página com folga é a última. É o caso mais comum de todos, e o {@code IsTruncated} tem de
	 * dizer a verdade nele também.
	 */
	@Test
	void pagina_com_folga_e_ultima() throws Exception {
		gravar("unico.jpg");

		String xml = pagina("10", null);

		assertThat(chaves(xml)).containsExactly("unico.jpg");
		assertThat(xml).contains("<IsTruncated>false</IsTruncated>");
		assertThat(tokenDe(xml)).isNull();
	}
}
