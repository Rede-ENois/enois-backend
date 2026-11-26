package com.socialcoisa.rede.network.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.socialcoisa.rede.network.domain.DataClass;
import com.socialcoisa.rede.network.domain.NetworkTask;
import com.socialcoisa.rede.network.domain.TaskType;

/**
 * O despacho respeita a capacidade que o nó declarou (V390, brief §8.2 "Health checks").
 *
 * <p>A permissão assinada autoriza tipos por perfil; a máquina pode não ter o executor. Com a
 * declaração antiga, o Control Plane designava para um {@code NODE_MEDIA} sem {@code ffmpeg}, o nó
 * tentava, falhava e a tarefa gastava uma das tentativas a cada ciclo — até morrer. O que estes
 * testes fixam é a diferença entre <b>declarou nada</b> (não é candidato) e <b>não declarou</b>
 * (runtime antigo; a permissão continua valendo, o nó não sai do balanceamento).</p>
 */
@SpringBootTest
class NetworkSchedulerExecutorCapabilityTests {

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private NetworkTaskService tasks;

	@BeforeEach
	void setUp() {
		jdbc.update("delete from network_tasks");
		jdbc.update("delete from network_node_keys");
		jdbc.update("delete from network_provision_tokens");
		jdbc.update("delete from network_nodes");
	}

	@Test
	void noQueNaoDeclarouContinuaRecebendoPelaPermissaoDoPerfil() {
		// Runtime anterior à V390: a coluna é nula e o despacho não pode tirar o nó do
		// balanceamento por falta de informação.
		insertNode("nd_sem_declaracao", "NODE_WORKER", null);
		NetworkTask task = job();

		assertThat(tasks.dispatchPending()).isEqualTo(1);

		assertThat(assignedNode(task.id())).isEqualTo("nd_sem_declaracao");
	}

	@Test
	void noQueDeclarouNaoConseguirOExecutorNaoERecebeATarefa() {
		insertNode("nd_sem_executor", "NODE_WORKER", "[]");
		NetworkTask task = job();

		assertThat(tasks.dispatchPending()).as("ninguém capaz: a tarefa fica na fila, não queima tentativa")
			.isZero();
		assertThat(assignedNode(task.id())).isNull();
	}

	@Test
	void noQueDeclarouOExecutorERecebeMesmoAoLadoDeUmQueNaoConsegue() {
		// A ordem de inserção coloca o incapaz primeiro: se o filtro não existisse, ele seria o
		// escolhido (mesma CPU, mesma região).
		insertNode("nd_incapaz", "NODE_WORKER", "[]");
		insertNode("nd_capaz", "NODE_WORKER", "[\"ASYNC_JOB\"]");
		NetworkTask task = job();

		assertThat(tasks.dispatchPending()).isEqualTo(1);

		assertThat(assignedNode(task.id())).isEqualTo("nd_capaz");
	}

	private NetworkTask job() {
		return tasks.enqueue(new NetworkTaskService.EnqueueCommand(TaskType.ASYNC_JOB, "cap-1", "{}",
			DataClass.PUBLIC, null));
	}

	private void insertNode(String id, String profiles, String executorTaskTypes) {
		Instant now = Instant.now();
		jdbc.update("insert into network_nodes (id, display_name, owner_user_id, status, profiles, "
			+ "executor_task_types, declared_cpu_cores, declared_memory_mb, declared_disk_mb, "
			+ "declared_bandwidth_mbps, created_at, last_seen_at) values (?, ?, ?, 'ACTIVE', ?, ?, 4, 8192, "
			+ "51200, 100, ?, ?)", id, "no-" + id, "owner-" + id, profiles, executorTaskTypes, Timestamp.from(now),
			Timestamp.from(now));
	}

	private String assignedNode(String taskId) {
		return jdbc.queryForObject("select assigned_node_id from network_tasks where id = ?", String.class, taskId);
	}
}