package com.socialcoisa.rede.network.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.socialcoisa.rede.network.application.port.out.NetworkTaskRepository;
import com.socialcoisa.rede.network.domain.NetworkTask;
import com.socialcoisa.rede.network.domain.TaskStatus;
import com.socialcoisa.rede.network.domain.TaskType;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Fase 5: o produtor do cache de borda e o alvo de designação.
 *
 * <p>Duas coisas quebradas, e o teste fixa cada uma:</p>
 * <ul>
 * <li><b>Ninguém enfileirava {@code EDGE_CACHE}.</b> O executor no nó existia e ficava ocioso. Aqui
 * o pedido nasce e a tarefa chega ao nó de borda pelo mesmo caminho das demais (dispatch por
 * mérito, {@code GET /network/tasks/next}).</li>
 * <li><b>A tarefa de borda não pode ir para qualquer nó.</b> O cursor do cache é do disco do nó, então
 * designá-la ao nó sorteado aqueceria um e deixaria os outros frios. O filtro de alvo é provado
 * pelo caso em que <b>só</b> o nó alvo é elegível e pelo caso em que o alvo está fora: a tarefa
 * permanece na fila em vez de ir para o nó errado.</li>
 * </ul>
 *
 * <p>O teste fica neste pacote porque {@link EdgeCacheWorker} é package-private.</p>
 */
@SpringBootTest
class EdgeCacheProducerTests {

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private EdgeCacheService edge;

	@Autowired
	private EdgeCacheWorker worker;

	@Autowired
	private NetworkTaskService tasks;

	@Autowired
	private NetworkTaskRepository taskRepository;

	@BeforeEach
	void setUp() {
		jdbc.update("delete from network_tasks");
		jdbc.update("delete from network_state");
		jdbc.update("delete from network_node_keys");
		jdbc.update("delete from network_provision_tokens");
		jdbc.update("delete from network_nodes");
	}

	/**
	 * O pedido explícito nasce com alvo e chega ao nó de borda. Sem alvo, este teste falharia por
	 * sorteio: com um nó só ele passaria — por isso o caso do alvo fora dos candidatos existe.
	 */
	@Test
	void pedidoExplicitoChegaAoNoDeBorda() {
		insertActiveNode("nd_borda", "NODE_EDGE");

		NetworkTask task = edge.request("nd_borda", "admin-1");

		assertThat(task.taskType()).isEqualTo(TaskType.EDGE_CACHE);
		assertThat(task.targetNodeId()).isEqualTo("nd_borda");
		assertThat(task.status()).isEqualTo(TaskStatus.PENDING);

		assertThat(tasks.dispatchPending()).isEqualTo(1);
		assertThat(jdbc.queryForObject("select assigned_node_id from network_tasks where id = ?", String.class,
			task.id())).isEqualTo("nd_borda");
	}

	/**
	 * O alvo é filtro, não preferência: com dois nós de borda e um deles alvo, a tarefa vai para o
	 * alvo mesmo que o outro tenha mais folga. Antes do filtro, a tarefa de borda podia cair em
	 * qualquer um — e o cursor do nó escolhido era o único que andava.
	 */
	@Test
	void alvoGanhaDoMerito() {
		insertActiveNode("nd_alvo", "NODE_EDGE");
		insertActiveNode("nd_folgado", "NODE_EDGE");
		// O nó alvo é o menos atrativo possível sem virar inelegível: folga 0.5 (2 núcleos, 1 rodando)
		// contra 1.0 (8 núcleos, nenhum rodando). A folga é livres/núcleos e é o critério que precede
		// o jitter, então sem o filtro de alvo a escolha é determinística em favor do outro — e o
		// alvo ainda fica elegível, o que é o ponto: o filtro vence antes de qualquer mérito.
		Instant now = Instant.now();
		jdbc.update("update network_nodes set declared_cpu_cores = 2, observed_running_tasks = 1, "
			+ "observed_cpu_percent = 80, last_seen_at = ? where id = 'nd_alvo'", Timestamp.from(now));
		jdbc.update("update network_nodes set declared_cpu_cores = 8, observed_running_tasks = 0, "
			+ "observed_cpu_percent = 5, last_seen_at = ? where id = 'nd_folgado'", Timestamp.from(now));

		NetworkTask task = edge.request("nd_alvo", "admin-1");
		assertThat(tasks.dispatchPending()).isEqualTo(1);

		assertThat(jdbc.queryForObject("select assigned_node_id from network_tasks where id = ?", String.class,
			task.id())).isEqualTo("nd_alvo");
	}

