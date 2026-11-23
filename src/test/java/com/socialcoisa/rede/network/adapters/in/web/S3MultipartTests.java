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
import org.springframework.test.context.TestPropertySource;
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
@TestPropertySource(properties = "rede.storage.max-bytes-per-owner=" + S3MultipartTests.COTA)
class S3MultipartTests {

	/** Cota de 20 MiB: três partes de 6 MiB cabem, a quarta não. */
	static final long COTA = 20L * 1024 * 1024;

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

	/**
	 * O {@code NoSuchUpload} sai no formato do S3 — <b>XML com {@code Content-Type:
	 * application/xml}</b> —, não no JSON do resto da API.
	 *
	 * <p>Um SDK (boto3, aws-cli, MinIO) deserializa o corpo pelo {@code Content-Type}: JSON aqui
	 * chega como corpo irreconhecível, e o cliente trata como falha de rede ou erro genérico em vez
	 * de "este upload não existe, recomece". A rota respondia 404 com o código certo num documento
	 * que o SDK não sabe ler.</p>
	 */
	/**
	 * O upload multipart <b>aparece no painel de operações</b>: abrir, enviar parte, listar e
	 * concluir todas geram amostra no mesmo contador do catálogo.
	 *
	 * <p><b>Antes:</b> só o catálogo de objetos contava (`PUT`/`GET`/`HEAD`/`DELETE`/`LIST`). O
	 * {@code StorageMultipartService} — que serve justamente os arquivos grandes — não registrava
	 * nada: o painel dizia "multipart nunca acontece" enquanto um cliente enviava gigabytes. E o teto
	 * de requisição por Access Key conta as mesmas rotas, então a operação que mais consome banda era
	 * a que não aparecia.</p>
	 *
	 * <p><b>Agora:</b> abrir conta {@code PUT} (é a criação do upload), cada parte conta {@code PUT},
	 * {@code ListParts} conta {@code LIST} e a conclusão conta {@code PUT} — os mesmos verbos do S3,
	 * para o número ser comparável ao de um cliente de verdade.</p>
	 */
	@Test
	void upload_multipart_conta_no_painel_de_operacoes() throws Exception {
		String chave = "grande/%s.bin".formatted(sufixo());
		long antes = amostras();

		String uploadId = abrir(chave);
		enviarParte(chave, uploadId, 1, "conteudo".getBytes(StandardCharsets.UTF_8));
		mockMvc.perform(assinar(get("/s3/" + bucket + "/" + chave).param("uploadId", uploadId), shaVazio()))
			.andExpect(status().isOk());
		concluir(chave, uploadId, 1);

		// abrir (PUT) + parte (PUT) + listar (LIST) + concluir (PUT) = 4 amostras novas. Sem a
		// instrumentação são zero: o multipart serve arquivos grandes e não aparecia em lugar nenhum.
		assertThat(amostras() - antes).isEqualTo(4);
		var verbos = jdbc.queryForList("select distinct operation from network_storage_operation_metrics",
			String.class);
		assertThat(verbos).contains("PUT", "LIST");
	}

	/** O total de requisições que o painel de operações já tem gravado. */
	private long amostras() {
		Long total = jdbc.queryForObject("select coalesce(sum(req_count), 0) from network_storage_operation_metrics",
			Long.class);
		return total == null ? 0 : total;
	}

	@Test
	void upload_inexistente_responde_xml_do_s3_e_nao_json() throws Exception {
		MockHttpServletRequestBuilder pedido = get("/s3/" + bucket + "/" + "qualquer.bin")
			.param("uploadId", "up_nao_existe_000000");
		var recusa = mockMvc.perform(assinar(pedido, shaVazio())).andReturn();

		assertThat(recusa.getResponse().getStatus()).isEqualTo(404);
		assertThat(recusa.getResponse().getContentType()).startsWith("application/xml");
		String corpo = recusa.getResponse().getContentAsString();
		assertThat(corpo).startsWith("<?xml").contains("<Code>NoSuchUpload</Code>");
		assertThat(corpo).doesNotContain("\"code\"");
	}

