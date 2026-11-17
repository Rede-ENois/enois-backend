package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestDatabase;

import tools.jackson.databind.ObjectMapper;

/**
 * O disco livre que o nó <b>mede</b>, ao lado do que ele <b>declarou</b>.
 *
 * <p>Antes desta correção o Control Plane só conhecia o {@code declaredDiskMb} do enroll: o que o
 * participante prometeu ceder. Um nó que declara 2 TB e tem 40 GB livres há semanas continuava
 * sendo tratado como se tivesse 2 TB — e o escalonamento de armazenamento decidia sobre um número
 * que não existe. O heartbeat já levava CPU, memória e tarefas em execução; passou a levar também o
 * disco livre medido, persistido em coluna própria, sem tocar no declarado.</p>
 *
 * <p>O que este teste fixa, e por quê:</p>
 * <ul>
 * <li>a medida do heartbeat <b>chega ao banco</b> e o declarado <b>continua sendo o declarado</b> —
 * assertar só que a requisição devolve 200 passaria mesmo que o campo fosse descartado no caminho;</li>
 * <li>o valor é o <b>último</b> reportado: um heartbeat novo sobrescreve o anterior. Ficar com o
 * primeiro diria "disco cheio" para um nó que já liberou espaço;</li>
 * <li>{@code null} e zero são coisas diferentes. {@code null} é "não reportou" (runtime antigo, ou
 * {@code df} que não respondeu) e zero é "sem espaço livre" — quem lê a capacidade tem de tratá-los
 * de forma distinta, e é por isso que a coluna é anulável. A primeira versão desta correção mandava
 * {@code 0} quando não conseguia medir, o que é uma afirmação falsa; este teste recusa isso.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class NetworkNodeObservedDiskTests {

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
	}

	@Test
	void oDiscoLivreMedidoEhPersistidoAoLadoDoDeclarado() throws Exception {
		Node node = activeNode("net_disk_persist", 51200);

		beat(node, 73400L);

		assertThat(observedDiskFreeMb(node.id())).isEqualTo(73400L);
		// O declarado não muda por consequência da medida: são dois fatos diferentes.
		assertThat(declaredDiskMb(node.id())).isEqualTo(51200L);
	}

	@Test
	void oUltimoDiscoReportadoEhOQueVale() throws Exception {
		Node node = activeNode("net_disk_ultimo", 51200);

		beat(node, 73400L);
		beat(node, 512L);

		assertThat(observedDiskFreeMb(node.id())).isEqualTo(512L);
	}

	@Test
	void noQueNaoReportaDiscoFicaNuloENaoZero() throws Exception {
		Node node = activeNode("net_disk_ausente", 51200);

		// Corpo sem `diskFreeMb`: é o heartbeat de um runtime anterior a esta versão.
		mockMvc
			.perform(post("/network/nodes/heartbeat").header("X-Node-Id", node.id())
				.header("X-Node-Key", node.key()).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("runtimeVersion", "0.1.0", "observed",
					Map.of("cpuPercent", 5.0, "memoryMb", 1024, "runningTasks", 0)))))
			.andExpect(status().isOk());

		// Igual a zero, isso aqui valeria "sem espaço" e tiraria o nó da capacidade de armazenamento.
		// Não é o que se sabe: não se sabe.
		assertThat(observedDiskFreeMb(node.id())).isNull();
	}

	@Test
	void zeroEhUmaMedidaERetornaZero() throws Exception {
		Node node = activeNode("net_disk_zero", 51200);

		beat(node, 0L);

		assertThat(observedDiskFreeMb(node.id())).isEqualTo(0L);
	}

	private void beat(Node node, long diskFreeMb) throws Exception {
		Map<String, Object> observed = new HashMap<>();
		observed.put("cpuPercent", 5.0);
		observed.put("memoryMb", 1024);
		observed.put("runningTasks", 0);
		observed.put("diskFreeMb", diskFreeMb);
		mockMvc
			.perform(post("/network/nodes/heartbeat").header("X-Node-Id", node.id())
				.header("X-Node-Key", node.key()).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("runtimeVersion", "0.1.0", "observed", observed))))
			.andExpect(status().isOk());
	}

	private Long observedDiskFreeMb(String nodeId) {
		return jdbc.queryForObject("select observed_disk_free_mb from network_nodes where id = ?", Long.class, nodeId);
	}

	private int declaredDiskMb(String nodeId) {
		Integer value = jdbc.queryForObject("select declared_disk_mb from network_nodes where id = ?", Integer.class,
				nodeId);
		return value == null ? -1 : value;
	}

	private record Node(String id, String key) {
	}

	private Node activeNode(String name, int declaredDiskMb) throws Exception {
		var session = auth.authenticate(name);
		TestDatabase.setAdmin(session.userId());

		var tokenResult = mockMvc
			.perform(post("/admin/network/nodes/provision-token").header("X-Session-Token", session.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("displayName", name, "ownerUserId", "owner-" + name,
					"profiles", List.of("NODE_WORKER")))))
			.andExpect(status().isOk())
			.andReturn();
		String provisionToken = objectMapper.readTree(tokenResult.getResponse().getContentAsString()).get("token")
			.asString();

		Map<String, Object> declared = new HashMap<>();
		declared.put("cpuCores", 4);
		declared.put("memoryMb", 8192);
		declared.put("diskMb", declaredDiskMb);
		declared.put("bandwidthMbps", 100);
		var enrollResult = mockMvc
			.perform(post("/network/nodes/enroll").contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("provisionToken", provisionToken, "declared", declared))))
			.andExpect(status().isOk())
			.andReturn();
		String nodeId = objectMapper.readTree(enrollResult.getResponse().getContentAsString()).get("nodeId")
			.asString();

		mockMvc.perform(post("/admin/network/nodes/" + nodeId + "/approve").header("X-Session-Token", session.token()))
			.andExpect(status().isOk());

		var keyResult = mockMvc
			.perform(post("/admin/network/nodes/" + nodeId + "/keys").header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andReturn();
		String key = objectMapper.readTree(keyResult.getResponse().getContentAsString()).get("apiKey").asString();
		return new Node(nodeId, key);
	}

}