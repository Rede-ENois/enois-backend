package com.socialcoisa.rede.network.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.socialcoisa.rede.network.application.port.out.EdgeDeliverVerifier;
import com.socialcoisa.rede.network.application.port.out.NetworkNodeRepository;
import com.socialcoisa.rede.network.application.port.out.NetworkTaskRepository;
import com.socialcoisa.rede.network.application.port.out.StorageResultVerifier;
import com.socialcoisa.rede.network.domain.DataClass;
import com.socialcoisa.rede.network.domain.NetworkTask;
import com.socialcoisa.rede.network.domain.NodeProfile;
import com.socialcoisa.rede.network.domain.NodeStatus;
import com.socialcoisa.rede.network.domain.TaskStatus;
import com.socialcoisa.rede.network.domain.TaskType;
import com.socialcoisa.rede.shared.audit.AuditTrail;
import com.socialcoisa.rede.shared.web.TestDatabaseSupport;

/** Fronteira real do TIMESTAMP H2, sem alterar o relógio dos beans ou consultar filas alheias. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:network_task_clock_boundary;MODE=MySQL;DB_CLOSE_DELAY=0")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class NetworkTaskClockBoundaryTests {

	/** Impede associação no boot; o listener reassocia o helper ao contexto do teste atual. */
	@MockitoBean private TestDatabaseSupport testDatabaseSupport;

	@Autowired private JdbcTemplate jdbc;
	@Autowired private NetworkTaskRepository repository;
	@Autowired private NetworkNodeRepository nodes;
	@Autowired private NetworkModeService modes;
	@Autowired private NetworkRegionService regions;
	@Autowired private AuditTrail audit;
	@Autowired private ApplicationContext context;
	@Autowired private PlatformTransactionManager transactions;

	private final String nodeId = "nd_clock_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
	private final String key = "clock_" + UUID.randomUUID();
	private String taskId;
	private static final Instant MICRO = Instant.parse("2026-11-15T04:52:33.743688Z");

	@BeforeEach
	void prepararNoElegivelEConferirPrecisaoReal() {
		assertThat(jdbc.queryForObject("""
			select datetime_precision from information_schema.columns
			where table_name = 'NETWORK_TASKS' and column_name = 'NEXT_ATTEMPT_AT'
			""", Integer.class)).isEqualTo(6);
		new TransactionTemplate(transactions).executeWithoutResult(status -> {
			nodes.create(nodeId, "no-fronteira-temporal", "owner-clock", List.of(NodeProfile.NODE_WORKER),
				null, null, 4, 8192, 51200, 100, MICRO);
			nodes.updateStatus(nodeId, NodeStatus.ACTIVE, "admin-clock", MICRO);
			nodes.touch(nodeId, "0.1.0", 5.0, 1024, 7168, 0, 51200L, 0L, 0L, 0L, 0L, MICRO);
		});
	}

	@AfterEach
	void limparSomenteOsIdsDoCenario() {
		if (taskId != null) {
			jdbc.update("delete from network_tasks where id = ?", taskId);
			assertThat(repository.findById(taskId)).isEmpty();
		}
		jdbc.update("delete from network_nodes where id = ?", nodeId);
		assertThat(nodes.findById(nodeId)).isEmpty();
	}

	@Test
	void arredondamentoParaFuturoSoPermiteDespachoNaFronteiraPersistida() {
		Instant enfileirado = MICRO.plusNanos(700);
		NetworkTask task = enfileirar(enfileirado);
		Instant persistido = proximaTentativaPersistida();
		assertThat(persistido).isEqualTo(MICRO.plusNanos(1000)).isAfter(enfileirado);
		assertThat(repository.findDispatchable(enfileirado, NetworkTaskService.SCAN_LIMIT)).isEmpty();
		assertThat(executar(enfileirado, NetworkTaskService::dispatchPending)).isZero();
		assertThat(executar(persistido.minusNanos(1), NetworkTaskService::dispatchPending)).isZero();
		assertThat(repository.findById(task.id()).orElseThrow().status()).isEqualTo(TaskStatus.PENDING);
		assertThat(repository.findById(task.id()).orElseThrow().attempt()).isZero();
		assertThat(repository.findDispatchable(persistido, NetworkTaskService.SCAN_LIMIT))
			.extracting(NetworkTask::id).containsExactly(task.id());
		assertThat(executar(persistido, NetworkTaskService::dispatchPending)).isEqualTo(1);
		conferirDesignacao(task);
	}

	@Test
	void microsExatosPermitemDespachoImediato() {
		NetworkTask task = enfileirar(MICRO);
		assertThat(proximaTentativaPersistida()).isEqualTo(MICRO);
		assertThat(repository.findDispatchable(MICRO, NetworkTaskService.SCAN_LIMIT))
			.extracting(NetworkTask::id).containsExactly(task.id());
		assertThat(executar(MICRO, NetworkTaskService::dispatchPending)).isEqualTo(1);
		conferirDesignacao(task);
	}

	@Test
	void arredondamentoParaBaixoPermiteDespachoNoInstanteOriginal() {
		Instant enfileirado = MICRO.plusNanos(300);
		NetworkTask task = enfileirar(enfileirado);
		assertThat(proximaTentativaPersistida()).isEqualTo(MICRO).isBefore(enfileirado);
		assertThat(executar(enfileirado, NetworkTaskService::dispatchPending)).isEqualTo(1);
		conferirDesignacao(task);
	}

	private NetworkTask enfileirar(Instant agora) {
		NetworkTask task = executar(agora, service -> service.enqueue(new NetworkTaskService.EnqueueCommand(
			TaskType.ASYNC_JOB, key, "{}", DataClass.PUBLIC, null)));
		taskId = task.id();
		return task;
	}

	private Instant proximaTentativaPersistida() {
		// Leitura JDBC fora da transação de enqueue, não a entidade ainda em cache do JPA.
		return jdbc.queryForObject("select next_attempt_at from network_tasks where id = ?",
			Timestamp.class, taskId).toInstant();
	}

	private void conferirDesignacao(NetworkTask task) {
		NetworkTask persisted = repository.findById(task.id()).orElseThrow();
		assertThat(persisted.status()).isEqualTo(TaskStatus.DISPATCHED);
		assertThat(persisted.attempt()).isEqualTo(1);
		assertThat(persisted.assignedNodeId()).isEqualTo(nodeId);
	}

	private <T> T executar(Instant agora, Function<NetworkTaskService, T> acao) {
		// O relógio pertence só a esta instância real do serviço. A transação preserva a fronteira JPA.
		NetworkTaskService service = new NetworkTaskService(repository, nodes,
			context.getBeanProvider(MediaResultVerifier.class), context.getBeanProvider(EdgeDeliverVerifier.class),
			context.getBeanProvider(StorageResultVerifier.class),
			modes, regions, audit, Clock.fixed(agora, ZoneOffset.UTC), 120, 180, 5, 900, 600);
		return new TransactionTemplate(transactions).execute(status -> acao.apply(service));
	}
}