	/**
	 * Alvo que não pode operar deixa a tarefa na fila. Executar no nó errado não é executar pior: o
	 * nó errado não tem o cursor daquele trabalho, e concluiria a tarefa sem ter aquecido nada.
	 */
	@Test
	void alvoForaDosCandidatosNaoDesviaParaOutroNo() {
		insertActiveNode("nd_alvo", "NODE_EDGE");
		insertActiveNode("nd_outro", "NODE_EDGE");
		NetworkTask task = edge.request("nd_alvo", "admin-1");

		jdbc.update("update network_nodes set status = 'SUSPENDED' where id = 'nd_alvo'");

		assertThat(tasks.dispatchPending()).isZero();
		assertThat(jdbc.queryForObject("select status from network_tasks where id = ?", String.class, task.id()))
			.isEqualTo("PENDING");
		assertThat(jdbc.queryForObject("select assigned_node_id from network_tasks where id = ?", String.class,
			task.id())).isNull();
	}

	/**
	 * A chave é por nó: dois nós de borda pedidos no mesmo instante recebem duas tarefas, cada uma
	 * com o seu alvo. Uma chave global devolveria a tarefa do primeiro ao segundo — que então
	 * aqueceria o cursor do outro.
	 */
	@Test
	void chaveDeIdempotenciaEDistintaPorNo() {
		insertActiveNode("nd_a", "NODE_EDGE");
		insertActiveNode("nd_b", "NODE_EDGE");

		NetworkTask a = edge.request("nd_a", "admin-1");
		NetworkTask b = edge.request("nd_b", "admin-1");

		assertThat(a.id()).isNotEqualTo(b.id());
		assertThat(a.targetNodeId()).isEqualTo("nd_a");
		assertThat(b.targetNodeId()).isEqualTo("nd_b");
	}

	/** Pedir de novo dentro da janela devolve a mesma tarefa: a chave segura o duplo clique. */
	@Test
	void mesmoPedidoNaMesmaJanelaNaoDuplica() {
		insertActiveNode("nd_a", "NODE_EDGE");

		NetworkTask first = edge.request("nd_a", "admin-1");
		NetworkTask second = edge.request("nd_a", "admin-1");

		assertThat(second.id()).isEqualTo(first.id());
		assertThat(jdbc.queryForObject("select count(*) from network_tasks where target_node_id = 'nd_a'",
			Long.class)).isEqualTo(1L);
	}

	/**
	 * A aquisição periódica dá trabalho ao nó que não tem nenhum — e não empilha outro enquanto o
	 * anterior está em aberto. Sem a segunda metade, cada ciclo criaria uma tarefa que executaria
	 * as mesmas páginas de novo, sem avançar o cursor.
	 */
	@Test
	void varreduraEnfileiraUmPedidoPorNoDeBorda() {
		insertActiveNode("nd_borda", "NODE_EDGE");
		insertActiveNode("nd_midia", "NODE_MEDIA");

		assertThat(worker.runOnce()).isEqualTo(1);
		assertThat(taskRepository.countOpenTargeted("nd_borda", Instant.now())).isEqualTo(1);
		// Nó de mídia não tem cache de borda: a varredura não cria trabalho para ele.
		assertThat(taskRepository.countOpenTargeted("nd_midia", Instant.now())).isZero();

		// Segunda rodada com o pedido ainda aberto: nada novo.
		assertThat(worker.runOnce()).isZero();
		assertThat(taskRepository.countOpenTargeted("nd_borda", Instant.now())).isEqualTo(1);
	}

	/**
	 * Nó sem o perfil de borda é recusado no pedido explícito, com o motivo. Um alvo que nunca vai
	 * executar a tarefa deixaria a linha na fila até o prazo — melhor recusar na hora e dizer por quê.
	 */
	@Test
	void pedidoParaNoSemPerfilDeBordaERecusado() {
		insertActiveNode("nd_midia", "NODE_MEDIA");

		assertThatThrownBy(() -> edge.request("nd_midia", "admin-1")).isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("code", "NODE_PROFILE_REQUIRED");
	}

	private void insertActiveNode(String id, String profiles) {
		Instant now = Instant.now();
		jdbc.update("insert into network_nodes (id, display_name, owner_user_id, status, profiles, "
			+ "declared_cpu_cores, declared_memory_mb, declared_disk_mb, declared_bandwidth_mbps, "
			+ "created_at, last_seen_at) values (?, ?, ?, 'ACTIVE', ?, 4, 8192, 51200, 100, ?, ?)", id, id,
			"owner-" + id, profiles, Timestamp.from(now), Timestamp.from(now));
	}
}