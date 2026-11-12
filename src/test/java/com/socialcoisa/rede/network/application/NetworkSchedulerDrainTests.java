package com.socialcoisa.rede.network.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.socialcoisa.rede.network.domain.DataClass;
import com.socialcoisa.rede.network.domain.NetworkTask;
import com.socialcoisa.rede.network.domain.TaskType;

/**
 * Fase 4, drenagem: a varredura periódica é quem a executa.
 *
 * <p>A drenagem existir como método não basta — o ciclo do scheduler precisa chamá-la <b>antes</b>
 * de designar. Sem essa ordem, a tarefa presa a um nó suspenso continua DISPATCHED até o claim
 * vencer, porque {@code findAbandoned} só olha prazo vencido e o nó suspenso não vai responder.</p>
 *
 * <p>O teste fica neste pacote porque {@link NetworkSchedulerWorker} é package-private: expor o
 * {@code runOnce()} só para testar de fora seria abrir a API por conveniência de teste.</p>
 */
@SpringBootTest
class NetworkSchedulerDrainTests {

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private NetworkTaskService tasks;

	@Autowired
	private NetworkSchedulerWorker worker;

	@BeforeEach
	void setUp() {
		jdbc.update("delete from network_tasks");
		jdbc.update("delete from network_state");
		jdbc.update("delete from network_node_keys");
		jdbc.update("delete from network_provision_tokens");
		jdbc.update("delete from network_nodes");
	}

	/**
	 * Uma varredura com o nó suspenso devolve a tarefa à fila — e ela não é redesignada, porque não
	 * sobrou nó elegível. Sem a chamada no ciclo, ela ficaria DISPATCHED pelo claim TTL inteiro.
	 */
	@Test
	void varreduraDrenaAntesDeDesignar() {
		insertActiveNode("nd_varredura", 4);
		NetworkTask task = tasks.enqueue(new NetworkTaskService.EnqueueCommand(TaskType.ASYNC_JOB,
			"varredura-1", "{}", DataClass.PUBLIC, null));
		assertThat(tasks.dispatchPending()).isEqualTo(1);
		assertThat(statusOf(task.id())).isEqualTo("DISPATCHED");

		jdbc.update("update network_nodes set status = 'SUSPENDED' where id = 'nd_varredura'");

		worker.runOnce();

		assertThat(statusOf(task.id())).isEqualTo("PENDING");
		assertThat(jdbc.queryForObject("select assigned_node_id from network_tasks where id = ?", String.class,
			task.id())).isNull();
		// A tentativa só sobe na próxima designação (é o claim que incrementa). A proteção contra o
		// resultado do nó drenado não depende disso: com status PENDING, acceptResult descarta qualquer
		// resultado como tentativa obsoleta antes de olhar o dono.
		assertThat(jdbc.queryForObject("select attempt from network_tasks where id = ?", Integer.class, task.id()))
			.isEqualTo(1);
	}

	private void insertActiveNode(String id, int cpuCores) {
		Instant now = Instant.now();
		jdbc.update("insert into network_nodes (id, display_name, owner_user_id, status, profiles, "
			+ "declared_cpu_cores, declared_memory_mb, declared_disk_mb, declared_bandwidth_mbps, "
			+ "created_at, last_seen_at) values (?, ?, ?, 'ACTIVE', 'NODE_WORKER', ?, 8192, 51200, 100, ?, ?)",
			id, "no-" + id, "owner-" + id, cpuCores, Timestamp.from(now), Timestamp.from(now));
	}

	private String statusOf(String taskId) {
		return jdbc.queryForObject("select status from network_tasks where id = ?", String.class, taskId);
	}
}