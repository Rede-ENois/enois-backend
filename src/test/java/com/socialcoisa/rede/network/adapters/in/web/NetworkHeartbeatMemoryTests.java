package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import tools.jackson.databind.ObjectMapper;

/**
 * O heartbeat diz o que o nó <b>mediu</b>, e o Control Plane tem de ler com o mesmo significado.
 *
 * <p><b>Antes:</b> o runtime mede {@code MemAvailable} (memória <em>disponível</em>) e manda em
 * {@code memoryMb}; o Control Plane guarda em {@code observed_memory_mb} e {@link
 * com.socialcoisa.rede.network.domain.NetworkNode#memoryExhausted()} compara com o
 * {@code declaredMemoryMb} (o teto <em>cedido</em>). Duas medidas opostas na mesma coluna: um nó com
 * 8 GB declarados e 7,9 GB <b>livres</b> era "teto atingido" — a máquina livre, fora do
 * balanceamento. E um nó com 8 GB declarados e 7,9 GB <b>em uso</b> (100 MB livres) aparecia com
 * memória ociosa e o painel mostrava "uso" de 100 MB.</p>
 *
 * <p><b>Agora:</b> {@code memoryMb} é o uso medido (o mesmo nome dos outros campos observados,
 * que são todos "o que está em uso") e {@code memoryAvailableMb} carrega o disponível. O uso é
 * derivado no Control Plane quando o runtime só manda o disponível (runtime anterior a este
 * contrato), para que um nó em campo continuo sendo lido certo.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class NetworkHeartbeatMemoryTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private com.socialcoisa.rede.network.application.port.out.NetworkNodeRepository nodes;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
		jdbc.update("delete from network_node_keys");
		jdbc.update("delete from network_nodes");
	}

	@Test
	void memoria_em_uso_no_teto_declara_esgotado_e_disponivel_nao() throws Exception {
		Node node = node("mem-uso", 8192);

		heartbeat(node, Map.of("memoryMb", 8000, "memoryAvailableMb", 192));
		assertThat(observedMemory(node)).as("uso medido").isEqualTo(8000);
		assertThat(availableMemory(node)).as("disponível medido").isEqualTo(192);
		// O teto é o CEDIDO, e a régua é "uso chegou ao teto": 8192 de 8192. Antes este nó — um dos
		// mais carregados — era lido como "8000 disponíveis de 8192".
		assertThat(exhausted(node)).as("uso 8000 de 8192 não é teto").isFalse();

		heartbeat(node, Map.of("memoryMb", 8192, "memoryAvailableMb", 0));
		assertThat(exhausted(node)).as("uso 8192 de 8192 é teto").isTrue();

		Node folgado = node("mem-folga", 8192);
		heartbeat(folgado, Map.of("memoryMb", 192, "memoryAvailableMb", 8000));
		assertThat(exhausted(folgado)).as("uso 192 de 8192").isFalse();
	}

	/**
	 * Runtime anterior a este contrato: só manda {@code memoryMb}, e era o DISPONÍVEL. O uso passa a
	 * ser derivado ({@code declarado - disponível}) em vez de ler o disponível como uso.
	 */
	@Test
	void runtime_antigo_com_so_o_disponivel_nao_e_lido_como_uso() throws Exception {
		Node node = node("mem-antigo", 8192);

		heartbeat(node, Map.of("memoryMb", 7900));

		assertThat(observedMemory(node)).as("uso derivado de 8192 - 7900").isEqualTo(292);
		assertThat(availableMemory(node)).as("disponível").isEqualTo(7900);
		assertThat(exhausted(node)).as("292 de 8192 não é teto").isFalse();
	}

	/**
	 * A série histórica do gráfico sofre do mesmo significado invertido: o runtime antigo manda o
	 * disponível em {@code memoryMb}, e a amostra recebia o campo cru. Um nó ocioso de 8 GB aparecia
	 * no gráfico com 7,9 GB em uso — exatamente o sintoma que a V381 corrige no nó, sobrevivendo na
	 * série, que é o que o painel desenha.
	 */
	@Test
	void a_serie_historica_registra_o_uso_derivado_e_nao_o_disponivel_cru() throws Exception {
		Node node = node("mem-serie", 8192);

		heartbeat(node, Map.of("memoryMb", 7900));

		// Mesmo minuto, mesmo ponto: o heartbeat troca o valor em vez de abrir um segundo.
		Long amostra = jdbc.queryForObject(
			"select memory_mb from network_node_samples where node_id = ? order by sampled_at desc limit 1",
			Long.class, node.id());
		assertThat(amostra).as("série do gráfico com o uso, não o disponível").isEqualTo(292);
	}

	@Test
	void runtime_antigo_com_disponivel_zero_nao_deriva_tudo_em_uso() throws Exception {
		// `/proc/meminfo` ilegível no runtime antigo mandava 0. Derivar diria "8192 de 8192 em uso" —
		// a leitura que falhou viraria um nó cheio e fora do balanceamento.
		Node node = node("mem-zero", 8192);
		heartbeat(node, Map.of("memoryMb", 0));

		assertThat(observedMemory(node)).isNull();
		assertThat(exhausted(node)).isFalse();
	}

	@Test
	void sem_memoria_reportada_nao_afirma_nem_uso_nem_esgotamento() throws Exception {
		Node node = node("mem-sem", 8192);
		heartbeat(node, Map.of("cpuPercent", 1.0));

		assertThat(observedMemory(node)).as("não reportado").isNull();
		assertThat(exhausted(node)).as("não medir não é estar cheio").isFalse();
	}

	@Test
	void memoria_negativa_derivada_de_medida_impossivel_nao_vira_uso() throws Exception {
		// `MemAvailable` acima do declarado (máquina com mais RAM do que o nó cede) não é uso zero:
		// é medida que não comparável, e afirmar 0 diria "nada em uso" sobre o que não se sabe.
		Node node = node("mem-estranho", 1024);
		heartbeat(node, Map.of("memoryMb", 4096));

		assertThat(observedMemory(node)).isNull();
		assertThat(availableMemory(node)).isEqualTo(4096);
	}

	private void heartbeat(Node node, Map<String, Object> observed) throws Exception {
		mockMvc
			.perform(post("/network/nodes/heartbeat").header("X-Node-Id", node.id())
				.header("X-Node-Key", node.key()).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("runtimeVersion", "0.1.0", "observed", observed))))
			.andExpect(status().isOk());
	}

	private Long observedMemory(Node node) {
		return jdbc.queryForObject("select observed_memory_mb from network_nodes where id = ?", Long.class, node.id());
	}

	private Long availableMemory(Node node) {
		return jdbc.queryForObject("select observed_memory_available_mb from network_nodes where id = ?",
			Long.class, node.id());
	}

	/**
	 * O {@code NetworkNode.memoryExhausted()} de verdade, relido do banco pelo mesmo repositório que o
	 * balanceamento usa — repetir a régua em SQL aqui seria testar a cópia, não o domínio.
	 */
	private boolean exhausted(Node node) {
		return nodes.findById(node.id()).orElseThrow().memoryExhausted();
	}

	private Node node(String slug, int memoryMb) {
		String id = "nd_" + slug.replace('-', '_');
		jdbc.update("insert into network_nodes (id, display_name, owner_user_id, status, profiles, "
			+ "declared_cpu_cores, declared_memory_mb, declared_disk_mb, declared_bandwidth_mbps, created_at) "
			+ "values (?, ?, ?, 'ACTIVE', 'NODE_WORKER', 4, ?, 51200, 100, CURRENT_TIMESTAMP)",
			id, slug, "owner-" + id, memoryMb);
		String key = "k-" + id;
		jdbc.update("insert into network_node_keys (id, node_id, key_hash, key_prefix, created_by, created_at) "
			+ "values (?, ?, ?, ?, 'admin', CURRENT_TIMESTAMP)",
			"k_" + id, id, com.socialcoisa.rede.shared.security.Hashes.sha256Hex(key), "k-" + id.substring(0, 6));
		return new Node(id, key);
	}

	private record Node(String id, String key) {
	}
}