	/**
	 * Toda recusa que nasce nos serviços do Storage chega ao cliente do S3 em <b>XML</b> — inclusive
	 * as que nunca passaram por tradução, como o {@code expires} fora do intervalo do {@code POST
	 * ?uploads} e o {@code InvalidPartOrder} da conclusão.
	 *
	 * <p>Antes, só `QuotaExceeded` e `STORAGE_PLACEMENT_MISSING` eram traduzidos; o resto saía como
	 * JSON do Control Plane, corpo que o SDK não deserializa. O cliente via "erro de rede" em vez de
	 * "o prazo pedido é longo demais" ou "as partes estão fora de ordem".</p>
	 */
	@Test
	void recusas_do_multipart_saem_em_xml() throws Exception {
		MockHttpServletRequestBuilder prazo = post("/s3/" + bucket + "/" + "grande.bin")
			.queryParam("uploads", "").queryParam("expires", "99999999");
		var prazoRuim = mockMvc.perform(assinar(prazo, shaVazio())).andReturn();
		assertThat(prazoRuim.getResponse().getStatus()).isEqualTo(400);
		assertThat(prazoRuim.getResponse().getContentType()).startsWith("application/xml");
		assertThat(prazoRuim.getResponse().getContentAsString()).startsWith("<?xml")
			.contains("<Code>InvalidArgument</Code>");

		MockHttpServletRequestBuilder naoNumero = post("/s3/" + bucket + "/" + "grande.bin")
			.queryParam("uploads", "").queryParam("expires", "abc");
		var naoEhNumero = mockMvc.perform(assinar(naoNumero, shaVazio())).andReturn();
		assertThat(naoEhNumero.getResponse().getStatus()).isEqualTo(400);
		assertThat(naoEhNumero.getResponse().getContentAsString()).startsWith("<?xml")
			.contains("<Code>InvalidArgument</Code>");
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

	/**
	 * O {@code ListParts} de um {@code uploadId} só responde pela chave daquele upload.
	 *
	 * <p><b>Antes:</b> a rota conferia o dono do upload e a donagem do bucket, e mais nada — o
	 * {@code key} da URL nunca era comparado com o {@code key} gravado no upload. Um cliente que
	 * trocasse a chave (bug de concatenação, retomada com a chave errada) recebia as partes de
	 * <b>outro</b> arquivo com 200, e nada no documento denunciava a troca. A S3 responde
	 * {@code 404 NoSuchUpload}: para a chave pedida, aquele upload não existe.</p>
	 *
	 * <p>O discriminante é o par: a chave certa devolve as partes; a chave errada, com o mesmo
	 * {@code uploadId} e a mesma credencial, é {@code 404}.</p>
	 */
	@Test
	void listar_partes_de_outra_chave_e_recusado() throws Exception {
		String chave = "outros/%s.bin".formatted(sufixo());
		String uploadId = abrir(chave);
		enviarParte(chave, uploadId, 1, "conteudo".getBytes(StandardCharsets.UTF_8));

		var certo = mockMvc.perform(assinar(get("/s3/" + bucket + "/" + chave).param("uploadId", uploadId),
			shaVazio())).andReturn();
		assertThat(certo.getResponse().getStatus()).as(certo.getResponse().getContentAsString()).isEqualTo(200);
		assertThat(certo.getResponse().getContentAsString()).contains("<PartNumber>1</PartNumber>");

		var trocado = mockMvc.perform(assinar(get("/s3/" + bucket + "/outros/outra.bin").param("uploadId", uploadId),
			shaVazio())).andReturn();
		assertThat(trocado.getResponse().getStatus()).as("chave de outro arquivo nao pode ver as partes")
			.isEqualTo(404);
		assertThat(trocado.getResponse().getContentAsString()).contains("NoSuchUpload");
	}

	/**
	 * O {@code AbortMultipartUpload} com a chave errada <b>não</b> apaga o upload.
	 *
	 * <p>É o caso mais caro do defeito: {@code ListParts} da chave errada mostrava o que não devia,
	 * mas o {@code Abort} da chave errada destruía o trabalho de outro arquivo — um cliente que
	 * concatena a chave errada perdia as partes já enviadas sem nunca ter pedido isso.</p>
	 */
	@Test
	void abortar_pela_chave_errada_nao_apaga_o_upload() throws Exception {
		String chave = "outros/%s.bin".formatted(sufixo());
		String uploadId = abrir(chave);
		enviarParte(chave, uploadId, 1, "conteudo".getBytes(StandardCharsets.UTF_8));

		var errado = mockMvc.perform(assinar(delete("/s3/" + bucket + "/outros/outra.bin")
			.param("uploadId", uploadId), shaVazio())).andReturn();
		assertThat(errado.getResponse().getStatus()).as(errado.getResponse().getContentAsString()).isEqualTo(404);

		// O upload continua inteiro: as partes continuam lá e a conclusão funciona.
		var partes = mockMvc.perform(assinar(get("/s3/" + bucket + "/" + chave).param("uploadId", uploadId),
			shaVazio())).andReturn();
		assertThat(partes.getResponse().getStatus()).isEqualTo(200);
		assertThat(partes.getResponse().getContentAsString()).contains("<PartNumber>1</PartNumber>");
		concluir(chave, uploadId, 1);
	}

	/**
	 * As partes de um multipart <b>contam contra a cota do participante antes de a conclusão chegar</b>.
	 *
	 * <p><b>Antes:</b> a cota era conferida na abertura do upload e na conclusão, e <b>nunca</b> nas
	 * partes. Um participante com cota de 1 MB podia abrir um multipart, mandar 10 000 partes de 5 MiB
	 * (o teto de número de parte) e nunca concluir: 50 GiB em disco, com a cota só olhando quando já
	 * era tarde demais. O {@code abort} existe, mas depende de quem enviou querer — e quem abusa não
	 * quer.</p>
	 *
	 * <p><b>Agora:</b> cada parte confere {@code usado + bytes das partes abertas + esta parte} contra
	 * a cota, com o mesmo {@code 507} da conclusão. O que o teste discrimina é o <b>momento</b>: a
	 * recusa chega no envio da parte, com o upload ainda aberto — não na conclusão, e não depois.</p>
	 */
	@Test
	void parte_que_estoura_a_cota_e_recusada_no_envio() throws Exception {
		String chave = "grande/%s.bin".formatted(sufixo());
		String uploadId = abrir(chave);

		// Duas partes de 6 MiB caberiam no teto do objeto; a cota (20 MiB) não as aceita juntas.
		enviarParte(chave, uploadId, 1, bytes('a', 6 * 1024 * 1024));

		// 3 partes de 6 MiB = 18 MiB; outra de 6 MiB passa de 20 MiB.
		enviarParte(chave, uploadId, 2, bytes('b', 6 * 1024 * 1024));
		enviarParte(chave, uploadId, 3, bytes('c', 6 * 1024 * 1024));
		byte[] quarta = bytes('d', 6 * 1024 * 1024);
		var estouro = mockMvc.perform(assinar(put("/s3/" + bucket + "/" + chave)
			.param("partNumber", "4").param("uploadId", uploadId).content(quarta), shaOfBytes(quarta)))
			.andReturn();
		assertThat(estouro.getResponse().getStatus()).as("a cota tem de morder no envio da parte")
			.isEqualTo(507);
		assertThat(estouro.getResponse().getContentAsString()).contains("QuotaExceeded");

		// O upload continua aberto e o que já chegou continua lá: recusar uma parte não apaga as outras.
		var partes = mockMvc.perform(assinar(get("/s3/" + bucket + "/" + chave).param("uploadId", uploadId),
			shaVazio())).andReturn();
		assertThat(partes.getResponse().getContentAsString()).contains("<PartNumber>3</PartNumber>")
			.doesNotContain("<PartNumber>4</PartNumber>");
	}

	/**
	 * O espaço <b>reservado</b> em partes abertas conta na abertura do próximo upload.
	 *
	 * <p><b>Antes:</b> a cota na abertura ({@code beginUpload}) olhava só o que já havia virado
	 * objeto. Quem tinha a cota inteira em partes de multiparts abertos abria outro upload sem
	 * recusa: a chave nascia {@code PENDING}, o corpo atravessava a rede, e só a gravação recusava
	 * — depois de o participante ter pago o upload. O limite deixava de ser um limite.</p>
	 *
	 * <p><b>Agora:</b> a abertura soma o reservado e recusa antes de a chave nascer, com o mesmo
	 * {@code 507} e dizendo <b>onde</b> está o espaço — em partes abertas, não em objeto gravado. O
	 * que o teste discrimina é o <b>momento</b>: a recusa com a chave ainda inexistente.</p>
	 */
	@Test
	void espaco_reservado_em_partes_conta_na_abertura_do_proximo_upload() throws Exception {
		// Dois multiparts de 10 MiB numa cota de 20 MiB: o reservado chega exatamente à cota sem
		// nenhum byte virado objeto.
		String chave = "reserva/%s.bin".formatted(sufixo());
		String uploadId = abrir(chave);
		enviarParte(chave, uploadId, 1, bytes('a', 10 * 1024 * 1024));
		String reservado = "reserva/%s-outro.bin".formatted(sufixo());
		String outro = abrir(reservado);
		enviarParte(reservado, outro, 1, bytes('r', 10 * 1024 * 1024));

		// O PUT simples abre a gravação pelo `beginUpload`, que não conhece o tamanho do corpo: com o
		// reservado acima da cota, nem um byte novo cabe, e a recusa tem de ser já na abertura.
		byte[] corpo = bytes('z', 4 * 1024 * 1024);
		String terceira = "reserva/%s-terceira.bin".formatted(sufixo());
		var resposta = mockMvc.perform(assinar(put("/s3/" + bucket + "/" + terceira).content(corpo),
			shaOfBytes(corpo))).andReturn();

		assertThat(resposta.getResponse().getStatus()).as("o reservado em partes tem de contar")
			.isEqualTo(507);
		assertThat(resposta.getResponse().getContentAsString()).contains("QuotaExceeded").contains("20971520")
			.contains("em partes abertas");
		assertThat(jdbc.queryForObject("select count(*) from network_storage_objects where object_key = ?",
			Integer.class, terceira)).as("recusou antes de a chave nascer").isZero();

		// Abortado o segundo, o reservado cai para 10 de 20 MiB e a mesma abertura passa.
		var f = mockMvc.perform(assinar(delete("/s3/" + bucket + "/" + reservado).param("uploadId", outro),
			shaVazio())).andReturn();
		assertThat(f.getResponse().getStatus()).isEqualTo(204);
		var depois = mockMvc.perform(assinar(put("/s3/" + bucket + "/" + terceira).content(corpo),
			shaOfBytes(corpo))).andReturn();
		assertThat(depois.getResponse().getStatus()).as(depois.getResponse().getContentAsString())
			.isEqualTo(200);
	}

	/**
	 * A conclusão de um multipart <b>não</b> cobra as partes de novo.
	 *
	 * <p><b>Antes:</b> a conclusão chama {@code beginUpload}, que passou a somar o que está reservado
	 * em partes (ciclo 89). Mas as partes daquele upload <b>são</b> o corpo que está chegando, já
	 * cobradas no envio. Somadas outra vez, um objeto que cabe na cota falhava na conclusão sempre que
	 * houvesse <b>outro</b> upload reservando o resto: dois multiparts de 10 MiB numa cota de 20 MiB, e
	 * o primeiro a concluir somava 10 (o seu) + 10 (o outro) contra a cota, e recusava — embora o corpo
	 * dele coubesse sozinho.</p>
	 */
	@Test
	void conclusao_nao_cobra_as_partes_de_novo() throws Exception {
		// Dois multiparts de 10 MiB: o reservado total é a cota inteira.
		String chave = "conclusao/%s.bin".formatted(sufixo());
		String uploadId = abrir(chave);
		enviarParte(chave, uploadId, 1, bytes('a', 10 * 1024 * 1024));
		String segunda = "conclusao/%s-outro.bin".formatted(sufixo());
		String outroId = abrir(segunda);
		enviarParte(segunda, outroId, 1, bytes('b', 10 * 1024 * 1024));

		// O primeiro conclui: o corpo (10 MiB) cabe; somar as partes dele ao outro upload (10) daria
		// 20 MiB e a conclusão recusaria.
		String resposta = concluir(chave, uploadId, 1);

		assertThat(resposta).contains("<CompleteMultipartUploadResult");
		assertThat(jdbc.queryForObject("select bytes from network_storage_objects where bucket = ?"
			+ " and object_key = ? and state = 'STORED'", Long.class, bucket, chave))
			.isEqualTo(10L * 1024 * 1024);
	}

	/**
	 * A lista de partes da conclusão não pode repetir nem voltar um número.
	 *
	 * <p><b>Antes:</b> a conclusão aceitava qualquer sequência que existisse. {@code [1, 1]} montava
	 * {@code part-1} <b>duas vezes</b> e gravava um objeto com o dobro do conteúdo do upload — o cliente
	 * recebia 200 e um arquivo que ele não mandou. O S3 recusa com {@code InvalidPartOrder}.</p>
	 *
	 * <p>O discriminante é o par: a lista com repetição recusa <b>e</b> o upload continua com a parte
	 * dele (a conclusão que falha não apaga nada); depois, a lista certa conclui com o tamanho da parte,
	 * não com o dobro.</p>
	 */
	@Test
	void conclusao_com_parte_repetida_e_recusada_sem_gravar_em_dobro() throws Exception {
		String chave = "ordem/%s.bin".formatted(sufixo());
		String uploadId = abrir(chave);
		enviarParte(chave, uploadId, 1, bytes('a', 6 * 1024 * 1024));
		enviarParte(chave, uploadId, 2, bytes('b', 1));

		String xml = xmlDePartes(1, 1);
		String sha = shaOfBytes(xml.getBytes(StandardCharsets.UTF_8));
		var recusa = mockMvc.perform(assinar(post("/s3/" + bucket + "/" + chave).param("uploadId", uploadId)
			.contentType("application/xml").content(xml), sha)).andReturn();
		assertThat(recusa.getResponse().getStatus()).as(recusa.getResponse().getContentAsString())
			.isEqualTo(400);
		assertThat(recusa.getResponse().getContentAsString()).contains("InvalidPartOrder");
		assertThat(jdbc.queryForObject("select count(*) from network_storage_objects where bucket = ?"
			+ " and object_key = ?", Integer.class, bucket, chave)).as("nada foi gravado").isZero();

		concluir(chave, uploadId, 1, 2);
		assertThat(jdbc.queryForObject("select bytes from network_storage_objects where bucket = ?"
			+ " and object_key = ?", Long.class, bucket, chave)).isEqualTo(6L * 1024 * 1024 + 1);
	}

	/**
	 * `ListParts` pagina com `max-parts` e `part-number-marker`, como o S3.
	 *
	 * <p><b>Antes:</b> a rota devolvia <b>todas</b> as partes, ignorava `max-parts` e
	 * `part-number-marker` e não mandava `IsTruncated` nem `NextPartNumberMarker`. O SDK, que pagina
	 * para descobrir o que já chegou num upload interrompido, recebia um documento sem
	 * `IsTruncated` e tratava como `false`: com mais partes do que o pedido, ele parava na primeira
	 * página e concluía que as demais não existiam — e reenviava tudo, ou pior, concluía o upload
	 * pelos números que não viu.</p>
	 *
	 * <p><b>Agora:</b> `max-parts` (padrão e teto 1000) e `part-number-marker` (<b>exclusivo</b>, como
	 * no S3) recortam a lista; `IsTruncated=true` e `NextPartNumberMarker` dizem onde continuar. Uma
	 * página cheia mas última sai `IsTruncated=false` — "página cheia" não é "há mais".</p>
	 */
	@Test
	void listar_partes_pagina_com_marcador_e_diz_se_ha_mais() throws Exception {
		String chave = "pagina/%s.bin".formatted(sufixo());
		String uploadId = abrir(chave);
		for (int n = 1; n <= 3; n++) {
			enviarParte(chave, uploadId, n, bytes((char) ('a' + n), 1024));
		}

		var primeira = listarPartes(chave, uploadId, "&max-parts=2");
		assertThat(primeira).contains("<IsTruncated>true</IsTruncated>");
		assertThat(primeira).contains("<NextPartNumberMarker>2</NextPartNumberMarker>");
		assertThat(primeira).as("a primeira pagina levou a parte 3").doesNotContain("<PartNumber>3</PartNumber>");

		var segunda = listarPartes(chave, uploadId, "&max-parts=2&part-number-marker=2");
		assertThat(segunda).contains("<IsTruncated>false</IsTruncated>");
		assertThat(segunda).contains("<PartNumber>3</PartNumber>");
		assertThat(segunda).as("o marcador e exclusivo: a parte 2 ja foi").doesNotContain("<PartNumber>2</PartNumber>");

		// Página cheia e última: não é truncada, e não manda o cliente pedir uma página vazia.
		var cheia = listarPartes(chave, uploadId, "&max-parts=3");
		assertThat(cheia).contains("<IsTruncated>false</IsTruncated>");
		assertThat(cheia).as("a pagina cheia levou as tres partes").contains("<PartNumber>1</PartNumber>")
			.contains("<PartNumber>2</PartNumber>").contains("<PartNumber>3</PartNumber>");
		assertThat(cheia).as("pagina cheia e ultima nao tem marcador").doesNotContain("<NextPartNumberMarker>");
	}


	/**
	 * ETag de parte que não é o do que chegou: `InvalidPart`, e nada é gravado.
	 *
	 * <p><b>Antes:</b> o `partesDoXml` só lia `<PartNumber>` e <b>descartava o `<ETag>`</b>. O S3
	 * exige que o ETag de cada `<Part>` seja o da parte enviada; um cliente que montou o XML com o ETag
	 * de outra parte (ou de um reenvio que ele não fez) recebia `200` e um objeto montado com bytes que
	 * ele não conferiu. Numa conclusão em que o cliente acha que está estendendo o arquivo, isso
	 * grava o conteúdo errado em silêncio — o pior modo de falha de um upload.</p>
	 *
	 * <p><b>Agora:</b> o par `(número, ETag)` é lido; o ETag é o digest entre aspas e é comparado com
	 * o da parte gravada. Diferente → `400 InvalidPart`, e o upload continua aberto para o cliente
	 * reenviar. Sem ETag no XML, o número basta — é o que os clientes que não conferem mandam.</p>
	 */
	@Test
	void conclusao_com_etag_de_parte_que_nao_bate_e_recusada_sem_gravar() throws Exception {
		String chave = "etag/%s.bin".formatted(sufixo());
		String uploadId = abrir(chave);
		enviarParte(chave, uploadId, 1, bytes('a', 6 * 1024 * 1024));
		enviarParte(chave, uploadId, 2, bytes('b', 1));

		// O ETag declarado é o da parte 2, e a lista nomeia a parte 1: o cliente trocou os valores.
		String xml = "<CompleteMultipartUpload><Part><PartNumber>1</PartNumber><ETag>\""
			+ shaOfBytes(bytes('b', 1)) + "\"</ETag></Part><Part><PartNumber>2</PartNumber><ETag>\""
			+ shaOfBytes(bytes('b', 1)) + "\"</ETag></Part></CompleteMultipartUpload>";
		String sha = shaOfBytes(xml.getBytes(StandardCharsets.UTF_8));
		var recusa = mockMvc.perform(assinar(post("/s3/" + bucket + "/" + chave).param("uploadId", uploadId)
			.contentType("application/xml").content(xml), sha)).andReturn();
		assertThat(recusa.getResponse().getStatus()).as(recusa.getResponse().getContentAsString())
			.isEqualTo(400);
		assertThat(recusa.getResponse().getContentAsString()).contains("InvalidPart");
		assertThat(jdbc.queryForObject("select count(*) from network_storage_objects where bucket = ?"
			+ " and object_key = ?", Integer.class, bucket, chave)).as("nada foi gravado").isZero();

		// Com os ETags certos — o que o SDK manda — a conclusão passa.
		String certo = "<CompleteMultipartUpload><Part><PartNumber>1</PartNumber><ETag>\""
			+ shaOfBytes(bytes('a', 6 * 1024 * 1024)) + "\"</ETag></Part><Part><PartNumber>2</PartNumber><ETag>\""
			+ shaOfBytes(bytes('b', 1)) + "\"</ETag></Part></CompleteMultipartUpload>";
		var ok = mockMvc.perform(assinar(post("/s3/" + bucket + "/" + chave).param("uploadId", uploadId)
			.contentType("application/xml").content(certo), shaOfBytes(certo.getBytes(StandardCharsets.UTF_8))))
			.andReturn();
		assertThat(ok.getResponse().getStatus()).as(ok.getResponse().getContentAsString()).isEqualTo(200);
		assertThat(jdbc.queryForObject("select bytes from network_storage_objects where bucket = ?"
			+ " and object_key = ?", Long.class, bucket, chave)).isEqualTo(6L * 1024 * 1024 + 1);
	}
	private String listarPartes(String chave, String uploadId, String query) throws Exception {
		var resposta = mockMvc.perform(assinar(get("/s3/" + bucket + "/" + chave + "?uploadId=" + uploadId + query),
			shaVazio())).andReturn();
		assertThat(resposta.getResponse().getStatus()).as(resposta.getResponse().getContentAsString()).isEqualTo(200);
		return resposta.getResponse().getContentAsString();
	}

	/**
	 * UploadPartCopy: a parte é o <b>intervalo</b> pedido da origem, sem o cliente enviar os bytes.
	 *
	 * <p><b>Antes:</b> a parte com {@code x-amz-copy-source} respondia {@code 501} — o cliente que
	 * copia um arquivo grande em partes (o caminho do {@code rclone} e do {@code aws s3 cp} acima de
	 * 8 MB) não tinha como. O destino só existia depois da conclusão, e nenhuma parte era gravada.</p>
	 *
	 * <p><b>Agora:</b> o intervalo {@code bytes=primeiro-ultimo} é lido da origem pelo mesmo
	 * {@code readBody} do download e entra pelo mesmo {@code receivePart} do envio direto. A resposta é
	 * o {@code CopyPartResult} do S3, com o ETag do que foi gravado. O discriminante é o par:
	 * {@code 200} com o XML <b>e</b> o objeto concluído com exatamente o intervalo — copiar a origem
	 * inteira passaria pelo status.</p>
	 */
	@Test
	void parte_de_copia_grava_o_intervalo_pedido_da_origem() throws Exception {
		gravar("copias/origem.txt", "0123456789");
		String chave = "copias/recorte-%s.txt".formatted(sufixo());
		String uploadId = abrir(chave);

		var resposta = copiarParte(chave, uploadId, 1, "copias/origem.txt", "bytes=2-5");

		assertThat(resposta.getStatus()).as(resposta.getContentAsString()).isEqualTo(200);
		assertThat(resposta.getContentAsString())
			.contains("<CopyPartResult")
			.contains("<ETag>\"" + shaDe("2345") + "\"</ETag>")
			.contains("<LastModified>");

		concluir(chave, uploadId, 1);
		assertThat(ler(chave)).isEqualTo("2345");
	}

	/** Sem {@code x-amz-copy-source-range}, a parte é o objeto inteiro — o caso do arquivo pequeno. */
	@Test
	void parte_de_copia_sem_intervalo_copia_o_objeto_inteiro() throws Exception {
		gravar("copias/inteira.txt", "conteudo inteiro da origem");
		String chave = "copias/inteira-copia-%s.txt".formatted(sufixo());
		String uploadId = abrir(chave);

		var resposta = copiarParte(chave, uploadId, 1, "copias/inteira.txt", null);
		assertThat(resposta.getStatus()).as(resposta.getContentAsString()).isEqualTo(200);

		concluir(chave, uploadId, 1);
		assertThat(ler(chave)).isEqualTo("conteudo inteiro da origem");
	}

	/** Origem que não existe: {@code 404 NoSuchKey} e nenhuma parte gravada. */
	@Test
	void parte_de_copia_de_origem_inexistente_responde_404_sem_gravar_parte() throws Exception {
		String chave = "copias/orfa-%s.txt".formatted(sufixo());
		String uploadId = abrir(chave);

		var resposta = copiarParte(chave, uploadId, 1, "copias/nao-existe.txt", null);

		assertThat(resposta.getStatus()).isEqualTo(404);
		assertThat(resposta.getContentAsString()).contains("<Code>NoSuchKey</Code>");
		assertThat(jdbc.queryForObject("select count(*) from network_storage_multipart_parts where upload_id = ?",
			Integer.class, uploadId)).isZero();
	}

	/** Intervalo fora do objeto (ou invertido) é {@code 400} e não grava parte. */
	@Test
	void parte_de_copia_com_intervalo_invalido_responde_400_sem_gravar_parte() throws Exception {
		gravar("copias/dez.txt", "0123456789");
		String chave = "copias/intervalo-%s.txt".formatted(sufixo());
		String uploadId = abrir(chave);

		var alemDoFim = copiarParte(chave, uploadId, 1, "copias/dez.txt", "bytes=5-99");
		assertThat(alemDoFim.getStatus()).isEqualTo(400);
		assertThat(alemDoFim.getContentAsString()).contains("<Code>InvalidArgument</Code>");

		var invertido = copiarParte(chave, uploadId, 2, "copias/dez.txt", "bytes=9-2");
		assertThat(invertido.getStatus()).isEqualTo(400);
		assertThat(invertido.getContentAsString()).contains("<Code>InvalidArgument</Code>");

		assertThat(jdbc.queryForObject("select count(*) from network_storage_multipart_parts where upload_id = ?",
			Integer.class, uploadId)).isZero();
	}

	// ------------------------------------------------------------------ apoio	// ------------------------------------------------------------------ apoio

	/** Um CopyObject de parte: {@code PUT ?partNumber=&uploadId=} com a origem no cabeçalho. */
	private org.springframework.mock.web.MockHttpServletResponse copiarParte(String chave, String uploadId,
			int numero, String origem, String faixa) throws Exception {
		MockHttpServletRequestBuilder requisicao = put("/s3/" + bucket + "/" + chave)
			.param("partNumber", String.valueOf(numero)).param("uploadId", uploadId)
			.header("x-amz-copy-source", "/" + bucket + "/" + origem)
			.content(new byte[0]);
		if (faixa != null) {
			requisicao.header("x-amz-copy-source-range", faixa);
		}
		return mockMvc.perform(assinar(requisicao, shaVazio())).andReturn().getResponse();
	}

	/** Grava um objeto pelo PUT simples, para servir de origem da cópia. */
	private void gravar(String chave, String conteudo) throws Exception {
		byte[] bytes = conteudo.getBytes(StandardCharsets.UTF_8);
		MockHttpServletRequestBuilder requisicao = put("/s3/" + bucket + "/" + chave).content(bytes)
			.contentType("text/plain");
		var resposta = mockMvc.perform(assinar(requisicao, shaOfBytes(bytes))).andReturn();
		assertThat(resposta.getResponse().getStatus()).as(resposta.getResponse().getContentAsString())
			.isEqualTo(200);
	}

	/** O corpo do objeto concluído, com o {@code asyncDispatch} do {@code StreamingResponseBody}. */
	private String ler(String chave) throws Exception {
		var resultado = mockMvc.perform(assinar(get("/s3/" + bucket + "/" + chave), shaVazio())).andReturn();
		if (resultado.getRequest().isAsyncStarted()) {
			resultado = mockMvc.perform(asyncDispatch(resultado)).andReturn();
		}
		assertThat(resultado.getResponse().getStatus())
			.as("GET " + chave + ": " + resultado.getResponse().getContentAsString()).isEqualTo(200);
		return resultado.getResponse().getContentAsString();
	}

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
		assertThat(resposta.getResponse().getStatus()).as(resposta.getResponse().getContentAsString())
			.isEqualTo(200);
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