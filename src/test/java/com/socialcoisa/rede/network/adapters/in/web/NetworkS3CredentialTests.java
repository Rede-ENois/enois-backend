package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

/**
 * As Access Keys pelo painel (brief §11): o que antes só nascia por SQL.
 *
 * <p>O teste que importa é o que atravessa a fronteira: a credencial que o painel devolve é a
 * <b>mesma</b> que o gateway autentica com SigV4. Uma chave criada pelo painel que o SDK recusa
 * seria pior que a lacuna — o dono teria a impressão de ter acesso.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class NetworkS3CredentialTests {

	private static final String ROTA = "/network/storage/s3/credentials";
	private static final DateTimeFormatter AMZ = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
		.withZone(ZoneOffset.UTC);

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	private String bucket;
	private String sessao;

	@BeforeEach
	void setUp() throws Exception {
		bucket = "bkt" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
		sessao = new TestAuthHelper(mockMvc, objectMapper).authenticate("s3_credencial_"
			+ UUID.randomUUID().toString().replace("-", "").substring(0, 8)).token();
	}

	@Test
	void criar_nao_devolve_o_segredo_de_novo_e_o_gateway_autentica_a_chave_criada() throws Exception {
		JsonNode criada = criar("GET PUT", null);
		String accessKey = criada.path("credential").path("accessKey").asText();
		String segredo = criada.path("secretAccessKey").asText();

		assertThat(accessKey).startsWith("AKIAENOIS").hasSizeLessThanOrEqualTo(32);
		assertThat(segredo).isNotBlank();
		assertThat(criada.path("credential").has("secretAccessKey")).isFalse();
		// Ordem fixa no banco: o painel mostra a credencial igual em toda leitura.
		assertThat(criada.path("credential").path("allowedOperations").asText()).isEqualTo("GET PUT");

		// Lista: a chave aparece, o segredo não.
		var lista = json(get(ROTA).header("X-Session-Token", sessao), 200);
		assertThat(lista).hasSize(1);
		assertThat(lista.get(0).has("secretAccessKey")).isFalse();
		assertThat(lista.get(0).path("accessKey").asText()).isEqualTo(accessKey);

		// O SDK: grava e lê com o par que o painel entregou.
		byte[] corpo = "conteudo pela chave do painel".getBytes(StandardCharsets.UTF_8);
		MockHttpServletRequestBuilder putObjeto = put("/s3/" + bucket + "/painel.txt").content(corpo)
			.contentType(MediaType.TEXT_PLAIN);
		assinar(putObjeto, accessKey, segredo, sha256(corpo));
		mockMvc.perform(putObjeto).andExpect(status().isOk());

		MockHttpServletRequestBuilder getObjeto = get("/s3/" + bucket + "/painel.txt");
		assinar(getObjeto, accessKey, segredo, sha256(new byte[0]));
		MvcResult leitura = mockMvc.perform(getObjeto).andReturn();
		assertThat(leitura.getResponse().getStatus()).isEqualTo(200);
	}

	@Test
	void revogar_tira_a_chave_do_gateway_e_repetir_nao_e_erro() throws Exception {
		JsonNode criada = criar("GET", null);
		String id = criada.path("credential").path("id").asText();
		String accessKey = criada.path("credential").path("accessKey").asText();
		String segredo = criada.path("secretAccessKey").asText();

		mockMvc.perform(delete(ROTA + "/" + id).header("X-Session-Token", sessao))
			.andExpect(status().isNoContent());

		MockHttpServletRequestBuilder leitura = get("/s3/" + bucket + "/nada.txt");
		assinar(leitura, accessKey, segredo, sha256(new byte[0]));
		assertThat(mockMvc.perform(leitura).andReturn().getResponse().getStatus()).isEqualTo(403);

		// Idempotente, e a lista mostra a revogação em vez de esconder a linha.
		mockMvc.perform(delete(ROTA + "/" + id).header("X-Session-Token", sessao))
			.andExpect(status().isNoContent());
		var lista = json(get(ROTA).header("X-Session-Token", sessao), 200);
		assertThat(lista.get(0).path("revokedAt").isNull()).isFalse();
	}

	@Test
	void credencial_de_outra_conta_nao_aparece_nem_pode_ser_revogada() throws Exception {
		JsonNode criada = criar("GET", null);
		String idAlheio = criada.path("credential").path("id").asText();
		var intruso = new TestAuthHelper(mockMvc, objectMapper).authenticate("s3_credencial_intruso_"
			+ UUID.randomUUID().toString().replace("-", "").substring(0, 8));
		// O intruso tem credencial própria: com a lista dele vazia, um filtro que ignorasse o id
		// passaria em branco — o teste só discrimina se houver linha para ele alcançar por engano.
		json(post(ROTA).header("X-Session-Token", intruso.token()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"allowedOperations\":\"GET\"}"), 200);

		assertThat(json(get(ROTA).header("X-Session-Token", intruso.token()), 200)).hasSize(1)
			.allSatisfy(c -> assertThat(c.path("id").asText()).isNotEqualTo(idAlheio));

		// 404 e não 403: quem não tem a chave não precisa saber que ela existe.
		mockMvc.perform(delete(ROTA + "/" + idAlheio).header("X-Session-Token", intruso.token()))
			.andExpect(status().isNotFound());
		assertThat(json(get(ROTA).header("X-Session-Token", sessao), 200).get(0).path("revokedAt").isNull())
			.isTrue();
	}

	@Test
	void bucket_de_outra_conta_nao_vira_escopo_de_credencial_nova() throws Exception {
		var outro = new TestAuthHelper(mockMvc, objectMapper).authenticate("s3_credencial_dono_"
			+ UUID.randomUUID().toString().replace("-", "").substring(0, 8));
		JsonNode chaveDoOutro = json(post(ROTA).header("X-Session-Token", outro.token())
			.contentType(MediaType.APPLICATION_JSON).content("{\"allowedOperations\":\"PUT\"}"), 200);
		byte[] corpo = "do dono".getBytes(StandardCharsets.UTF_8);
		MockHttpServletRequestBuilder putDono = put("/s3/" + bucket + "/dono.txt").content(corpo)
			.contentType(MediaType.TEXT_PLAIN);
		assinar(putDono, chaveDoOutro.path("credential").path("accessKey").asText(),
			chaveDoOutro.path("secretAccessKey").asText(), sha256(corpo));
		mockMvc.perform(putDono).andExpect(status().isOk());

		mockMvc.perform(post(ROTA).header("X-Session-Token", sessao).contentType(MediaType.APPLICATION_JSON)
			.content("{\"allowedBucket\":\"" + bucket + "\",\"allowedOperations\":\"GET\"}"))
			.andExpect(status().isForbidden());
	}

	@Test
	void operacao_ou_bucket_invalido_e_recusado_antes_de_criar_linha() throws Exception {
		mockMvc.perform(post(ROTA).header("X-Session-Token", sessao).contentType(MediaType.APPLICATION_JSON)
			.content("{\"allowedOperations\":\"GET ADMIN\"}"))
			.andExpect(status().isBadRequest());
		mockMvc.perform(post(ROTA).header("X-Session-Token", sessao).contentType(MediaType.APPLICATION_JSON)
			.content("{\"allowedBucket\":\"Bucket Com Espaco\"}"))
			.andExpect(status().isBadRequest());
		mockMvc.perform(post(ROTA).header("X-Session-Token", sessao).contentType(MediaType.APPLICATION_JSON)
			.content("{\"allowedOperations\":\" \"}"))
			.andExpect(status().isBadRequest());

		assertThat(json(get(ROTA).header("X-Session-Token", sessao), 200)).isEmpty();
	}

	@Test
	void sem_sessao_nao_cria_nem_lista() throws Exception {
		mockMvc.perform(post(ROTA).contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(get(ROTA)).andExpect(status().isUnauthorized());
	}

	// ---------------------------------------------------------------- apoio

	private JsonNode criar(String operacoes, String bucketEscopo) throws Exception {
		var corpo = objectMapper.createObjectNode();
		if (operacoes != null) {
			corpo.put("allowedOperations", operacoes);
		}
		if (bucketEscopo != null) {
			corpo.put("allowedBucket", bucketEscopo);
		}
		return json(post(ROTA).header("X-Session-Token", sessao).contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(corpo)), 200);
	}

	private JsonNode json(MockHttpServletRequestBuilder pedido, int esperado) throws Exception {
		MvcResult result = mockMvc.perform(pedido).andReturn();
		assertThat(result.getResponse().getStatus()).isEqualTo(esperado);
		String texto = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
		return objectMapper.readTree(texto);
	}

	/**
	 * A mesma assinatura que o SDK faz: `host`, `x-amz-content-sha256` e `x-amz-date`, com o caminho
	 * <b>sem</b> o prefixo `/s3` da instalação. O segredo é o que o painel devolveu.
	 */
	private void assinar(MockHttpServletRequestBuilder pedido, String accessKey, String segredo, String payloadHash) {
		var montado = pedido.buildRequest(new org.springframework.mock.web.MockServletContext());
		String amzDate = AMZ.format(Instant.now());
		String dia = amzDate.substring(0, 8);
		String caminho = montado.getRequestURI().substring(S3GatewayController.PREFIX.length());
		String assinados = "host;x-amz-content-sha256;x-amz-date";
		String canonico = "host:localhost\n" + "x-amz-content-sha256:" + payloadHash + "\n" + "x-amz-date:" + amzDate
			+ "\n";
		String pedidoCanonico = montado.getMethod() + "\n" + caminho + "\n\n" + canonico + "\n" + assinados + "\n"
			+ payloadHash;
		String escopo = dia + "/enois-1/s3/aws4_request";
		String paraAssinar = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + escopo + "\n" + sha256Hex(
			pedidoCanonico.getBytes(StandardCharsets.UTF_8));
		byte[] k = hmac(("AWS4" + segredo).getBytes(StandardCharsets.UTF_8), dia);
		k = hmac(k, "enois-1");
		k = hmac(k, "s3");
		k = hmac(k, "aws4_request");
		String assinatura = HexFormat.of().formatHex(hmac(k, paraAssinar));
		pedido.header(HttpHeaders.HOST, "localhost")
			.header("x-amz-date", amzDate)
			.header("x-amz-content-sha256", payloadHash)
			.header(HttpHeaders.AUTHORIZATION, "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + escopo
				+ ", SignedHeaders=" + assinados + ", Signature=" + assinatura);
	}

	private static byte[] hmac(byte[] chave, String dado) {
		try {
			var mac = javax.crypto.Mac.getInstance("HmacSHA256");
			mac.init(new javax.crypto.spec.SecretKeySpec(chave, "HmacSHA256"));
			return mac.doFinal(dado.getBytes(StandardCharsets.UTF_8));
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private static String sha256(byte[] bytes) {
		return HexFormat.of().formatHex(digest(bytes));
	}

	private static String sha256Hex(byte[] bytes) {
		return sha256(bytes);
	}

	private static byte[] digest(byte[] bytes) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(bytes);
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}
}