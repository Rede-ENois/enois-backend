package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

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
	private com.socialcoisa.rede.shared.security.IpRateLimiter tentativas;

	@Autowired
	private SecretCipher cipher;

	@Autowired
	private tools.jackson.databind.ObjectMapper objectMapper;

	private String accessKey;
	private String admin;
	private String bucket;

	@BeforeEach
	void setUp() throws Exception {
		// O teto de assinatura errada é um bean único: a conta de um teste não pode escolher o
		// resultado do próximo.
		tentativas.limpar();
		jdbc.update("delete from network_storage_s3_credentials");
		// O dono da credencial S3 do cenário é a conta de EMISSÃO das requisições: o bucket nasce com
		// o primeiro objeto `created_by` dela, e a donagem do bucket agora é conferida em todo verbo.
		// Usar um id sem conta daria "bucket não é do dono" em todo teste, por um eixo que este arquivo
		// não quer testar aqui (o teste de donagem tem dois donos de verdade).
		var conta = new TestAuthHelper(mockMvc, objectMapper).authenticate("s3_cenario_"
			+ UUID.randomUUID().toString().replace("-", "").substring(0, 8));
		admin = conta.userId();
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

	/**
	 * O caminho completo da URL pré-assinada (brief §11): o dono emite pela sessão, e a URL sai do
	 * controle do segredo — o portador não tem credencial nenhuma e a requisição não leva cabeçalho
	 * {@code Authorization}.
	 *
	 * <p>Agora a credencial é do dono de verdade: a do cenário tem outro {@code owner_user_id}, que é
	 * o que prova que a emissão não pega "qualquer credencial que permita GET".</p>
	 */
	@Test
	void url_pre_assinada_le_o_objeto_sem_nenhuma_credencial() throws Exception {
		var dono = new TestAuthHelper(mockMvc, objectMapper).authenticate("s3_presign_dono");
		String accessKeyDono = "AKIAENOISPRES" + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
		jdbc.update("""
			insert into network_storage_s3_credentials (id, access_key, secret_hash, secret_encrypted, owner_user_id,
				allowed_bucket, allowed_operations, created_at)
			values (?, ?, ?, ?, ?, ?, 'GET PUT', ?)
			""", "s3c_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12), accessKeyDono,
			com.socialcoisa.rede.shared.security.Hashes.sha256Hex(SECRET), cipher.encrypt(SECRET), dono.userId(),
			bucket, java.sql.Timestamp.from(Instant.parse("2026-11-01T00:00:00Z")));

		byte[] corpo = "conteudo lido por url pre-assinada".getBytes(StandardCharsets.UTF_8);
		// O bucket só existe porque o DONO gravou nele: emitir agora exige bucket do dono, e gravar
		// com a credencial do cenário deixaria o bucket na conta de outro usuário.
		gravarPeloDono("fotos/url.jpg", "conteudo lido por url pre-assinada", accessKeyDono);

		var emissao = mockMvc.perform(MockMvcRequestBuilders
			.post("/s3/" + bucket + "/presign").param("key", "fotos/url.jpg").param("method", "GET").param("expires", "600")
			.header("X-Session-Token", dono.token())).andReturn();
		assertThat(emissao.getResponse().getStatus()).as(emissao.getResponse().getContentAsString()).isEqualTo(200);
		String url = objectMapper.readTree(emissao.getResponse().getContentAsString()).get("url").asText();
		assertThat(url).contains("X-Amz-Signature=").contains("X-Amz-Expires=600");

		// O portador da URL: sem Authorization, sem sessão, sem nada além da query. O navegador/servidor
		// decodifica `%25` uma vez ao transformar a URL em requisição; o gateway vê `%2F`, que é o valor
		// assinado. Mandar o texto cru faria a servlet decodificar duas vezes e o valor divergir.
		String consulta = decodificaUmaVez(url.substring(url.indexOf('?') + 1));
		String caminho = url.substring(url.indexOf("/s3/"), url.indexOf('?'));
		// O MockMvc não manda `Host`: sem ele o servidor assina com um host e verifica com outro. O
		// navegador manda sempre — é o cabeçalho que o SigV4 exige.
		var portador = get(caminho + "?" + consulta).header("Host", "localhost");
		var resultado = mockMvc.perform(portador).andReturn();
		if (resultado.getRequest().isAsyncStarted()) {
			resultado = mockMvc.perform(asyncDispatch(resultado)).andReturn();
		}
		assertThat(resultado.getResponse().getStatus()).as(resultado.getResponse().getContentAsString()).isEqualTo(200);
		assertThat(resultado.getResponse().getContentAsByteArray()).isEqualTo(corpo);
	}

	/** A mesma URL com um parâmetro trocado é recusada — o que ela assinou não é o que ela pede. */
	@Test
	void url_pre_assinada_adulterada_e_recusada() throws Exception {
		var dono = new TestAuthHelper(mockMvc, objectMapper).authenticate("s3_presign_dono");
		String accessKeyDono = "AKIAENOISPRES" + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
		jdbc.update("""
			insert into network_storage_s3_credentials (id, access_key, secret_hash, secret_encrypted, owner_user_id,
				allowed_bucket, allowed_operations, created_at)
			values (?, ?, ?, ?, ?, ?, 'GET PUT', ?)
			""", "s3c_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12), accessKeyDono,
			com.socialcoisa.rede.shared.security.Hashes.sha256Hex(SECRET), cipher.encrypt(SECRET), dono.userId(),
			bucket, java.sql.Timestamp.from(Instant.parse("2026-11-01T00:00:00Z")));

		gravarPeloDono("fotos/url.jpg", "objeto do dono para a url", accessKeyDono);
		var emissao = mockMvc.perform(MockMvcRequestBuilders
			.post("/s3/" + bucket + "/presign").param("key", "fotos/url.jpg").param("method", "GET")
			.header("X-Session-Token", dono.token())).andReturn();
		String url = objectMapper.readTree(emissao.getResponse().getContentAsString()).get("url").asText();
		String consulta = decodificaUmaVez(url.substring(url.indexOf('?') + 1)) + "&response-cache-control=public";
		String caminho = url.substring(url.indexOf("/s3/"), url.indexOf('?'));

		var resultado = mockMvc.perform(get(caminho + "?" + consulta)).andReturn();
		assertThat(resultado.getResponse().getStatus()).isEqualTo(403);
	}

	/**
	 * Credencial de escopo <b>nulo</b> não emite URL para bucket de outra pessoa.
	 *
	 * <p>{@code allowedBucket = null} quer dizer "todos os buckets <b>do dono</b>" — não "todos os
	 * buckets da rede". Uma URL sai das mãos do dono, então escopo ilimitado não pode virar um link
	 * para o bucket de outra conta. Sem o filtro de donagem, o {@code null} passa em qualquer bucket e
	 * o furo fica invisível no teste de escopo setado (que é o único que existia).</p>
	 */
	@Test
	void credencial_de_escopo_nulo_nao_emite_url_para_bucket_de_outro_dono() throws Exception {
		// Duas contas reais: o dono do bucket e quem tenta emitir.
		var donoDoBucket = new TestAuthHelper(mockMvc, objectMapper).authenticate("s3_bucket_owner");
		var invasor = new TestAuthHelper(mockMvc, objectMapper).authenticate("s3_scope_nulo");
		// O bucket existe porque o DONO gravou nele.
		String acessoDono = "AKIAENOISOWNER" + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
		jdbc.update("""
			insert into network_storage_s3_credentials (id, access_key, secret_hash, secret_encrypted, owner_user_id,
				allowed_bucket, allowed_operations, created_at)
			values (?, ?, ?, ?, ?, ?, 'GET PUT', ?)
			""", "s3c_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12), acessoDono,
			com.socialcoisa.rede.shared.security.Hashes.sha256Hex(SECRET), cipher.encrypt(SECRET),
			donoDoBucket.userId(), bucket, java.sql.Timestamp.from(Instant.parse("2026-11-01T00:00:00Z")));
		byte[] corpo = "objeto do dono".getBytes(StandardCharsets.UTF_8);
		String sha = shaDe("objeto do dono");
		MockHttpServletRequestBuilder put = put("/s3/" + bucket + "/fotos/dono.jpg").content(corpo)
			.contentType("image/jpeg");
		assinar(put, SECRET, sha, acessoDono);
		mockMvc.perform(put).andExpect(status().isOk());

		// O invasor tem credencial SEM escopo (o padrão) e todo o verbo.
		jdbc.update("""
			insert into network_storage_s3_credentials (id, access_key, secret_hash, secret_encrypted, owner_user_id,
				allowed_bucket, allowed_operations, created_at)
			values (?, ?, ?, ?, ?, null, 'GET PUT', ?)
			""", "s3c_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
			"AKIAENOISSCOPE" + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase(),
			com.socialcoisa.rede.shared.security.Hashes.sha256Hex(SECRET), cipher.encrypt(SECRET), invasor.userId(),
			java.sql.Timestamp.from(Instant.parse("2026-11-01T00:00:00Z")));

		var emissao = mockMvc.perform(MockMvcRequestBuilders.post("/s3/" + bucket + "/presign")
			.param("key", "fotos/dono.jpg").param("method", "PUT")
			.header("X-Session-Token", invasor.token())).andReturn();

		assertThat(emissao.getResponse().getStatus()).isEqualTo(403);
		assertThat(emissao.getResponse().getContentAsString()).contains("nao e do dono da credencial");
	}

	/** O dono DO bucket emite normalmente — a linha de base, sem a qual "recusar tudo" passaria. */
	@Test
	void o_dono_do_bucket_emite_url_mesmo_com_credencial_de_escopo_nulo() throws Exception {
		var dono = new TestAuthHelper(mockMvc, objectMapper).authenticate("s3_presign_dono");
		String acesso = "AKIAENOISDONO" + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
		jdbc.update("""
			insert into network_storage_s3_credentials (id, access_key, secret_hash, secret_encrypted, owner_user_id,
				allowed_bucket, allowed_operations, created_at)
			values (?, ?, ?, ?, ?, null, 'GET PUT', ?)
			""", "s3c_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12), acesso,
			com.socialcoisa.rede.shared.security.Hashes.sha256Hex(SECRET), cipher.encrypt(SECRET), dono.userId(),
			java.sql.Timestamp.from(Instant.parse("2026-11-01T00:00:00Z")));
		byte[] corpo = "objeto do dono".getBytes(StandardCharsets.UTF_8);
		MockHttpServletRequestBuilder put = put("/s3/" + bucket + "/fotos/dono.jpg").content(corpo)
			.contentType("image/jpeg");
		assinar(put, SECRET, shaDe("objeto do dono"), acesso);
		mockMvc.perform(put).andExpect(status().isOk());

		var emissao = mockMvc.perform(MockMvcRequestBuilders.post("/s3/" + bucket + "/presign")
			.param("key", "fotos/dono.jpg").param("method", "GET")
			.header("X-Session-Token", dono.token())).andReturn();

		assertThat(emissao.getResponse().getStatus()).isEqualTo(200);
	}

	/**
	 * A dívida do doc 18 §6: credencial de escopo <b>nulo</b> não alcança bucket de outra conta nem por
	 * cabeçalho.
	 *
	 * <p>Era o furo declarado — o catálogo sabia o dono, o gateway não perguntava, e o escopo nulo (o
	 * padrão) passava em qualquer bucket. Aqui os três verbos que mexem em dado são exercitados: ler,
	 * gravar e apagar. Recusar só um deixaria o furo em pé pelo outro.</p>
	 */
	@Test
	void escopo_nulo_nao_alcanca_bucket_de_outra_conta_por_cabecalho() throws Exception {
		var donoDoBucket = new TestAuthHelper(mockMvc, objectMapper).authenticate("s3_owner_hdr");
		var invasor = new TestAuthHelper(mockMvc, objectMapper).authenticate("s3_invasor_hdr");
		String acessoDono = "AKIAENOISHDR" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
		String acessoInvasor = "AKIAENOISINV" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
		for (String[] par : new String[][] { { acessoDono, donoDoBucket.userId() }, { acessoInvasor, invasor.userId() } }) {
			jdbc.update("""
				insert into network_storage_s3_credentials (id, access_key, secret_hash, secret_encrypted, owner_user_id,
					allowed_bucket, allowed_operations, created_at)
				values (?, ?, ?, ?, ?, null, 'GET PUT LIST DELETE', ?)
				""", "s3c_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12), par[0],
				com.socialcoisa.rede.shared.security.Hashes.sha256Hex(SECRET), cipher.encrypt(SECRET), par[1],
				java.sql.Timestamp.from(Instant.parse("2026-11-01T00:00:00Z")));
		}
		// O objeto é do dono.
		byte[] corpo = "objeto do dono de verdade".getBytes(StandardCharsets.UTF_8);
		MockHttpServletRequestBuilder putDono = put("/s3/" + bucket + "/fotos/proprio.jpg").content(corpo)
			.contentType("image/jpeg");
		assinar(putDono, SECRET, shaDe("objeto do dono de verdade"), acessoDono);
		mockMvc.perform(putDono).andExpect(status().isOk());

		// O invasor, com escopo nulo e todos os verbos: GET, HEAD, PUT e DELETE.
		MockHttpServletRequestBuilder getInvasor = get("/s3/" + bucket + "/fotos/proprio.jpg");
		assinar(getInvasor, SECRET, shaVazio(), acessoInvasor);
		assertThat(mockMvc.perform(getInvasor).andReturn().getResponse().getStatus()).isEqualTo(403);

		MockHttpServletRequestBuilder headInvasor = head("/s3/" + bucket + "/fotos/proprio.jpg");
		assinar(headInvasor, SECRET, shaVazio(), acessoInvasor);
		assertThat(mockMvc.perform(headInvasor).andReturn().getResponse().getStatus()).isEqualTo(403);

		MockHttpServletRequestBuilder listInvasor = get("/s3/" + bucket).queryParam("list-type", "2");
		assinar(listInvasor, SECRET, shaVazio(), acessoInvasor);
		assertThat(mockMvc.perform(listInvasor).andReturn().getResponse().getStatus()).isEqualTo(403);

		// O PUT grava e só então confere a donagem — é a gravação que cria o bucket do dono. Aqui o
		// bucket já é de outra conta, então a gravação é desfeita e a resposta é 403.
		MockHttpServletRequestBuilder putInvasor = put("/s3/" + bucket + "/fotos/plantado.jpg")
			.content("plantado".getBytes(StandardCharsets.UTF_8)).contentType("text/plain");
		assinar(putInvasor, SECRET, shaDe("plantado"), acessoInvasor);
		assertThat(mockMvc.perform(putInvasor).andReturn().getResponse().getStatus()).isEqualTo(403);

		MockHttpServletRequestBuilder deleteInvasor = MockMvcRequestBuilders
			.delete("/s3/" + bucket + "/fotos/proprio.jpg");
		assinar(deleteInvasor, SECRET, shaVazio(), acessoInvasor);
		assertThat(mockMvc.perform(deleteInvasor).andReturn().getResponse().getStatus()).isEqualTo(403);

		// Nem resíduo: o objeto que o PUT do invasor criou antes de ser recusado não ficou no bucket.
		// Se ficasse, o invasor passaria a "ser dono" do bucket pelo próprio lixo que plantou.
		assertThat(jdbc.queryForObject("select count(*) from network_storage_objects where bucket = ?", Long.class,
			bucket)).isEqualTo(1L);

		// O objeto do dono continua lá — nenhum verbo do invasor o tocou.
		MockHttpServletRequestBuilder getDono = get("/s3/" + bucket + "/fotos/proprio.jpg");
		assinar(getDono, SECRET, shaVazio(), acessoDono);
		var leituraDono = mockMvc.perform(getDono).andReturn();
		if (leituraDono.getRequest().isAsyncStarted()) {
			leituraDono = mockMvc.perform(asyncDispatch(leituraDono)).andReturn();
		}
		assertThat(leituraDono.getResponse().getStatus()).isEqualTo(200);
		assertThat(leituraDono.getResponse().getContentAsByteArray()).isEqualTo(corpo);
	}

	/**
	 * Reescrever a mesma chave é o caso normal do S3, e o dono continua dono.
	 *
	 * <p>Este é o caminho que a donagem pós-gravação poderia quebrar: {@code beginUpload} devolve o objeto
	 * JÁ existente (STORED), então o S3 não tem "upload novo" para a mesma chave — a gravação de hoje recusa
	 * com 409 e a recusa não pode levar junto o objeto que já estava lá.</p>
	 */
	@Test
	void reescrever_a_mesma_chave_e_recusado_sem_apagar_o_objeto_existente() throws Exception {
		gravar("foto.jpg", "versao um");

		MockHttpServletRequestBuilder segundo = put("/s3/" + bucket + "/foto.jpg")
			.content("versao dois".getBytes(StandardCharsets.UTF_8)).contentType("text/plain");
		assinar(segundo, SECRET, shaDe("versao dois"));
		// O catálogo não aceita uma segunda cópia da mesma chave (o gate é o mesmo da rota do nó): a
		// resposta é 409 e a versão que o dono tinha continua servível, intacta.
		assertThat(mockMvc.perform(segundo).andReturn().getResponse().getStatus()).isEqualTo(409);

		MockHttpServletRequestBuilder leitura = get("/s3/" + bucket + "/foto.jpg");
		assinar(leitura, SECRET, shaVazio());
		var resposta = mockMvc.perform(leitura).andReturn();
		if (resposta.getRequest().isAsyncStarted()) {
			resposta = mockMvc.perform(asyncDispatch(resposta)).andReturn();
		}
		assertThat(resposta.getResponse().getStatus()).isEqualTo(200);
		assertThat(new String(resposta.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8))
			.isEqualTo("versao um");
	}

	/**
	 * Objeto confirmado <b>sem cópia registrada</b> responde no vocabulário do S3.
	 *
	 * <p><b>Antes:</b> o {@code GET} e o {@code HEAD} devolviam o erro interno do control plane —
	 * {@code STORAGE_PLACEMENT_MISSING} no corpo JSON do Énóis — quando o catálogo dizia que o objeto
	 * existe e nenhuma cópia estava registrada. O SDK lê o {@code Code} do XML do S3; um código que
	 * ele não conhece vira falha opaca, e o cliente não distingue "tente de novo" de "não existe".
	 * A tradução só cobria o {@code PUT}.</p>
	 *
	 * <p><b>Agora:</b> a tradução vale nas três rotas de leitura. O discriminante é o corpo: sem a
	 * correção ele traz o código interno e não é XML do S3.</p>
	 */
	@Test
	void objeto_sem_copia_registrada_responde_no_vocabulario_do_s3() throws Exception {
		var dono = new TestAuthHelper(mockMvc, objectMapper).authenticate("s3_sem_copia");
		String minhaChave = "AKIAENOISSEM" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
		jdbc.update("""
			insert into network_storage_s3_credentials (id, access_key, secret_hash, secret_encrypted, owner_user_id,
				allowed_bucket, allowed_operations, created_at)
			values (?, ?, ?, ?, ?, null, 'GET PUT LIST DELETE', ?)
			""", "s3c_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12), minhaChave,
			com.socialcoisa.rede.shared.security.Hashes.sha256Hex(SECRET), cipher.encrypt(SECRET), dono.userId(),
			java.sql.Timestamp.from(Instant.parse("2026-11-01T00:00:00Z")));
		byte[] corpo = "sem copia registrada".getBytes(StandardCharsets.UTF_8);
		MockHttpServletRequestBuilder put = put("/s3/" + bucket + "/orfa.jpg").content(corpo)
			.contentType("text/plain");
		assinar(put, SECRET, shaDe("sem copia registrada"), minhaChave);
		mockMvc.perform(put).andExpect(status().isOk());
		// O estado que a falha no registro da cópia deixa: o objeto confirmado e nenhuma localização.
		jdbc.update("delete from network_storage_placements where object_id in"
			+ " (select id from network_storage_objects where bucket = ? and object_key = ?)", bucket, "orfa.jpg");

		// Uma rota por vez: o `HEAD` também passa pelo mesmo caminho, e um `for` que só falhasse no
		// `GET` deixaria o `HEAD` carregando a prova de uma tradução que não existe nele.
		MockHttpServletRequestBuilder leitura = get("/s3/" + bucket + "/orfa.jpg");
		assinar(leitura, SECRET, shaVazio(), minhaChave);
		var resposta = mockMvc.perform(leitura).andReturn();
		if (resposta.getRequest().isAsyncStarted()) {
			resposta = mockMvc.perform(asyncDispatch(resposta)).andReturn();
		}
		String visto = resposta.getResponse().getContentAsString();
		assertThat(resposta.getResponse().getStatus()).as(visto).isEqualTo(409);
		assertThat(visto).as("o corpo e o XML do S3").contains("<Code>").doesNotContain("STORAGE_PLACEMENT_MISSING");

		// O `HEAD` não tem corpo: o que prova a tradução é o tipo da resposta. Sem ela o handler
		// genérico devolve JSON do Énóis; com ela, o documento XML que o SDK espera.
		MockHttpServletRequestBuilder cabecalho = head("/s3/" + bucket + "/orfa.jpg");
		assinar(cabecalho, SECRET, shaVazio(), minhaChave);
		var semCorpo = mockMvc.perform(cabecalho).andReturn().getResponse();
		assertThat(semCorpo.getStatus()).isEqualTo(409);
		assertThat(semCorpo.getContentType()).as("o HEAD tambem responde no formato do S3")
			.startsWith("application/xml");
	}

	/** DELETE por URL não é emitido: a lápide não vai para um link que pode vazar. */
	@Test
	void url_pre_assinada_de_delete_e_recusada_na_emissao() throws Exception {
		var dono = new TestAuthHelper(mockMvc, objectMapper).authenticate("s3_presign_dono");

		var emissao = mockMvc.perform(MockMvcRequestBuilders
			.post("/s3/" + bucket + "/presign").param("key", "fotos/url.jpg").param("method", "DELETE")
			.header("X-Session-Token", dono.token())).andReturn();

		assertThat(emissao.getResponse().getStatus()).isEqualTo(400);
		assertThat(emissao.getResponse().getContentAsString()).contains("InvalidArgument");
	}

	/**
	 * Emitir URL exige sessão do dono: sem sessão é 401, nunca a URL.
	 *
	 * <p>O 401 (e não 403 do S3) é coerente com o resto do painel: quem emite é a aplicação com
	 * sessão, não um SDK com credencial S3. E a ordem importa — a sessão é conferida ANTES do
	 * parâmetro, então a rota não conta a forma da chamada a quem não está autenticado.</p>
	 */
	@Test
	void emissao_de_url_sem_sessao_e_recusada() throws Exception {
		var resultado = mockMvc.perform(MockMvcRequestBuilders.post("/s3/" + bucket + "/presign")).andReturn();

		assertThat(resultado.getResponse().getStatus()).isEqualTo(401);
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

	/**
	 * Assinatura errada tem teto por Access Key. Sem ele, a Access Key — que é pública — vira um
	 * alvo de força bruta na velocidade da rede, e cada teste custa um HMAC ao gateway.
	 */
	@Test
	void assinatura_errada_chega_ao_teto_e_o_sdk_legitimo_continua_entrando() throws Exception {
		for (int i = 0; i < 20; i++) {
			MockHttpServletRequestBuilder list = get("/s3/" + bucket).queryParam("list-type", "2");
			assinar(list, "segredo-errado", shaVazio());
			assertThat(mockMvc.perform(list).andReturn().getResponse().getStatus()).isEqualTo(403);
		}
		MockHttpServletRequestBuilder barrada = get("/s3/" + bucket).queryParam("list-type", "2");
		assinar(barrada, SECRET, shaVazio());
		var resultado = mockMvc.perform(barrada).andReturn();

		// 429 com o código do S3: o SDK entende "espere", não "chave errada".
		assertThat(resultado.getResponse().getStatus()).isEqualTo(429);
		assertThat(resultado.getResponse().getContentAsString()).contains("<Code>SlowDown</Code>");
	}

	/**
	 * CopyObject: o {@code PUT} com {@code x-amz-copy-source} copia os bytes da origem no servidor.
	 *
	 * <p><b>Antes:</b> o cabeçalho era recusado com {@code 501 NotImplemented} — a operação não
	 * existia, e {@code aws s3 cp --recursive} / {@code rclone copy} não tinham como copiar sem baixar
	 * e reenviar cada arquivo.</p>
	 *
	 * <p><b>Agora:</b> o destino é gravado pelo mesmo caminho do PUT, com o corpo lido da origem, e a
	 * resposta é o {@code CopyObjectResult} do S3. O discriminante é o par: {@code 200} com o XML
	 * <b>e</b> o {@code GET} do destino devolvendo exatamente os bytes da origem — um 200 sem
	 * gravação passaria só pelo status.</p>
	 */
	@Test
	void copia_de_objeto_grava_os_bytes_da_origem_no_destino() throws Exception {
		gravar("copias/origem.txt", "conteudo que a copia precisa levar");
		String sha = shaDe("conteudo que a copia precisa levar");

		var resposta = copiar("copias/origem.txt", "copias/destino.txt", null);

		assertThat(resposta.getStatus()).as(resposta.getContentAsString()).isEqualTo(200);
		assertThat(resposta.getContentAsString())
			.contains("<CopyObjectResult")
			.contains("<ETag>\"" + sha + "\"</ETag>")
			.contains("<LastModified>");
		assertThat(resposta.getHeader(HttpHeaders.ETAG)).isEqualTo("\"" + sha + "\"");

		assertThat(ler("copias/destino.txt")).isEqualTo("conteudo que a copia precisa levar");
		assertThat(ler("copias/origem.txt")).as("a origem continua intacta")
			.isEqualTo("conteudo que a copia precisa levar");
		assertThat(cabecalhosDe("copias/destino.txt").getHeader(HttpHeaders.CONTENT_LENGTH))
			.isEqualTo(String.valueOf("conteudo que a copia precisa levar".getBytes(StandardCharsets.UTF_8).length));
	}

	/**
	 * O metadado acompanha a cópia; {@code REPLACE} usa o que veio na requisição de cópia.
	 *
	 * <p>É o que o SDK usa para copiar um arquivo mudando o {@code Content-Type} ou o nome de anexo
	 * sem baixar o corpo. O {@code COPY} é o padrão do protocolo e não pode ser confundido com "copiar
	 * só o metadado": o corpo é o mesmo nos dois.</p>
	 */
	@Test
	void copia_traz_o_metadado_da_origem_e_replace_troca_pelo_do_pedido() throws Exception {
		byte[] corpo = "foto".getBytes(StandardCharsets.UTF_8);
		MockHttpServletRequestBuilder origem = put("/s3/" + bucket + "/copias/gato.jpg")
			.content(corpo).contentType("image/jpeg")
			.header("Cache-Control", "max-age=120")
			.header("Content-Disposition", "attachment; filename=\"gato.jpg\"")
			.header("x-amz-meta-dono", "luna");
		assinar(origem, SECRET, shaDe("foto"));
		mockMvc.perform(origem).andExpect(status().isOk());

		var copia = copiar("copias/gato.jpg", "copias/gato-copia.jpg", null);
		assertThat(copia.getStatus()).as(copia.getContentAsString()).isEqualTo(200);

		var herdados = cabecalhosDe("copias/gato-copia.jpg");
		assertThat(herdados.getContentType()).isEqualTo("image/jpeg");
		assertThat(herdados.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("max-age=120");
		assertThat(herdados.getHeader(HttpHeaders.CONTENT_DISPOSITION))
			.isEqualTo("attachment; filename=\"gato.jpg\"");
		assertThat(herdados.getHeader("x-amz-meta-dono")).isEqualTo("luna");
		assertThat(ler("copias/gato-copia.jpg")).isEqualTo("foto");

		var substituida = copiar("copias/gato.jpg", "copias/gato-novo.jpg", "REPLACE",
			"Content-Type", "text/plain", "x-amz-meta-dono", "outro");
		assertThat(substituida.getStatus()).as(substituida.getContentAsString()).isEqualTo(200);

		var novos = cabecalhosDe("copias/gato-novo.jpg");
		assertThat(novos.getContentType()).isEqualTo("text/plain");
		assertThat(novos.getHeader("x-amz-meta-dono")).isEqualTo("outro");
		assertThat(novos.getHeader(HttpHeaders.CACHE_CONTROL))
			.as("o Cache-Control da origem nao foi herdado no REPLACE").isNotEqualTo("max-age=120");
		// O corpo é o mesmo nos dois caminhos: REPLACE troca o declarado, não o conteúdo.
		assertThat(ler("copias/gato-novo.jpg")).isEqualTo("foto");
	}

	/** Origem que não existe: {@code 404 NoSuchKey} no XML do S3 e nenhuma chave de destino criada. */
	@Test
	void copia_de_origem_inexistente_responde_404_sem_criar_destino() throws Exception {
		var resposta = copiar("copias/nao-existe.txt", "copias/orfao.txt", null);

		assertThat(resposta.getStatus()).isEqualTo(404);
		assertThat(resposta.getContentAsString()).contains("<Code>NoSuchKey</Code>");
		assertThat(jdbc.queryForObject("select count(*) from network_storage_objects where bucket = ?"
			+ " and object_key = ?", Integer.class, bucket, "copias/orfao.txt")).isZero();
	}

	/**
	 * A origem também é do dono da credencial: copiar de bucket de outra conta é {@code 403} e não
	 * grava destino nenhum.
	 */
	@Test
	void copia_de_bucket_de_outra_conta_e_recusada_sem_gravar() throws Exception {
		var alheio = new TestAuthHelper(mockMvc, objectMapper).authenticate("s3_copia_alheia");
		String chaveAlheia = ACCESS_KEY_PREFIX + UUID.randomUUID().toString().replace("-", "")
			.substring(0, 10).toUpperCase();
		jdbc.update("""
			insert into network_storage_s3_credentials (id, access_key, secret_hash, secret_encrypted, owner_user_id,
				allowed_bucket, allowed_operations, created_at)
			values (?, ?, ?, ?, ?, null, 'GET PUT LIST DELETE', ?)
			""", "s3c_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12), chaveAlheia,
			com.socialcoisa.rede.shared.security.Hashes.sha256Hex(SECRET), cipher.encrypt(SECRET),
			alheio.userId(), java.sql.Timestamp.from(Instant.parse("2026-11-01T00:00:00Z")));
		String bucketAlheio = "bkt" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
		byte[] segredo = "segredo de outra conta".getBytes(StandardCharsets.UTF_8);
		MockHttpServletRequestBuilder putAlheio = put("/s3/" + bucketAlheio + "/segredo.txt")
			.content(segredo).contentType("text/plain");
		assinar(putAlheio, SECRET, shaDe("segredo de outra conta"), chaveAlheia);
		mockMvc.perform(putAlheio).andExpect(status().isOk());

		MockHttpServletRequestBuilder copia = put("/s3/" + bucket + "/copias/roubada.txt")
			.header("x-amz-copy-source", "/" + bucketAlheio + "/segredo.txt")
			.content(new byte[0]);
		assinar(copia, SECRET, shaVazio());
		var resposta = mockMvc.perform(copia).andReturn().getResponse();

		assertThat(resposta.getStatus()).isEqualTo(403);
		assertThat(resposta.getContentAsString()).contains("<Code>AccessDenied</Code>");
		assertThat(jdbc.queryForObject("select count(*) from network_storage_objects where bucket = ?"
			+ " and object_key = ?", Integer.class, bucket, "copias/roubada.txt")).isZero();
	}

	/**
	 * Subrecurso que gravaria é {@code 501} e <b>o objeto fica como estava</b>.
	 *
	 * <p><b>Antes:</b> o {@code PUT} era despachado só pela chave da rota. Um {@code PUT ?tagging}
	 * — a operação com que o S3 grava etiquetas — abria um upload da chave, gravava o XML da etiqueta
	 * como corpo, substituía o arquivo e respondia {@code 200}, que é o "sucesso" da etiquetagem.</p>
	 *
	 * <p><b>Agora:</b> {@code 501 NotImplemented} no XML do S3, antes do catálogo. O discriminante é o
	 * par: o status <b>e</b> o {@code HEAD} seguinte devolvendo o mesmo ETag — o ETag é o que prova
	 * que nada foi reescrito, e o {@code GET} prova que o corpo é o de antes.</p>
	 */
	@Test
	void put_de_subrecurso_que_gravaria_responde_501_sem_alterar_o_objeto() throws Exception {
		gravar("subrecurso/alvo.txt", "conteudo que nao pode virar etiqueta");
		String shaOriginal = shaDe("conteudo que nao pode virar etiqueta");
		String etiqueta = "<Tagging><TagSet><Tag><Key>a</Key><Value>b</Value></Tag></TagSet></Tagging>";
		MockHttpServletRequestBuilder etiquetar = put("/s3/" + bucket + "/subrecurso/alvo.txt")
			.queryParam("tagging").content(etiqueta.getBytes(StandardCharsets.UTF_8))
			.contentType("application/xml");
		assinar(etiquetar, SECRET, shaDe(etiqueta));

		var resposta = mockMvc.perform(etiquetar).andReturn().getResponse();

		assertThat(resposta.getStatus()).as(resposta.getContentAsString()).isEqualTo(501);
		assertThat(resposta.getContentAsString()).as(resposta.getContentAsString())
			.contains("<Code>NotImplemented</Code>")
			.contains("tagging");
		assertThat(cabecalhosDe("subrecurso/alvo.txt").getHeader(HttpHeaders.ETAG))
			.as("o objeto nao pode ter sido reescrito com o XML da etiqueta").isEqualTo("\"" + shaOriginal + "\"");
		assertThat(ler("subrecurso/alvo.txt")).isEqualTo("conteudo que nao pode virar etiqueta");
	}

	/**
	 * Dois subrecursos na mesma requisição ({@code ?tagging&acl}): a mensagem é determinística.
	 *
	 * <p>Com um {@code Set.of}, a ordem de iteração não é contrato — a mesma requisição responderia
	 * {@code tagging} numa execução e {@code acl} em outra. A lista fixa garante que a primeira da
	 * ordem responde, e o teste trava a ordem, não só o status.</p>
	 */
	@Test
	void dois_subrecursos_que_gravariam_respondem_o_primeiro_da_ordem() throws Exception {
		gravar("subrecurso/dois.txt", "intacto");

		MockHttpServletRequestBuilder dois = put("/s3/" + bucket + "/subrecurso/dois.txt")
			.queryParam("acl").queryParam("tagging").content(new byte[0]);
		assinar(dois, SECRET, shaVazio());

		var resposta = mockMvc.perform(dois).andReturn().getResponse();

		assertThat(resposta.getStatus()).as(resposta.getContentAsString()).isEqualTo(501);
		assertThat(resposta.getContentAsString()).contains("O subrecurso 'tagging' nao e suportado");
		assertThat(ler("subrecurso/dois.txt")).isEqualTo("intacto");
	}

	/**
	 * O corpo do {@code PUT ?tagging} é <b>consumido</b> antes do {@code 501}.
	 *
	 * <p>Em conexão persistente, responder não-2xx deixando o corpo do pedido pendente faz o servidor
	 * fechar a conexão no meio de um pedido (RFC 9110 §15.6.2). O {@code MockMvc} não simula a borda
	 * HTTP, então o discriminante é aqui e não no pedido inteiro: o fluxo de entrada do pedido é
	 * substituído por um que conta os bytes lidos, e o corpo tem que sair inteiro dele.</p>
	 */
	@Test
	void subrecurso_que_gravaria_consome_o_corpo_antes_do_501() throws Exception {
		byte[] corpo = "<Tagging><TagSet/></Tagging>".getBytes(StandardCharsets.UTF_8);
		AtomicInteger lidos = new AtomicInteger();
		MockHttpServletRequest pedido = new MockHttpServletRequest("PUT", "/s3/" + bucket + "/subrecurso/drenar.txt") {
			@Override
			public String getQueryString() {
				return "tagging";
			}

			@Override
			public ServletInputStream getInputStream() {
				ServletInputStream original = super.getInputStream();
				return new ServletInputStream() {
					@Override
					public int read() throws java.io.IOException {
						int b = original.read();
						if (b >= 0) {
							lidos.incrementAndGet();
						}
						return b;
					}

					@Override
					public int read(byte[] destino, int inicio, int quantidade) throws java.io.IOException {
						int n = original.read(destino, inicio, quantidade);
						if (n > 0) {
							lidos.addAndGet(n);
						}
						return n;
					}

					@Override
					public boolean isFinished() {
						return original.isFinished();
					}

					@Override
					public boolean isReady() {
						return original.isReady();
					}

					@Override
					public void setReadListener(jakarta.servlet.ReadListener ouvinte) {
						original.setReadListener(ouvinte);
					}
				};
			}
		};
		pedido.setContent(corpo);

		S3GatewayController.drenarCorpo(pedido);

		assertThat(lidos.get()).as("o corpo inteiro tem que sair do fluxo de entrada").isEqualTo(corpo.length);
	}

	/** {@code GET ?acl} também é {@code 501}, e o objeto não muda. */
	@Test
	void get_de_acl_responde_501_sem_alterar_o_objeto() throws Exception {
		gravar("subrecurso/acl-get.txt", "com acl intacta");
		String shaOriginal = shaDe("com acl intacta");

		MockHttpServletRequestBuilder consultar = get("/s3/" + bucket + "/subrecurso/acl-get.txt")
			.queryParam("acl");
		assinar(consultar, SECRET, "");

		var resposta = mockMvc.perform(consultar).andReturn().getResponse();

		assertThat(resposta.getStatus()).as(resposta.getContentAsString()).isEqualTo(501);
		assertThat(resposta.getContentAsString()).contains("<Code>NotImplemented</Code>");
		assertThat(cabecalhosDe("subrecurso/acl-get.txt").getHeader(HttpHeaders.ETAG))
			.isEqualTo("\"" + shaOriginal + "\"");
	}

	/**
	 * A guarda roda <b>antes</b> do desvio para o {@code CopyObject}: {@code PUT ?tagging} com
	 * {@code x-amz-copy-source} não copia.
	 *
	 * <p>Sem a ordem, o cabeçalho de cópia capturaria a requisição de etiqueta e a cópia responderia
	 * {@code 200} — o cliente via sucesso na etiquetagem e ganhava um objeto novo no destino. O
	 * discriminante é o par: {@code 501} <b>e</b> o destino inexistente.</p>
	 */
	@Test
	void subrecurso_de_etiqueta_com_cabecalho_de_copia_nao_vira_copia() throws Exception {
		gravar("subrecurso/origem.txt", "origem");
		MockHttpServletRequestBuilder mistura = put("/s3/" + bucket + "/subrecurso/etiquetado.txt")
			.queryParam("tagging").header("x-amz-copy-source", "/" + bucket + "/subrecurso/origem.txt")
			.content(new byte[0]);
		assinar(mistura, SECRET, shaVazio());

		var resposta = mockMvc.perform(mistura).andReturn().getResponse();

		assertThat(resposta.getStatus()).as(resposta.getContentAsString()).isEqualTo(501);
		assertThat(resposta.getContentAsString()).contains("<Code>NotImplemented</Code>");
		assertThat(jdbc.queryForObject("select count(*) from network_storage_objects where bucket = ?"
			+ " and object_key = ?", Integer.class, bucket, "subrecurso/etiquetado.txt")).isZero();
	}

	/** Copiar sobre o próprio objeto sem trocar o conteúdo é {@code 400}, e nada é tocado. */
	@Test
	void copia_do_objeto_sobre_ele_mesmo_responde_400_e_preserva_o_conteudo() throws Exception {
		gravar("copias/eu.txt", "eu mesmo");

		var resposta = copiar("copias/eu.txt", "copias/eu.txt", null);

		assertThat(resposta.getStatus()).isEqualTo(400);
		assertThat(resposta.getContentAsString()).contains("<Code>InvalidRequest</Code>");
		assertThat(ler("copias/eu.txt")).isEqualTo("eu mesmo");
	}

	/**
	 * {@code x-amz-copy-source} é caminho, não query: {@code +} é {@code +}.
	 *
	 * <p>Um decodificador de query transformaria o {@code +} literal em espaço e a cópia procuraria
	 * uma chave que ninguém gravou. O SDK manda {@code %2B} para a mesma chave; as duas formas
	 * precisam chegar nela.</p>
	 */
	@Test
	void copia_aceita_mais_literal_e_mais_percent_encoded_na_origem() throws Exception {
		gravar("copias/a+b.txt", "mais literal");

		assertThat(copiar("copias/a+b.txt", "copias/mais-literal.txt", null).getStatus()).isEqualTo(200);
		assertThat(ler("copias/mais-literal.txt")).isEqualTo("mais literal");

		assertThat(copiar("copias/a%2Bb.txt", "copias/mais-codificado.txt", null).getStatus()).isEqualTo(200);
		assertThat(ler("copias/mais-codificado.txt")).isEqualTo("mais literal");
	}

	/**
	 * {@code if-match} divergente é {@code 412} e não grava. A condição é do protocolo: o cliente
	 * pediu a cópia <b>só se</b> a origem ainda fosse a que ele viu; copiar mesmo assim seria uma
	 * resposta errada com cara de certa.
	 */
	@Test
	void copia_com_if_match_divergente_responde_412_e_nao_grava() throws Exception {
		gravar("copias/condicional.txt", "condicional");

		var resposta = copiar("copias/condicional.txt", "copias/condicional-destino.txt", null,
			"x-amz-copy-source-if-match", "\"0000000000000000000000000000000000000000000000000000000000000000\"");

		assertThat(resposta.getStatus()).isEqualTo(412);
		assertThat(resposta.getContentAsString()).contains("<Code>PreconditionFailed</Code>");
		assertThat(jdbc.queryForObject("select count(*) from network_storage_objects where bucket = ?"
			+ " and object_key = ?", Integer.class, bucket, "copias/condicional-destino.txt")).isZero();
	}

	/**
	 * Copiar para uma chave já confirmada responde {@code 409}, como o PUT, e o objeto que estava lá
	 * continua com o <b>conteúdo e o metadado</b> de antes — a recusa não pode decorar a vítima.
	 */
	@Test
	void copia_para_chave_confirmada_responde_409_sem_tocar_no_objeto() throws Exception {
		byte[] original = "original".getBytes(StandardCharsets.UTF_8);
		MockHttpServletRequestBuilder ocupada = put("/s3/" + bucket + "/copias/ocupada.png")
			.content(original).contentType("image/png").header("x-amz-meta-dono", "original");
		assinar(ocupada, SECRET, shaDe("original"));
		mockMvc.perform(ocupada).andExpect(status().isOk());
		gravar("copias/fonte.txt", "fonte da copia");

		var resposta = copiar("copias/fonte.txt", "copias/ocupada.png", "REPLACE",
			"Content-Type", "text/plain", "x-amz-meta-dono", "invasor");

		assertThat(resposta.getStatus()).isEqualTo(409);
		assertThat(resposta.getContentAsString()).contains("<Code>ObjectAlreadyStored</Code>");
		var cabecalhos = cabecalhosDe("copias/ocupada.png");
		assertThat(cabecalhos.getContentType()).isEqualTo("image/png");
		assertThat(cabecalhos.getHeader("x-amz-meta-dono")).isEqualTo("original");
		assertThat(ler("copias/ocupada.png")).isEqualTo("original");
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

	/**
	 * O `Range` que o módulo não vai servir sai como corpo inteiro, não como faixa do primeiro intervalo.
	 *
	 * <p><b>Antes:</b> `ByteRange.parse` devolvia vazio para `bytes=0-1,5-6`, e o `ContentService`
	 * lia vazio como "sirva tudo" — certo. Mas o `Header` é copiado para a resposta em `finish`, e o
	 * Spring, vendo `Range: bytes=0-1,5-6` com corpo de 12 bytes, <b>aplicava a faixa</b>: respondia
	 * `206` com `Content-Range: bytes 0-1/12` e 2 bytes para quem pediu dois intervalos. O cliente
	 * recebia uma resposta <b>multipart declarada como faixa simples</b> — lixo que nenhum leitor de
	 * `multipart/byteranges` entende.</p>
	 *
	 * <p><b>Agora:</b> o cabeçalho do pedido não volta na resposta; o `Range` só é lido, nunca ecoado.
	 * A resposta é `200` com os 12 bytes inteiros.</p>
	 */
	@Test
	void range_com_mais_de_um_intervalo_responde_o_corpo_inteiro() throws Exception {
		String conteudo = "0123456789ab";
		gravar("faixas/varios.txt", conteudo);

		MockHttpServletRequestBuilder leitura = get("/s3/" + bucket + "/faixas/varios.txt")
			.header(HttpHeaders.RANGE, "bytes=0-1,5-6");
		assinar(leitura, SECRET, shaVazio());
		var resultado = mockMvc.perform(leitura).andReturn();
		if (resultado.getRequest().isAsyncStarted()) {
			resultado = mockMvc.perform(asyncDispatch(resultado)).andReturn();
		}
		var resposta = resultado.getResponse();
		assertThat(resposta.getStatus()).as(resposta.getContentAsString()).isEqualTo(200);
		assertThat(resposta.getContentAsByteArray()).as("a faixa simples cobriu o corpo inteiro")
			.isEqualTo(conteudo.getBytes(StandardCharsets.UTF_8));
		assertThat(resposta.getHeader(HttpHeaders.CONTENT_RANGE)).as("o corpo inteiro nao tem Content-Range").isNull();
	}

	/**
	 * Objeto cuja cópia não está neste nó responde `503 SlowDown` no XML do S3, e não `500` sem corpo.
	 *
	 * <p><b>Antes:</b> o `GET` era `200` com `Content-Length: 12` e <b>zero bytes</b>: a cópia era
	 * procurada dentro do corpo da resposta (o `StreamingResponseBody`), depois de os cabeçalhos já
	 * terem saído — o cliente recebia um download truncado <b>sem erro</b>. E a recusa que viesse
	 * antes disso (a cópia está em outro nó, ou não há cópia registrada) saía como o erro do
	 * control plane: `409 STORAGE_REPLICA_ELSEWHERE` / `500` sem corpo, JSON que o SDK não lê.</p>
	 *
	 * <p><b>Agora:</b> a cópia é aberta <b>antes</b> do primeiro byte; se ela não abre, a resposta é o
	 * XML do S3 com `503 SlowDown` e `Retry-After` — o `ClientException` literal que o
	 * {@code S3Exception.retryable} do SDK repete. Um objeto sem cópia nenhuma continua `409`, mas
	 * agora em XML.</p>
	 */
	@Test
	void copia_fora_deste_no_responde_503_em_xml() throws Exception {
		gravar("remota/so.txt", "0123456789ab");
		// A cópia está em outro nó: os bytes saem da raiz desta instalação e a colocação aponta para um
		// nó que não é este. É o que sobra quando o objeto foi replicado e a réplica 0 foi podada.
		jdbc.update("""
			update network_storage_placements set node_id = 'nd_outro_no'
			where object_id in (select id from network_storage_objects where bucket = ? and object_key = ?)
			""", bucket, "remota/so.txt");
		Path raiz = Path.of(System.getProperty("java.io.tmpdir"), "enois-storage");
		for (String id : jdbc.queryForList("select id from network_storage_objects where bucket = ? and object_key = ?",
				String.class, bucket, "remota/so.txt")) {
			Files.deleteIfExists(raiz.resolve(id));
		}

		MockHttpServletRequestBuilder leitura = get("/s3/" + bucket + "/remota/so.txt");
		assinar(leitura, SECRET, shaVazio());
		var resultado = mockMvc.perform(leitura).andReturn();
		if (resultado.getRequest().isAsyncStarted()) {
			resultado = mockMvc.perform(asyncDispatch(resultado)).andReturn();
		}
		var resposta = resultado.getResponse();
		assertThat(resposta.getStatus()).as(resposta.getContentAsString()).isEqualTo(503);
		assertThat(resposta.getHeader(HttpHeaders.RETRY_AFTER)).as("sem Retry-After o SDK nao sabe quando tentar")
			.isNotNull();
		assertThat(resposta.getContentAsString()).as("o corpo e o XML do S3").contains("<Code>SlowDown</Code>");

		MockHttpServletRequestBuilder cabecalho = head("/s3/" + bucket + "/remota/so.txt");
		assinar(cabecalho, SECRET, shaVazio());
		assertThat(mockMvc.perform(cabecalho).andReturn().getResponse().getStatus()).isEqualTo(503);
	}

	/**
	 * {@code If-Modified-Since} com a data que o próprio gateway devolveu em {@code Last-Modified}
	 * dá <b>304</b>, e data anterior dá o corpo.
	 *
	 * <p><b>Antes:</b> o GetObject só olhava {@code If-None-Match}. Um SDK que revalida por data
	 * (ou um cliente atrás de CDN que normaliza o validador para {@code Last-Modified}) recebia o
	 * objeto inteiro de novo; num vídeo de gigabytes, é a transferência toda por nada.</p>
	 *
	 * <p><b>Agora:</b> a data é comparada em <b>segundos</b>, a granularidade do HTTP-date. O teste
	 * devolve o cabeçalho recebido com {@code updated_at} em milissegundos ({@code .750}): sem
	 * truncar, a comparação diria "mudou" sempre.</p>
	 */
	@Test
	void get_condicional_por_data_devolve_304() throws Exception {
		gravar("cache/revalidado.txt", "conteudo cacheavel");
		String id = jdbc.queryForObject(
			"select id from network_storage_objects where bucket = ? and object_key = ?", String.class, bucket,
			"cache/revalidado.txt");
		jdbc.update("update network_storage_objects set updated_at = ? where id = ?",
			java.sql.Timestamp.from(Instant.parse("2026-11-01T00:00:00.750Z")), id);

		MockHttpServletRequestBuilder primeiro = get("/s3/" + bucket + "/cache/revalidado.txt");
		assinar(primeiro, SECRET, shaVazio());
		var resultado = mockMvc.perform(primeiro).andReturn();
		if (resultado.getRequest().isAsyncStarted()) {
			resultado = mockMvc.perform(asyncDispatch(resultado)).andReturn();
		}
		String modificadoEm = resultado.getResponse().getHeader(HttpHeaders.LAST_MODIFIED);
		assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
		assertThat(modificadoEm).isNotBlank();

		MockHttpServletRequestBuilder revalidacao = get("/s3/" + bucket + "/cache/revalidado.txt")
			.header(HttpHeaders.IF_MODIFIED_SINCE, modificadoEm);
		assinar(revalidacao, SECRET, shaVazio());
		mockMvc.perform(revalidacao)
			.andExpect(status().isNotModified())
			.andExpect(header().string(HttpHeaders.ETAG, "\"" + shaDe("conteudo cacheavel") + "\""));

		MockHttpServletRequestBuilder antiga = get("/s3/" + bucket + "/cache/revalidado.txt")
			.header(HttpHeaders.IF_MODIFIED_SINCE, "Sat, 31 Oct 2026 00:00:00 GMT");
		assinar(antiga, SECRET, shaVazio());
		var comCorpo = mockMvc.perform(antiga).andReturn();
		if (comCorpo.getRequest().isAsyncStarted()) {
			comCorpo = mockMvc.perform(asyncDispatch(comCorpo)).andReturn();
		}
		assertThat(comCorpo.getResponse().getStatus()).isEqualTo(200);
		assertThat(comCorpo.getResponse().getContentAsString()).isEqualTo("conteudo cacheavel");
	}

	/**
	 * O HEAD condicional também responde <b>304</b> — por ETag ou por data.
	 *
	 * <p><b>Antes:</b> o HeadObject ignorava os dois condicionais e sempre devolvia 200 com os
	 * cabeçalhos. O SDK usa o HEAD para decidir se baixa o objeto; 200 aqui é "baixe de novo", mesmo
	 * quando o cliente já tem os bytes.</p>
	 *
	 * <p>E o {@code If-None-Match}, quando presente, <b>desliga</b> o {@code If-Modified-Since}: se o
	 * ETag não bate, a resposta é 200, ainda que a data diga "não mudou" (RFC 9110 §13.1.3).</p>
	 */
	@Test
	void head_condicional_responde_304() throws Exception {
		gravar("cache/cabecalho.txt", "conteudo do cabecalho");
		String id = jdbc.queryForObject(
			"select id from network_storage_objects where bucket = ? and object_key = ?", String.class, bucket,
			"cache/cabecalho.txt");
		jdbc.update("update network_storage_objects set updated_at = ? where id = ?",
			java.sql.Timestamp.from(Instant.parse("2026-11-01T00:00:00.750Z")), id);
		String sha = shaDe("conteudo do cabecalho");

		MockHttpServletRequestBuilder semCondicional = head("/s3/" + bucket + "/cache/cabecalho.txt");
		assinar(semCondicional, SECRET, shaVazio());
		String modificadoEm = mockMvc.perform(semCondicional).andReturn().getResponse()
			.getHeader(HttpHeaders.LAST_MODIFIED);
		assertThat(modificadoEm).isNotBlank();

		MockHttpServletRequestBuilder porEtag = head("/s3/" + bucket + "/cache/cabecalho.txt")
			.header(HttpHeaders.IF_NONE_MATCH, "\"" + sha + "\"");
		assinar(porEtag, SECRET, shaVazio());
		mockMvc.perform(porEtag).andExpect(status().isNotModified());

		MockHttpServletRequestBuilder porData = head("/s3/" + bucket + "/cache/cabecalho.txt")
			.header(HttpHeaders.IF_MODIFIED_SINCE, modificadoEm);
		assinar(porData, SECRET, shaVazio());
		mockMvc.perform(porData).andExpect(status().isNotModified());

		// ETag diferente + data igual: o If-None-Match manda sozinho e a resposta é 200.
		MockHttpServletRequestBuilder etagErrado = head("/s3/" + bucket + "/cache/cabecalho.txt")
			.header(HttpHeaders.IF_NONE_MATCH, "\"outro-digest\"")
			.header(HttpHeaders.IF_MODIFIED_SINCE, modificadoEm);
		assinar(etagErrado, SECRET, shaVazio());
		mockMvc.perform(etagErrado).andExpect(status().isOk());
	}

	/**
	 * V367: o que o cliente declara no PUT volta no HEAD e no GET.
	 *
	 * <p><b>Antes:</b> o gateway guardava tipo, tamanho e digest e descartava o resto — o
	 * {@code x-amz-meta-*} sumia, e o {@code Cache-Control} e o nome de arquivo do anexo vinham da
	 * política do TIPO, não do que o cliente pediu. <b>Agora:</b> voltam iguais.</p>
	 */
	@Test
	void o_metadado_e_os_cabecalhos_declarados_no_put_voltem_no_head_e_no_get() throws Exception {
		byte[] corpo = "laudo em pdf".getBytes(StandardCharsets.UTF_8);
		String sha = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(corpo));
		MockHttpServletRequestBuilder put = put("/s3/" + bucket + "/docs/laudo.pdf").content(corpo)
			.contentType("application/pdf")
			.header("x-amz-meta-Autor", "Ana")
			.header("x-amz-meta-setor", "juridico")
			.header(HttpHeaders.CACHE_CONTROL, "private, max-age=60")
			.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"laudo-final.pdf\"");
		assinar(put, SECRET, sha);
		mockMvc.perform(put).andExpect(status().isOk());

		MockHttpServletRequestBuilder headObject = head("/s3/" + bucket + "/docs/laudo.pdf");
		assinar(headObject, SECRET, shaVazio());
		var head = mockMvc.perform(headObject).andExpect(status().isOk()).andReturn();
		// O nome é normalizado em minúsculas, como o S3 faz: `Autor` e `autor` são a mesma chave.
		assertThat(head.getResponse().getHeader("x-amz-meta-autor")).isEqualTo("Ana");
		assertThat(head.getResponse().getHeader("x-amz-meta-setor")).isEqualTo("juridico");
		mockMvc.perform(headObject)
			// O declarado vence a política do tipo (que diria `private, no-store` para PDF).
			.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, max-age=60"))
			.andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
				"attachment; filename=\"laudo-final.pdf\""));

		MockHttpServletRequestBuilder getObject = get("/s3/" + bucket + "/docs/laudo.pdf");
		assinar(getObject, SECRET, shaVazio());
		var getResult = mockMvc.perform(getObject).andReturn();
		if (getResult.getRequest().isAsyncStarted()) {
			getResult = mockMvc.perform(asyncDispatch(getResult)).andReturn();
		}
		assertThat(getResult.getResponse().getHeader("x-amz-meta-autor")).isEqualTo("Ana");
		assertThat(getResult.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("private, max-age=60");
		assertThat(getResult.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION))
			.isEqualTo("attachment; filename=\"laudo-final.pdf\"");
		assertThat(getResult.getResponse().getContentAsByteArray()).isEqualTo(corpo);
	}

	/** Objeto sem metadado declarado continua saindo com a política do tipo, não com cabeçalho vazio. */
	@Test
	void objeto_sem_metadado_nao_ganha_cabecalho_de_metadado() throws Exception {
		gravar("sem-meta.txt", "texto");
		MockHttpServletRequestBuilder headObject = head("/s3/" + bucket + "/sem-meta.txt");
		assinar(headObject, SECRET, shaVazio());
		mockMvc.perform(headObject)
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
			.andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, org.hamcrest.Matchers.nullValue()));
	}

	/** Cabeçalho com quebra de linha seria injeção de resposta: recusado no PUT, e nada é gravado. */
	@Test
	void cabecalho_declarado_com_quebra_de_linha_e_recusado() throws Exception {
		// O dono do bucket gravado antes: sem ele, o bucket nasceria do objeto de outra conta e a
		// leitura responderia 403 por donagem, escondendo o que se quer provar (a chave não existe).
		gravar("docs/antes.txt", "ancora");
		byte[] corpo = "x".getBytes(StandardCharsets.UTF_8);
		String sha = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(corpo));
		MockHttpServletRequestBuilder put = put("/s3/" + bucket + "/docs/mau.txt").content(corpo)
			.contentType("text/plain")
			// Um objeto de outro arquivo do MESMO bucket: o bucket já nasceu do dono, e a chave não
			// existir é o que se prova. Criar `docs/mau.txt` como primeiro objeto faria o bucket ser de
			// outra conta pelo objeto gravado, e o 404 viraria 403 por um motivo que este teste não é.
			.header(HttpHeaders.CONTENT_DISPOSITION, "inline\r\nSet-Cookie: a=b");
		assinar(put, SECRET, sha);
		mockMvc.perform(put)
			.andExpect(status().isBadRequest())
			.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
				.string(org.hamcrest.Matchers.containsString("InvalidArgument")));

		// Recusar depois de gravar decoraria a linha com o que não foi aceito; a chave não pode existir.
		MockHttpServletRequestBuilder getObject = get("/s3/" + bucket + "/docs/mau.txt");
		assinar(getObject, SECRET, shaVazio());
		mockMvc.perform(getObject).andExpect(status().isNotFound());
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

	/**
	 * {@code delimiter=/} agrupa as chaves em {@code CommonPrefixes}, como o S3 — e o grupo conta
	 * como <b>uma</b> entrada no {@code MaxKeys}.
	 *
	 * <p><b>Antes:</b> o parâmetro era aceito e ignorado. Um cliente que navega "pastas" (rclone,
	 * boto3, aws-cli, a maioria dos SDKs pede {@code delimiter=/} por padrão) recebia
	 * {@code a/x/y.jpg} como {@code <Key>} em vez de ver a pasta {@code a/x/}. A navegação aparecia
	 * como centenas de arquivos soltos e as subpastas nunca eram oferecidas.</p>
	 *
	 * <p><b>Agora:</b> chave que tem o delimitador depois do prefixo não vira {@code Contents}; o
	 * trecho até o delimitador vira {@code <CommonPrefixes><Prefix>…</Prefix></CommonPrefixes>}, uma
	 * vez por grupo, em ordem. O que está direto no prefixo continua {@code Contents}.</p>
	 */
	@Test
	void delimiter_agrupa_subpastas_em_common_prefixes() throws Exception {
		gravar("fotos/raiz.jpg", "r");
		gravar("fotos/2025/um.jpg", "um");
		gravar("fotos/2025/dois.jpg", "dois");
		gravar("fotos/2026/tres.jpg", "tres");

		MockHttpServletRequestBuilder list = get("/s3/" + bucket).queryParam("list-type", "2")
			.queryParam("prefix", "fotos/").queryParam("delimiter", "/");
		assinar(list, SECRET, shaVazio());
		var result = mockMvc.perform(list).andExpect(status().isOk()).andReturn();

		String xml = result.getResponse().getContentAsString();
		// O que está direto no prefixo continua sendo chave.
		assertThat(xml).contains("<Key>fotos/raiz.jpg</Key>");
		// As subpastas viram prefixos, uma vez cada, e as chaves delas NÃO aparecem.
		assertThat(xml).contains("<CommonPrefixes><Prefix>fotos/2025/</Prefix></CommonPrefixes>");
		assertThat(xml).contains("<CommonPrefixes><Prefix>fotos/2026/</Prefix></CommonPrefixes>");
		assertThat(xml).doesNotContain("fotos/2025/um.jpg").doesNotContain("fotos/2026/tres.jpg");
		// 1 chave + 2 prefixos.
		assertThat(xml).contains("<KeyCount>3</KeyCount>");
	}

	/**
	 * Com {@code delimiter}, cada subpasta é <b>uma</b> entrada do {@code MaxKeys} — e a segunda
	 * página continua de onde a primeira parou.
	 *
	 * <p>Sem esta conta, uma página com {@code max-keys=2} que tivesse dez arquivos dentro de
	 * {@code fotos/} não caberia: ou estouraria o teto (o cliente pediu 2 e recebia 10) ou o token
	 * apontaria para a última chave lida, e o cliente repetiria o mesmo grupo na página seguinte —
	 * em ambos os casos um cliente que pagina nunca termina.</p>
	 */
	@Test
	void delimiter_pagina_contando_cada_grupo_uma_vez() throws Exception {
		gravar("p/a.txt", "a");
		gravar("p/sub/1.txt", "1");
		gravar("p/sub/2.txt", "2");
		gravar("p/z.txt", "z");

		MockHttpServletRequestBuilder primeiro = get("/s3/" + bucket).queryParam("list-type", "2")
			.queryParam("prefix", "p/").queryParam("delimiter", "/").queryParam("max-keys", "2");
		assinar(primeiro, SECRET, shaVazio());
		String xml1 = mockMvc.perform(primeiro).andExpect(status().isOk()).andReturn()
			.getResponse().getContentAsString()
			.replaceAll("<\\?xml[^>]*>", "")
			.replace("xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"", "");
		// A ordem é a das chaves: `p/a.txt`, depois o grupo `p/sub/`.
		assertThat(xml1).contains("<Key>p/a.txt</Key>");
		assertThat(xml1).contains("<CommonPrefixes><Prefix>p/sub/</Prefix></CommonPrefixes>");
		assertThat(xml1).doesNotContain("<Key>p/z.txt</Key>");
		assertThat(xml1).contains("<IsTruncated>true</IsTruncated>");

		var doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
			.parse(new java.io.ByteArrayInputStream(xml1.getBytes(StandardCharsets.UTF_8)));
		String token = doc.getElementsByTagName("NextContinuationToken").item(0).getTextContent();
		assertThat(token).isEqualTo("p/sub/");

		MockHttpServletRequestBuilder segundo = get("/s3/" + bucket).queryParam("list-type", "2")
			.queryParam("prefix", "p/").queryParam("delimiter", "/").queryParam("max-keys", "2")
			.queryParam("continuation-token", token);
		assinar(segundo, SECRET, shaVazio());
		String xml2 = mockMvc.perform(segundo).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

		assertThat(xml2).contains("<Key>p/z.txt</Key>");
		assertThat(xml2).doesNotContain("<Key>p/a.txt</Key>");
		assertThat(xml2).contains("<IsTruncated>false</IsTruncated>");
	}

	/**
	 * DeleteBucket de um bucket <b>sem objeto vivo</b> responde {@code 204} — e ele deixa de existir
	 * para o dono.
	 *
	 * <p><b>Antes:</b> a rota respondia {@code 501 NotImplemented} em <b>todos</b> os casos, com a
	 * justificativa "não há bucket vazio a apagar". A premissa estava errada: bucket existe enquanto
	 * tem objeto vivo ({@code bucketsOf} filtra {@code state <> 'DELETED'}), então um bucket cujas
	 * chaves foram todas removidas continua aparecendo em {@code ListBuckets} e o cliente não tinha
	 * como tirá-lo. Nenhum teste cobria a rota.</p>
	 *
	 * <p><b>Agora:</b> bucket sem objeto vivo e do dono → {@code 204} e o nome sai de
	 * {@code ListBuckets}; com objeto vivo → {@code 409 BucketNotEmpty}, como o S3.</p>
	 */
	@Test
	void delete_de_bucket_sem_objeto_vivo_responde_204() throws Exception {
		gravar("velho.txt", "x");
		MockHttpServletRequestBuilder delObjeto = delete("/s3/" + bucket + "/velho.txt");
		assinar(delObjeto, SECRET, shaVazio());
		mockMvc.perform(delObjeto).andExpect(status().isNoContent());

		MockHttpServletRequestBuilder delBucket = delete("/s3/" + bucket);
		assinar(delBucket, SECRET, shaVazio());
		mockMvc.perform(delBucket).andExpect(status().isNoContent());

		MockHttpServletRequestBuilder listar = get("/s3");
		assinar(listar, SECRET, shaVazio());
		var resultado = mockMvc.perform(listar).andExpect(status().isOk()).andReturn();
		assertThat(resultado.getResponse().getContentAsString()).doesNotContain("<Name>" + bucket + "</Name>");
	}

	/**
	 * Bucket que o dono da credencial <b>nunca gravou</b> continua {@code 403} — a regra nova de
	 * donagem não pode virar "qualquer bucket é apagável".
	 *
	 * <p>A prova é o catálogo, vivo ou lápide: aqui não há nem um nem outro para este dono. Sem esta
	 * guarda, o caminho do {@code 204} (bucket já esvaziado) abriria a porta para uma credencial
	 * apagar o nome de um bucket alheio que por acaso também está vazio.</p>
	 */
	@Test
	void delete_de_bucket_de_outro_dono_responde_403() throws Exception {
		MockHttpServletRequestBuilder delBucket = delete("/s3/" + bucket);
		assinar(delBucket, SECRET, shaVazio());
		var resultado = mockMvc.perform(delBucket).andReturn();

		assertThat(resultado.getResponse().getStatus()).isEqualTo(403);
		assertThat(resultado.getResponse().getContentAsString()).contains("AccessDenied");
	}

	/** Com objeto vivo o bucket não sai: 409 {@code BucketNotEmpty}, como o S3. */
	@Test
	void delete_de_bucket_com_objeto_responde_409() throws Exception {
		gravar("fica.txt", "x");

		MockHttpServletRequestBuilder delBucket = delete("/s3/" + bucket);
		assinar(delBucket, SECRET, shaVazio());
		var resultado = mockMvc.perform(delBucket).andReturn();

		assertThat(resultado.getResponse().getStatus()).isEqualTo(409);
		assertThat(resultado.getResponse().getContentAsString()).contains("BucketNotEmpty");
	}

	/**
	 * Objeto de <b>zero byte</b> é aceito, como no S3: é o marcador de "pasta" que
	 * {@code aws s3 cp --recursive} cria, e o resultado de uma escrita vazia legítima.
	 *
	 * <p><b>Antes:</b> o gateway repassava o corpo ao catálogo, e o catálogo recusa
	 * {@code STORAGE_EMPTY_BODY} (regra da fase 1, para não gravar objeto sem conteúdo). Um cliente
	 * que sincroniza uma árvore inteira — que sempre cria os marcadores de pasta — recebia
	 * {@code 400} em cada um deles e tratava a sincronização como falha, mesmo com todos os arquivos
	 * gravados. A regra era do módulo, mas o gateway S3 é compatível com o S3, e o S3 aceita 0 byte.</p>
	 */
	@Test
	void put_de_corpo_vazio_e_aceito_como_no_s3() throws Exception {
		MockHttpServletRequestBuilder put = put("/s3/" + bucket + "/marcador-vazio/")
			.content(new byte[0]).contentType("application/octet-stream");
		assinar(put, SECRET, shaVazio());
		mockMvc.perform(put).andExpect(status().isOk());

		MockHttpServletRequestBuilder head = head("/s3/" + bucket + "/marcador-vazio/");
		assinar(head, SECRET, shaVazio());
		var resposta = mockMvc.perform(head).andReturn();
		assertThat(resposta.getResponse().getStatus()).isEqualTo(200);
		assertThat(resposta.getResponse().getHeader("Content-Length")).isEqualTo("0");

		// O GET devolve corpo vazio com 200 — não 404 nem 416.
		MockHttpServletRequestBuilder get = get("/s3/" + bucket + "/marcador-vazio/");
		assinar(get, SECRET, shaVazio());
		var leitura = mockMvc.perform(get).andReturn();
		assertThat(leitura.getResponse().getStatus()).isEqualTo(200);
		assertThat(leitura.getResponse().getContentAsByteArray()).isEmpty();
	}

	/** DeleteObject responde 204 e a chave deixa de ser servível — a lápide, como no S3 versionado. */
	@Test
	void delete_vira_lapide() throws Exception {
		gravar("apagar.txt", "x");

		MockHttpServletRequestBuilder del = MockMvcRequestBuilders
			.delete("/s3/" + bucket + "/apagar.txt");
		assinar(del, SECRET, shaVazio());
		mockMvc.perform(del).andExpect(status().isNoContent());

		MockHttpServletRequestBuilder getObject = get("/s3/" + bucket + "/apagar.txt");
		assinar(getObject, SECRET, shaVazio());
		// A lápide é a última linha do dono naquele bucket: `bucketsOf` filtra `state <> 'DELETED'` e o
		// bucket deixa de "existir" para ele. A recusa é 403 ("não é do dono"), não 404 — a ordem
		// donagem-antes-de-existência está documentada no `getObject`.
		var depoisDaLapide = mockMvc.perform(getObject).andReturn();
		assertThat(depoisDaLapide.getResponse().getStatus()).isIn(403, 404);
	}

	/**
	 * `start-after` do ListObjectsV2 começa a listagem **depois** daquela chave.
	 *
	 * <p><b>Antes:</b> o parâmetro era ignorado. `aws s3api list-objects-v2 --start-after b` e o
	 * `listObjectsV2(... .startAfter("b"))` do SDK recebiam a listagem <b>desde o começo</b>, com
	 * `200` — o cliente que retoma uma varredura a partir de uma chave reescreve tudo o que já
	 * processou, e num bucket grande isso é a listagem inteira de novo. O `marker` (v1) e o
	 * `continuation-token` (v2) funcionam; o `start-after` — a forma que o V2 usa para começar no
	 * meio — não existia.</p>
	 *
	 * <p><b>Agora:</b> `start-after` é a chave de partida do V2, e tem precedência sobre o
	 * `continuation-token` (o S3 o ignora quando há token: o token já diz onde continuar). Só vale
	 * no `list-type=2`; no V1 o parâmetro não é dele.</p>
	 */
	@Test
	void list_v2_start_after_comeca_depois_da_chave() throws Exception {
		gravar("inicio/a.txt", "a");
		gravar("inicio/b.txt", "b");
		gravar("inicio/c.txt", "c");

		MockHttpServletRequestBuilder lista = get("/s3/" + bucket).queryParam("list-type", "2")
			.queryParam("prefix", "inicio/").queryParam("start-after", "inicio/b.txt");
		assinar(lista, SECRET, shaVazio());
		var resposta = mockMvc.perform(lista).andReturn().getResponse();
		assertThat(resposta.getStatus()).as(resposta.getContentAsString()).isEqualTo(200);
		String corpo = resposta.getContentAsString();
		assertThat(corpo).as("a chave de partida nao pode vir na listagem").doesNotContain("<Key>inicio/a.txt</Key>")
			.doesNotContain("<Key>inicio/b.txt</Key>");
		assertThat(corpo).contains("<Key>inicio/c.txt</Key>");
		assertThat(corpo).contains("<KeyCount>1</KeyCount>");

		// Com `continuation-token`, o token manda — e os dois valores são DIFERENTES, senão o teste
		// não distingue "token venceu" de "start-after venceu": seria a mesma chave de partida.
		MockHttpServletRequestBuilder comToken = get("/s3/" + bucket).queryParam("list-type", "2")
			.queryParam("prefix", "inicio/").queryParam("start-after", "inicio/c.txt")
			.queryParam("continuation-token", "inicio/a.txt");
		assinar(comToken, SECRET, shaVazio());
		var comTokenResposta = mockMvc.perform(comToken).andReturn().getResponse();
		assertThat(comTokenResposta.getStatus()).isEqualTo(200);
		String comTokenCorpo = comTokenResposta.getContentAsString();
		assertThat(comTokenCorpo).as("o token tem de vencer o start-after").contains("<Key>inicio/b.txt</Key>")
			.contains("<Key>inicio/c.txt</Key>").doesNotContain("<Key>inicio/a.txt</Key>");
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

	/**
	 * Chave com caracteres de XML: o documento tem de continuar sendo XML válido depois do escape.
	 *
	 * <p>Sem isso, um objeto chamado {@code <script>.txt} num bucket qualquer quebraria a resposta
	 * de quem lista — e é o caminho que o CodeQL cobriu ({@code java/xss}) no PR #914.</p>
	 */
	@Test
	void chave_com_caractere_de_xml_nao_quebra_a_listagem() throws Exception {
		// `&` e `'` passam na URL sem percent-encoding (ao contrário de `<`, `>` e `"`, que o cliente
		// codifica). São os que provam o escape do documento sem confundir com encode de URL.
		String chave = "a&b'c.txt";
		gravar(chave, "conteudo");

		MockHttpServletRequestBuilder list = get("/s3/" + bucket).queryParam("list-type", "2");
		assinar(list, SECRET, shaVazio());
		var result = mockMvc.perform(list).andExpect(status().isOk()).andReturn();

		String xml = result.getResponse().getContentAsString();
		// O documento continua parseável e a chave aparece escapada.
		javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
			.parse(new java.io.ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
		// O `&` é escapado (obrigatório em conteúdo); `'` é válido sem escape, e o `XMLStreamWriter`
		// não o escapa — o teste aceita as duas formas equivalentes em XML.
		assertThat(xml).containsPattern("<Key>a&amp;b(&apos;|')c\\.txt</Key>");
	}

	// ---------------------------------------------------------------- helpers: o "SDK"

	/** Grava com a credencial do dono — a conta que vai emitir a URL. */
	private void gravarPeloDono(String key, String conteudo, String accessKeyDono) throws Exception {
		byte[] bytes = conteudo.getBytes(StandardCharsets.UTF_8);
		MockHttpServletRequestBuilder put = put("/s3/" + bucket + "/" + key).content(bytes).contentType("text/plain");
		assinar(put, SECRET, shaDe(conteudo), accessKeyDono);
		mockMvc.perform(put).andExpect(status().isOk());
	}

	/** Executa um CopyObject de {@code origem} para {@code destino}, opcionalmente com REPLACE. */
	private org.springframework.mock.web.MockHttpServletResponse copiar(String origem, String destino,
			String diretiva, String... cabecalhos) throws Exception {
		MockHttpServletRequestBuilder copia = put("/s3/" + bucket + "/" + destino)
			.header("x-amz-copy-source", "/" + bucket + "/" + origem)
			.content(new byte[0]);
		if (diretiva != null) {
			copia.header("x-amz-metadata-directive", diretiva);
		}
		for (int i = 0; i + 1 < cabecalhos.length; i += 2) {
			copia.header(cabecalhos[i], cabecalhos[i + 1]);
		}
		assinar(copia, SECRET, shaVazio());
		return mockMvc.perform(copia).andReturn().getResponse();
	}

	/** O corpo do objeto, com o {@code asyncDispatch} que o {@code StreamingResponseBody} exige. */
	private String ler(String chave) throws Exception {
		MockHttpServletRequestBuilder leitura = get("/s3/" + bucket + "/" + chave);
		assinar(leitura, SECRET, shaVazio());
		var resultado = mockMvc.perform(leitura).andReturn();
		if (resultado.getRequest().isAsyncStarted()) {
			resultado = mockMvc.perform(asyncDispatch(resultado)).andReturn();
		}
		assertThat(resultado.getResponse().getStatus())
			.as("GET " + chave + ": " + resultado.getResponse().getContentAsString()).isEqualTo(200);
		return resultado.getResponse().getContentAsString();
	}

	/** Os cabeçalhos e o status de um HEAD, sem corpo para drenar. */
	private org.springframework.mock.web.MockHttpServletResponse cabecalhosDe(String chave) throws Exception {
		MockHttpServletRequestBuilder requisicao = head("/s3/" + bucket + "/" + chave);
		assinar(requisicao, SECRET, shaVazio());
		return mockMvc.perform(requisicao).andReturn().getResponse();
	}

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
		assinar(builder, secret, payloadHash, accessKey);
	}

	/** A mesma assinatura, com outra credencial — os testes com duas contas precisam das duas. */
	private void assinar(MockHttpServletRequestBuilder builder, String secret, String payloadHash, String accessKeyUsada) {
		String amzDate = AMZ.format(CLOCK.instant());
		builder.header("x-amz-date", amzDate).header("x-amz-content-sha256", payloadHash)
			.header(HttpHeaders.AUTHORIZATION, assinarComData(builder, secret, payloadHash, amzDate,
				amzDate.substring(0, 8), accessKeyUsada));
	}

	private String assinarComData(MockHttpServletRequestBuilder builder, String secret, String payloadHash,
			String amzDate, String dateStamp) {
		return assinarComData(builder, secret, payloadHash, amzDate, dateStamp, accessKey);
	}

	private String assinarComData(MockHttpServletRequestBuilder builder, String secret, String payloadHash,
			String amzDate, String dateStamp, String accessKeyUsada) {
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
		var parsed = new SigV4Verifier.Parsed(accessKeyUsada, "enois-1", "s3", signedHeaders, "");
		String signature = SigV4Verifier.signature(request, parsed, secret, dateStamp);
		return "AWS4-HMAC-SHA256 Credential=" + accessKeyUsada + "/" + dateStamp + "/enois-1/s3/aws4_request, SignedHeaders="
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

	/**
	 * A URL emitida -> a query que o servidor enxerga, como um navegador faria: <b>uma</b> decodificação.
	 *
	 * <p>O emissor escreve o valor exatamente como ele é assinado, e o servlet entrega decodificado
	 * uma vez — então a query chega igual à emitida. Decodificar no teste provaria uma requisição que
	 * nenhum cliente real monta.</p>
	 */
	private static String decodificaUmaVez(String consulta) {
		return consulta;
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
