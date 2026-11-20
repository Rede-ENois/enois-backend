package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestDatabase;

import tools.jackson.databind.ObjectMapper;

/**
 * A coleta do upload multipart abandonado (doc 18 §4.2, brief "garbage collection").
 *
 * <p>O defeito: o doc dizia que {@code sweep()} descarta o vencido, e o método não existia. O prazo
 * só era conferido na próxima parte — que um cliente morto nunca manda. A linha, as partes e os bytes
 * ficavam para sempre.</p>
 *
 * <p>O que estas asserções seguram: vence o prazo <b>gravado no início</b> (um upload com 1 h de prazo
 * some antes de um com 24 h); o seco não apaga; a soltura apaga a linha; e o diretório de um upload
 * que já não tem linha é recolhido — a sobra de uma passada interrompida.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminStorageMultipartSweepTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	/** O diretório de partes desta instalação, como o serviço o monta (não um caminho adivinhado). */
	@Autowired
	private com.socialcoisa.rede.network.application.StorageMultipartService multipart;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	private void upload(String id, Instant criado, Instant expira) {
		jdbc.update("""
			insert into network_storage_multipart_uploads (upload_id, bucket, `key`, content_type, owner_user_id,
				credential_id, created_at, expires_at) values (?, 'fotos', ?, 'image/jpeg', 'user_1', 's3c_1', ?, ?)
			""", id, id + ".jpg", Timestamp.from(criado), Timestamp.from(expira));
		jdbc.update("""
			insert into network_storage_multipart_parts (upload_id, part_number, bytes, sha256, received_at)
			values (?, 1, 10, 'sha1', ?), (?, 2, 20, 'sha2', ?)
			""", id, Timestamp.from(criado), id, Timestamp.from(criado));
	}

	private int linhas(String id) {
		return jdbc.queryForObject("select count(*) from network_storage_multipart_uploads where upload_id = ?",
			Integer.class, id);
	}

	private int partes(String id) {
		return jdbc.queryForObject("select count(*) from network_storage_multipart_parts where upload_id = ?",
			Integer.class, id);
	}

	@Test
	void o_seco_mostra_o_vencido_e_nao_apaga() throws Exception {
		jdbc.update("delete from network_storage_multipart_parts");
		jdbc.update("delete from network_storage_multipart_uploads");
		var agora = Instant.now();
		upload("mpu_vencido_dry", agora.minusSeconds(3 * 3600), agora.minusSeconds(7200));
		var sessao = auth.authenticate("mpu_sweep_admin_dry");
		TestDatabase.setAdmin(sessao.userId());

		var resposta = mockMvc.perform(post("/admin/network/storage/multipart/sweep")
			.header("X-Session-Token", sessao.token())).andReturn();

		assertThat(resposta.getResponse().getStatus()).as(resposta.getResponse().getContentAsString()).isEqualTo(200);
		var corpo = objectMapper.readValue(resposta.getResponse().getContentAsString(), Map.class);
		assertThat(corpo.get("dryRun")).isEqualTo(true);
		assertThat(corpo.get("pendingCount")).isEqualTo(1);
		assertThat(corpo.get("expiredCount")).isEqualTo(1);
		assertThat(corpo.get("expiredParts")).isEqualTo(2);
		var vencidas = (List<Map<String, Object>>) corpo.get("expired");
		assertThat(vencidas).singleElement().satisfies(p -> {
			assertThat(p.get("uploadId")).isEqualTo("mpu_vencido_dry");
			assertThat(p.get("key")).isEqualTo("mpu_vencido_dry.jpg");
			assertThat(p.get("expired")).isEqualTo(true);
		});
		assertThat(linhas("mpu_vencido_dry")).isEqualTo(1);
	}

	@Test
	void o_real_apaga_so_o_vencido_e_deixa_o_aberto() throws Exception {
		jdbc.update("delete from network_storage_multipart_parts");
		jdbc.update("delete from network_storage_multipart_uploads");
		var agora = Instant.now();
		upload("mpu_vencido", agora.minusSeconds(7200), agora.minusSeconds(60));
		upload("mpu_no_prazo", agora.minusSeconds(60), agora.plusSeconds(3600));
		// O prazo é o gravado no início: este venceu em 30 min, não em 24 h.
		upload("mpu_prazo_curto", agora.minusSeconds(2 * 3600), agora.minusSeconds(5400));
		Path raiz = multipart.root();
		for (String id : List.of("mpu_vencido", "mpu_no_prazo", "mpu_prazo_curto")) {
			Files.createDirectories(raiz.resolve(id));
			Files.createDirectories(raiz.resolve(id).resolve("0"));
			Files.writeString(raiz.resolve(id).resolve("0").resolve("part-1"), "bytes");
		}
		var sessao = auth.authenticate("mpu_sweep_admin_real");
		TestDatabase.setAdmin(sessao.userId());

		var resposta = mockMvc.perform(post("/admin/network/storage/multipart/sweep?dryRun=false")
			.header("X-Session-Token", sessao.token())).andReturn();

		assertThat(resposta.getResponse().getStatus()).as(resposta.getResponse().getContentAsString()).isEqualTo(200);
		var corpo = objectMapper.readValue(resposta.getResponse().getContentAsString(), Map.class);
		assertThat((List<Object>) corpo.get("removed")).as("os dois vencidos, nenhum a mais").hasSize(2);
		assertThat(linhas("mpu_vencido")).isZero();
		assertThat(partes("mpu_vencido")).as("as partes saem junto com a linha").isZero();
		assertThat(linhas("mpu_prazo_curto")).isZero();
		assertThat(linhas("mpu_no_prazo")).as("o upload no prazo continua aberto").isEqualTo(1);
		assertThat(partes("mpu_no_prazo")).isEqualTo(2);
		// Os bytes moram num subdiretório (`<id>/0/`): só um `walk` que apague a árvore os leva. Sem
		// esta linha, apagar só a pasta de cima deixaria o arquivo para trás.
		assertThat(Files.exists(raiz.resolve("mpu_vencido").resolve("0").resolve("part-1")))
			.as("os bytes do vencido saem do disco, não só a pasta").isFalse();
		assertThat(Files.exists(raiz.resolve("mpu_vencido"))).isFalse();
		assertThat(Files.exists(raiz.resolve("mpu_prazo_curto"))).isFalse();
		assertThat(Files.exists(raiz.resolve("mpu_no_prazo"))).as("o upload vivo não perde as partes")
			.isTrue();
	}

	@Test
	void o_diretorio_sem_linha_e_recolhido() throws Exception {
		jdbc.update("delete from network_storage_multipart_parts");
		jdbc.update("delete from network_storage_multipart_uploads");
		var sessao = auth.authenticate("mpu_sweep_admin_orfao");
		TestDatabase.setAdmin(sessao.userId());
		// Sozinho do abort que apagou a linha e caiu antes do disco — e um nome fora do padrão, que
		// nunca pode ser apagado pelo varredor.
		Path raiz = multipart.root();
		var agora = Instant.now();
		upload("mpu_vivo", agora.minusSeconds(60), agora.plusSeconds(3600));
		Files.createDirectories(raiz.resolve("mpu_vivo"));
		Files.writeString(raiz.resolve("mpu_vivo").resolve("part-1"), "bytes do upload vivo");
		Files.createDirectories(raiz.resolve("mpu_orfaozinho"));
		Files.writeString(raiz.resolve("mpu_orfaozinho").resolve("part-1"), "lixo");
		Files.createDirectories(raiz.resolve("nao.e.upload"));
		Files.writeString(raiz.resolve("nao.e.upload").resolve("importante.txt"), "nao apagar");
		try {
			var resposta = mockMvc.perform(post("/admin/network/storage/multipart/sweep?dryRun=false")
				.header("X-Session-Token", sessao.token())).andReturn();

			assertThat(resposta.getResponse().getStatus()).as(resposta.getResponse().getContentAsString())
				.isEqualTo(200);
			assertThat(Files.exists(raiz.resolve("mpu_orfaozinho")))
				.as("diretório de upload que não existe mais é recolhido").isFalse();
			assertThat(Files.exists(raiz.resolve("mpu_vivo"))).as("diretório de upload vivo não é recolhido")
				.isTrue();
			assertThat(Files.exists(raiz.resolve("nao.e.upload"))).as("nome fora do padrão não é tocado").isTrue();
		}
		finally {
			try (var arquivos = Files.walk(raiz)) {
				arquivos.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
					try {
						Files.deleteIfExists(p);
					}
					catch (java.io.IOException ignored) {
					}
				});
			}
		}
	}

	@Test
	void a_leitura_conta_o_vencido_e_nao_apaga() throws Exception {
		jdbc.update("delete from network_storage_multipart_parts");
		jdbc.update("delete from network_storage_multipart_uploads");
		var agora = Instant.now();
		upload("mpu_lido_vencido", agora.minusSeconds(7200), agora.minusSeconds(60));
		upload("mpu_lido_aberto", agora.minusSeconds(60), agora.plusSeconds(3600));
		var sessao = auth.authenticate("mpu_sweep_admin_leitura");
		TestDatabase.setAdmin(sessao.userId());

		var resposta = mockMvc.perform(get("/admin/network/storage/multipart")
			.header("X-Session-Token", sessao.token())).andReturn();

		assertThat(resposta.getResponse().getStatus()).as(resposta.getResponse().getContentAsString())
			.isEqualTo(200);
		var corpo = objectMapper.readValue(resposta.getResponse().getContentAsString(), Map.class);
		assertThat(corpo.get("pendingCount")).as("os dois abertos, vencido ou não").isEqualTo(2);
		assertThat(corpo.get("expiredCount")).isEqualTo(1);
		assertThat(corpo.get("dryRun")).isEqualTo(true);
		assertThat(linhas("mpu_lido_vencido")).as("leitura não apaga").isEqualTo(1);
		assertThat(partes("mpu_lido_vencido")).isEqualTo(2);
	}

	@Test
	void sem_admin_a_leitura_e_a_coleta_sao_recusadas() throws Exception {
		var sessao = auth.authenticate("mpu_sweep_nao_admin");

		int leitura = mockMvc.perform(get("/admin/network/storage/multipart")
			.header("X-Session-Token", sessao.token())).andReturn().getResponse().getStatus();
		int coleta = mockMvc.perform(post("/admin/network/storage/multipart/sweep?dryRun=false")
			.header("X-Session-Token", sessao.token())).andReturn().getResponse().getStatus();

		assertThat(leitura).as("conta comum não enumera os uploads abertos").isIn(401, 403);
		assertThat(coleta).as("nem coleta").isIn(401, 403);
	}
}