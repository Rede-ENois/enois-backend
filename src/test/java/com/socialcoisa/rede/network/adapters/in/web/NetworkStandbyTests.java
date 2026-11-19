package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.network.application.StandbyDumpSource;
import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestDatabase;

import tools.jackson.databind.ObjectMapper;

/**
 * A cópia do banco do Control Plane nos nós de reserva (V360, brief cooperativo §11/§12).
 *
 * <p>O que estes testes fixam, e o defeito que cada um fecha:</p>
 * <ul>
 * <li>promover um nó <b>sem cópia</b> é recomeço disfarçado de failover: a promoção pela rota de
 * reserva recusa com 409 e pede a cópia;</li>
 * <li>cópia que o nó <b>afirma</b> ter mas o digest não é o do dump enviado não vira {@code READY}: um
 * arquivo velho de outra cópia não pode fazer o failover assumir o banco errado;</li>
 * <li>início de uma cópia nova não apaga a prova da anterior — quem cai no meio não "nunca teve
 * cópia";</li>
 * <li>retomada ({@code offset > 0}) não é falha: o nó que perdeu a conexão aos 90% continua de onde
 * parou, e o digest é sempre o do dump INTEIRO;</li>
 * <li>a linha só nasce com o corpo; o simples GET do estado não liga a cópia.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(NetworkStandbyTests.FailingSource.class)
@org.springframework.test.context.TestPropertySource(properties = {
	"rede.network.standby.min-bytes=64",
	"rede.network.standby.refresh-hours=6"
})
class NetworkStandbyTests {

	/** Dump determinístico: o formato do {@code mariadb-dump} é dele, não nosso; o que importa é ser estável. */
	static final byte[] DUMP = ("CREATE TABLE t (id int);\n" + "INSERT INTO t VALUES (1);\n").repeat(4)
		.getBytes(StandardCharsets.UTF_8);

	@org.springframework.stereotype.Component
	static class FailingSource implements StandbyDumpSource {

		volatile boolean fail;

		@Override
		public byte[] dump(long offset) {
			if (fail) {
				throw new IllegalStateException("banco fora");
			}
			return offset <= 0 ? DUMP.clone() : java.util.Arrays.copyOfRange(DUMP, (int) offset, DUMP.length);
		}
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
		jdbc.update("delete from network_standby_snapshots");
		jdbc.update("delete from network_authorities");
		jdbc.update("delete from network_instance_readers");
		jdbc.update("delete from network_state");
		jdbc.update("insert into network_state (id, mode, reason, since, term) values ('global', 'NORMAL', ?, ?, 0)",
			"estado de teste", Timestamp.from(Instant.now()));
		jdbc.update("delete from audit_logs where action like 'NETWORK_STANDBY_%' or action like 'NETWORK_AUTHORITY_%'");
	}

	// ---------------------------------------------------------------- promoção exige a cópia

	/**
	 * O cenário do pedido: promover um nó para alta prioridade sem a cópia faria o novo controlador
	 * começar sem o estado do antigo. A rota de reserva recusa, dispara a cópia e não sobe o termo.
	 */
	@Test
	void promoverSemCopiaRecusaEPedeACopia() throws Exception {
		var admin = adminSession();
		declareQuorum(admin.userId());
		var node = activeNode(admin.token(), "no-de-reserva-" + admin.userId());

		mockMvc.perform(post("/admin/network/authority/promote-standby").header("X-Session-Token", admin.token())
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("instanceId", "cp-2", "nodeId", node.id(),
				"reason", "cp-1 caiu"))))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("NETWORK_STANDBY_COPY_MISSING"));

		// O termo não andou — a autoridade original continua valendo — e a cópia foi pedida.
		assertThat(jdbc.queryForObject("select term from network_state where id = 'global'", Integer.class)).isZero();
		assertThat(jdbc.queryForObject(
			"select state from network_standby_snapshots where node_id = ? and authority_id = 'original'",
			String.class, node.id())).isEqualTo("SYNCING");
	}

	@Test
	void promoverSemDeclararONoDoBancoERecusado() throws Exception {
		var admin = adminSession();
		declareQuorum(admin.userId());

		mockMvc.perform(post("/admin/network/authority/promote-standby").header("X-Session-Token", admin.token())
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("instanceId", "cp-2", "reason", "sem no"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("STANDBY_NODE_REQUIRED"));
	}

	// ---------------------------------------------------------------- a cópia completa

	/** A cópia do início ao fim: admin pede, nó baixa, nó confirma, e o nó passa a poder assumir. */
	@Test
	void copiaConcluidaPeloNoTornaOReservaAptoAAssumir() throws Exception {
		var admin = adminSession();
		declareQuorum(admin.userId());
		var node = activeNode(admin.token(), "no-reserva-completa-" + admin.userId());

		requestCopy(admin.token(), node.id());

		var dump = mockMvc.perform(get("/network/standby/dump").header("X-Node-Id", node.id())
			.header("X-Node-Key", node.key()))
			.andExpect(status().isOk())
			.andReturn();
		byte[] body = dump.getResponse().getContentAsByteArray();
		String digest = dump.getResponse().getHeader("X-Standby-Sha256");
		assertThat(body).isEqualTo(DUMP);
		assertThat(digest).isEqualTo(sha256(DUMP));

		mockMvc.perform(post("/network/standby/complete").header("X-Node-Id", node.id())
			.header("X-Node-Key", node.key()).contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("bytes", DUMP.length, "sha256", digest))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("READY"));

		mockMvc.perform(get("/admin/network/authority/standby").header("X-Session-Token", admin.token())
			.param("nodeId", node.id()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.canTakeOver").value(true))
			.andExpect(jsonPath("$.readyCopies").value(1))
			.andExpect(jsonPath("$.reason").doesNotExist());
	}

	/**
	 * Cópia que o banco NÃO liberou não vira cópia boa: a linha fica FAILED com o motivo, e o nó
	 * continua sem poder assumir. É o caso do dump desligado em desenvolvimento — a cópia tem de
	 * dizer "não deu", não produzir um arquivo de mentira para o failover.
	 */
	@Test
	void bancoIndisponivelNaoViraCopiaBoa() throws Exception {
		var admin = adminSession();
		declareQuorum(admin.userId());
		var node = activeNode(admin.token(), "no-reserva-banco-off-" + admin.userId());
		requestCopy(admin.token(), node.id());

		// A fonte em uso pelo app passa a recusar: é o caso do dump desligado / banco fora. A
		// cópia do início em diante usa esta mesma fonte, então a recusa acontece no primeiro
		// byte lido pelo servidor.
		// O cache do dump é descartado: um dump gerado antes deste teste não pode mascarar a falha.
		servico().forgetCachedDump();
		FailingSource failing = context.getBean(FailingSource.class);
		failing.fail = true;
		try {
			mockMvc.perform(get("/network/standby/dump").header("X-Node-Id", node.id())
				.header("X-Node-Key", node.key()))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("STANDBY_DUMP_UNAVAILABLE"));
		}
		finally {
			failing.fail = false;
		}

		assertThat(jdbc.queryForObject(
			"select state from network_standby_snapshots where node_id = ?", String.class, node.id()))
			.isEqualTo("FAILED");
		assertThat(standby().status(node.id()).canTakeOver()).isFalse();
	}

	/**
	 * O digest é a prova. Sem ela, um nó com arquivo velho de outra cópia (restauração de disco,
	 * backup de backup) confirmaria "cópia pronta" e o failover assumiria o banco errado.
	 */
	@Test
	void digestDiferenteDoEnviadoNaoViraCopiaPronta() throws Exception {
		var admin = adminSession();
		declareQuorum(admin.userId());
		var node = activeNode(admin.token(), "no-reserva-digest-" + admin.userId());

		requestCopy(admin.token(), node.id());
		mockMvc.perform(get("/network/standby/dump").header("X-Node-Id", node.id())
			.header("X-Node-Key", node.key())).andExpect(status().isOk());

		mockMvc.perform(post("/network/standby/complete").header("X-Node-Id", node.id())
			.header("X-Node-Key", node.key()).contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("bytes", DUMP.length, "sha256", "a".repeat(64)))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("FAILED"));

		mockMvc.perform(get("/admin/network/authority/standby").header("X-Session-Token", admin.token())
			.param("nodeId", node.id()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.canTakeOver").value(false))
			.andExpect(jsonPath("$.failures").value(1));
	}

	/** Cópia nova NÃO apaga a prova da anterior: quem cai no meio não "nunca teve cópia". */
	@Test
	void novaCopiaEmAndamentoPreservaAProvaDaAnterior() throws Exception {
		var admin = adminSession();
		declareQuorum(admin.userId());
		var node = activeNode(admin.token(), "no-reserva-preserva-" + admin.userId());

		copyAndComplete(admin.token(), node, DUMP.length);
		var ready = jdbc.queryForMap(
			"select completed_at, bytes, sha256 from network_standby_snapshots where node_id = ?", node.id());
		assertThat(ready.get("bytes")).isEqualTo((long) DUMP.length);

		// Nova tentativa que não termina.
		requestCopy(admin.token(), node.id());

		var during = jdbc.queryForMap(
			"select state, completed_at, bytes, sha256 from network_standby_snapshots where node_id = ?", node.id());
		assertThat(during.get("state")).isEqualTo("SYNCING");
		assertThat(during.get("completed_at")).isEqualTo(ready.get("completed_at"));
		assertThat(during.get("bytes")).isEqualTo(ready.get("bytes"));
		assertThat(during.get("sha256")).isEqualTo(ready.get("sha256"));
	}

	// ---------------------------------------------------------------- retomada

	/**
	 * A conexão caiu aos 90%: o nó pede a partir do byte que faltou. O trecho é a cauda, o digest é o
	 * do dump INTEIRO, e a linha registra o início — retomada não é falha.
	 */
	@Test
	void retomadaEnviaACaudaComODigestDoDumpInteiro() throws Exception {
		var admin = adminSession();
		declareQuorum(admin.userId());
		var node = activeNode(admin.token(), "no-reserva-retoma-" + admin.userId());
		requestCopy(admin.token(), node.id());

		long from = 40;
		var dump = mockMvc.perform(get("/network/standby/dump").header("X-Node-Id", node.id())
			.header("X-Node-Key", node.key()).param("offset", Long.toString(from)))
			.andExpect(status().isOk())
			.andReturn();

		byte[] tail = dump.getResponse().getContentAsByteArray();
		assertThat(tail).isEqualTo(java.util.Arrays.copyOfRange(DUMP, (int) from, DUMP.length));
		assertThat(dump.getResponse().getHeader("X-Standby-Sha256")).isEqualTo(sha256(DUMP));
		assertThat(dump.getResponse().getHeader("X-Standby-Byte-Start")).isEqualTo(Long.toString(from));

		mockMvc.perform(post("/network/standby/complete").header("X-Node-Id", node.id())
			.header("X-Node-Key", node.key()).contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("bytes", DUMP.length,
				"sha256", sha256(DUMP)))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("READY"));
	}

	/**
	 * Dump abaixo do piso não é o banco. Provado no serviço, com uma fonte que devolve 4 bytes: a
	 * rota não pode transformar "quase nada" numa cópia "boa" do nó.
	 */
	@Test
	void dumpAbaixoDoPisoFalhaENaoViraCopiaBoa() throws Exception {
		var admin = adminSession();
		declareQuorum(admin.userId());
		var node = activeNode(admin.token(), "no-reserva-curto-" + admin.userId());
		var shortSource = new com.socialcoisa.rede.network.application.StandbySyncService(
			context.getBean(com.socialcoisa.rede.network.application.port.out.StandbySnapshotRepository.class),
			context.getBean(com.socialcoisa.rede.network.application.port.out.NetworkAuthorityRepository.class),
			context.getBean(com.socialcoisa.rede.network.application.port.out.NetworkNodeRepository.class),
			offset -> "abc".getBytes(StandardCharsets.UTF_8),
			context.getBean(com.socialcoisa.rede.network.application.StandbyFailureRecorder.class),
			context.getBean(com.socialcoisa.rede.shared.audit.AuditTrail.class),
			java.time.Clock.systemUTC(), 6, 64, 600);

		org.assertj.core.api.Assertions.assertThatThrownBy(() -> shortSource.download(admin.userId(), node.id(), 0))
			.isInstanceOf(com.socialcoisa.rede.shared.web.ApiException.class)
			.satisfies(error -> assertThat(((com.socialcoisa.rede.shared.web.ApiException) error).code())
				.isEqualTo("STANDBY_DUMP_UNAVAILABLE"));
		assertThat(jdbc.queryForObject(
			"select state from network_standby_snapshots where node_id = ?", String.class, node.id()))
			.isEqualTo("FAILED");
	}

	/** Um nó não confirma a cópia que o admin pediu para OUTRO nó: o banco do outro não está aqui. */
	@Test
	void noNaoConfirmaCopiaDeOutroNo() throws Exception {
		var admin = adminSession();
		declareQuorum(admin.userId());
		var mine = activeNode(admin.token(), "no-reserva-a-" + admin.userId());
		var other = activeNode(admin.token(), "no-reserva-b-" + admin.userId());
		requestCopy(admin.token(), other.id());

		mockMvc.perform(post("/network/standby/complete").header("X-Node-Id", mine.id())
			.header("X-Node-Key", mine.key()).contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("nodeId", other.id(), "bytes", DUMP.length,
				"sha256", sha256(DUMP)))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("STANDBY_NODE_MISMATCH"));
	}

	/**
	 * #953 item 4: o nó que não consegue gravar/restaurar conta o motivo, e a linha vira {@code FAILED}
	 * em vez de ficar {@code SYNCING} para sempre. E a retomada sai do MESMO dump que a primeira leitura
	 * gerou — entre duas leituras o banco é lido uma vez só.
	 */
	@Test
	void noReportaFalhaERetomadaUsaOMesmoDumpEmCache() throws Exception {
		var admin = adminSession();
		declareQuorum(admin.userId());
		var node = activeNode(admin.token(), "no-reserva-falha-" + admin.userId());
		requestCopy(admin.token(), node.id());
		servico().forgetCachedDump();

		var inteiro = mockMvc.perform(get("/network/standby/dump").header("X-Node-Id", node.id())
			.header("X-Node-Key", node.key()))
			.andExpect(status().isOk())
			.andReturn();
		byte[] corpo = inteiro.getResponse().getContentAsByteArray();
		String digest = inteiro.getResponse().getHeader("X-Standby-Sha256");

		// A retomada sai do MESMO dump: digest igual e trecho igual à cauda dele.
		var cauda = mockMvc.perform(get("/network/standby/dump").header("X-Node-Id", node.id())
			.header("X-Node-Key", node.key()).param("offset", "20"))
			.andExpect(status().isOk())
			.andReturn();
		assertThat(cauda.getResponse().getHeader("X-Standby-Sha256")).isEqualTo(digest);
		assertThat(cauda.getResponse().getContentAsByteArray())
			.isEqualTo(java.util.Arrays.copyOfRange(corpo, 20, corpo.length));

		mockMvc.perform(post("/network/standby/failed").header("X-Node-Id", node.id())
			.header("X-Node-Key", node.key()).contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("reason", "disco cheio no no"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("FAILED"))
			.andExpect(jsonPath("$.reason").value("disco cheio no no"));

		// Chave de outro nó não reporta falha no nome deste.
		var outro = activeNode(admin.token(), "no-reserva-outro-" + admin.userId());
		mockMvc.perform(post("/network/standby/failed").header("X-Node-Id", outro.id())
			.header("X-Node-Key", "chave-errada").contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("reason", "x"))))
			.andExpect(status().is4xxClientError());
	}

	// ---------------------------------------------------------------- promoção com a cópia

	/** Com cópia pronta, a promoção pela rota de reserva sobe o termo e audita o nó do banco. */
	@Test
	void comCopiaProntaPromocaoSobeOTermoEAuditaONo() throws Exception {
		var admin = adminSession();
		declareQuorum(admin.userId());
		var node = activeNode(admin.token(), "no-reserva-pronta-" + admin.userId());
		copyAndComplete(admin.token(), node, DUMP.length);

		mockMvc.perform(post("/admin/network/authority/promote-standby").header("X-Session-Token", admin.token())
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("instanceId", "cp-2", "nodeId", node.id(),
				"reason", "cp-1 perdeu a regiao"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.term").value(1))
			.andExpect(jsonPath("$.authorityId").value("cp-2"))
			.andExpect(jsonPath("$.readyCopies").value(1));

		String metadata = jdbc.queryForObject(
			"select metadata from audit_logs where action = 'NETWORK_AUTHORITY_PROMOTED_WITH_STANDBY' and target_id = ?",
			String.class, node.id());
		assertThat(metadata).contains("\"term\":1").contains("cp-2");
	}

	@Test
	void estadoDeNoSemCopiaExplicaOPorque() throws Exception {
		var admin = adminSession();
		var node = activeNode(admin.token(), "no-reserva-virgem-" + admin.userId());

		mockMvc.perform(get("/admin/network/authority/standby").header("X-Session-Token", admin.token())
			.param("nodeId", node.id()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.canTakeOver").value(false))
			.andExpect(jsonPath("$.readyCopies").value(0))
			.andExpect(jsonPath("$.reason").isNotEmpty());
	}

	@Test
	void membroComumNaoLeNemPedeCopiaDoBanco() throws Exception {
		var member = auth.authenticate("net_membro_standby");
		assertThat(member.token()).isNotBlank();

		mockMvc.perform(get("/admin/network/authority/standby").header("X-Session-Token", member.token())
			.param("nodeId", "nd_qualquer")).andExpect(status().isForbidden());
		mockMvc.perform(post("/admin/network/authority/standby/copy").header("X-Session-Token", member.token())
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("nodeId", "nd_qualquer"))))
			.andExpect(status().isForbidden());
	}

	// ---------------------------------------------------------------- refresh do dia

	/**
	 * O refresh de seis em seis horas só age quando a cópia do dia não existe. Com cópia recente, não
	 * copia de novo (o dump custa I/O do banco que está atendendo).
	 */
	@Test
	void refreshNaoCopiaDeNovoQuandoJaHaCopiaDoDia() throws Exception {
		var admin = adminSession();
		declareQuorum(admin.userId());
		var node = activeNode(admin.token(), "no-reserva-refresh-" + admin.userId());
		copyAndComplete(admin.token(), node, DUMP.length);

		int copied = standby().refreshDaily();

		assertThat(copied).isZero();
		assertThat(jdbc.queryForObject("select count(*) from network_standby_snapshots", Integer.class)).isEqualTo(1);
	}

	/** Sem cópia fresca, o refresh copia de novo para os nós que já têm linha — e só para eles. */
	/**
	 * O refresh copia para quem já tem linha. Prova no nível de dados: a linha passa de READY a
	 * SYNCING pedida pelo "scheduler" — e o ledger (uma linha por nó/autoridade) continua com uma só.
	 * O retorno é zero por desenho: o download só inicia a cópia, e o nó a conclui confirmando o
	 * digest; é o nó que fecha o ciclo.
	 */
	@Test
	void refreshCopiaDeNovoQuandoACopiaEstaVelha() throws Exception {
		var admin = adminSession();
		declareQuorum(admin.userId());
		var node = activeNode(admin.token(), "no-reserva-velha-" + admin.userId());
		copyAndComplete(admin.token(), node, DUMP.length);
		jdbc.update("update network_standby_snapshots set completed_at = ? where node_id = ?",
			Timestamp.from(Instant.now().minus(java.time.Duration.ofHours(7))), node.id());

		assertThat(standby().status(node.id()).copies()).extracting("state").containsExactly("READY");

		standby().refreshDaily();

		// A cópia foi pedida de novo: a linha voltou para SYNCING e a requisição é do agendador.
		assertThat(jdbc.queryForObject(
			"select state from network_standby_snapshots where node_id = ?", String.class, node.id()))
			.isEqualTo("SYNCING");
		assertThat(jdbc.queryForObject(
			"select requested_by from network_standby_snapshots where node_id = ?", String.class, node.id()))
			.isEqualTo("scheduler");
		// E a prova da cópia anterior não foi apagada (é o que o nó tem no disco).
		assertThat(jdbc.queryForObject(
			"select sha256 from network_standby_snapshots where node_id = ?", String.class, node.id()))
			.isEqualTo(sha256(DUMP));
		assertThat(jdbc.queryForObject("select count(*) from network_standby_snapshots", Integer.class)).isEqualTo(1);
	}

	// ---------------------------------------------------------------- auxiliares

	private com.socialcoisa.rede.network.application.StandbySyncService standby() {
		return context.getBean(com.socialcoisa.rede.network.application.StandbySyncService.class);
	}

	@Autowired
	private org.springframework.context.ApplicationContext context;

	/** O serviço real do contexto: descartar o cache do dump não pode ser um estado vazado entre testes. */
	private com.socialcoisa.rede.network.application.StandbySyncService servico() {
		return context.getBean(com.socialcoisa.rede.network.application.StandbySyncService.class);
	}

	@Autowired
	private FailingSource dumpSource;

	private void requestCopy(String adminToken, String nodeId) throws Exception {
		mockMvc.perform(post("/admin/network/authority/standby/copy").header("X-Session-Token", adminToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("nodeId", nodeId))))
			.andExpect(status().isOk());
	}

	private void copyAndComplete(String adminToken, Node node, long bytes) throws Exception {
		requestCopy(adminToken, node.id());
		mockMvc.perform(get("/network/standby/dump").header("X-Node-Id", node.id())
			.header("X-Node-Key", node.key())).andExpect(status().isOk());
		mockMvc.perform(post("/network/standby/complete").header("X-Node-Id", node.id())
			.header("X-Node-Key", node.key()).contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("bytes", bytes, "sha256", sha256(DUMP)))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("READY"));
	}

	private static String sha256(byte[] bytes) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
	}

	private void declareQuorum(String adminId) {
		com.socialcoisa.rede.network.application.NetworkAuthorityService authorities = context
			.getBean(com.socialcoisa.rede.network.application.NetworkAuthorityService.class);
		authorities.declare(adminId, "cp-1");
		authorities.declare(adminId, "cp-2");
		authorities.declare(adminId, "cp-3");
		for (String instance : List.of("cp-1", "cp-2")) {
			Timestamp at = Timestamp.from(Instant.now());
			jdbc.update("""
					insert into network_instance_readers (instance_id, started_at, last_seen_at, last_seq)
					values (?, ?, ?, 0) on duplicate key update last_seen_at = values(last_seen_at)
					""", instance, at, at);
		}
	}

	private record Node(String id, String key) {
	}

	private record Session(String token, String userId) {
	}

	private Session adminSession() throws Exception {
		var session = auth.authenticate("net_admin_standby");
		TestDatabase.setAdmin(session.userId());
		return new Session(session.token(), session.userId());
	}

	private Node activeNode(String admin, String name) throws Exception {
		// Nome curto: o nome do nó é único na rede inteira, e os testes dividem o H2 da suíte. O
		// sufixo aleatório mantém o mesmo teste rodando duas vezes no mesmo banco.
		String nodeName = name.replaceAll("[^a-z0-9-]", "").substring(0, Math.min(20,
			name.replaceAll("[^a-z0-9-]", "").length())) + "-" + Long.toHexString(System.nanoTime());
		var tokenResult = mockMvc
			.perform(post("/admin/network/nodes/provision-token").header("X-Session-Token", admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("displayName", nodeName, "ownerUserId", "owner-" + nodeName,
					"profiles", List.of("NODE_STORAGE")))))
			.andExpect(status().isOk())
			.andReturn();
		String provisionToken = objectMapper.readTree(tokenResult.getResponse().getContentAsString())
			.get("token")
			.asString();

		var enrollResult = mockMvc
			.perform(post("/network/nodes/enroll").contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("provisionToken", provisionToken, "declared",
					Map.of("cpuCores", 2, "memoryMb", 4096, "diskMb", 20480, "bandwidthMbps", 50)))))
			.andExpect(status().isOk())
			.andReturn();
		String nodeId = objectMapper.readTree(enrollResult.getResponse().getContentAsString()).get("nodeId")
			.asString();

		mockMvc.perform(post("/admin/network/nodes/" + nodeId + "/approve").header("X-Session-Token", admin))
			.andExpect(status().isOk());
		var keyResult = mockMvc
			.perform(post("/admin/network/nodes/" + nodeId + "/keys").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andReturn();
		String key = objectMapper.readTree(keyResult.getResponse().getContentAsString()).get("apiKey").asString();
		return new Node(nodeId, key);
	}
}