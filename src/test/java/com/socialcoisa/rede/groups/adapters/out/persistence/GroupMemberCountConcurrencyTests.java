package com.socialcoisa.rede.groups.adapters.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.socialcoisa.rede.groups.application.port.out.GroupRepository;

/**
 * O contador desnormalizado de membros ({@code groups.member_count}) tem de refletir o numero real
 * mesmo com entradas simultaneas.
 *
 * <p>O valor e lido no onboarding ("grupos abertos" sugeridos a quem chega), entao um contador que
 * fica abaixo do real mostra o grupo menor do que e — e nao existe rotina que recalcule. Como o
 * ajuste era ler-mutar-gravar a entidade inteira, duas entradas concorrentes podiam ler o MESMO
 * valor e a segunda sobrescrever o incremento da primeira.</p>
 *
 * <p>Este teste faz N ajustes simultaneos pelo repositorio, como o servico faz, e exige o total
 * exato. Ele falha no codigo anterior e passa com o UPDATE atomico.</p>
 */
@SpringBootTest
class GroupMemberCountConcurrencyTests {

	@Autowired
	private GroupRepository groups;

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void concurrentAdjustmentsDoNotLoseIncrements() throws Exception {
		String groupId = "grp_count_" + UUID.randomUUID().toString().substring(0, 8);
		jdbc.update("""
			merge into groups (id, slug, name, description, topic, join_mode, owner_id, status,
				member_count, created_at, updated_at)
			key(id) values (?, ?, 'Contador', null, null, 'OPEN', 'grp_count_owner', 'ACTIVE', 0, now(), now())
			""", groupId, groupId);

		int entries = 24;
		ExecutorService pool = Executors.newFixedThreadPool(entries);
		try {
			CountDownLatch start = new CountDownLatch(1);
			List<Callable<Void>> runs = new ArrayList<>();
			for (int i = 0; i < entries; i++) {
				runs.add(() -> {
					start.await(10, TimeUnit.SECONDS);
					groups.adjustMemberCount(groupId, 1);
					return null;
				});
			}
			List<Future<Void>> futures = new ArrayList<>();
			for (Callable<Void> run : runs) {
				futures.add(pool.submit(run));
			}
			start.countDown();
			for (Future<Void> future : futures) {
				future.get(60, TimeUnit.SECONDS);
			}
		}
		finally {
			pool.shutdownNow();
		}

		Integer stored = jdbc.queryForObject("select member_count from groups where id = ?", Integer.class, groupId);
		assertThat(stored)
			.as("nenhuma das " + entries + " entradas pode ser perdida no contador")
			.isEqualTo(entries);
	}

	@Test
	void concurrentRemovalsNeverGoBelowZero() throws Exception {
		String groupId = "grp_floor_" + UUID.randomUUID().toString().substring(0, 8);
		jdbc.update("""
			merge into groups (id, slug, name, description, topic, join_mode, owner_id, status,
				member_count, created_at, updated_at)
			key(id) values (?, ?, 'Piso', null, null, 'OPEN', 'grp_floor_owner', 'ACTIVE', 2, now(), now())
			""", groupId, groupId);

		ExecutorService pool = Executors.newFixedThreadPool(8);
		try {
			CountDownLatch start = new CountDownLatch(1);
			List<Future<Void>> futures = new ArrayList<>();
			for (int i = 0; i < 8; i++) {
				futures.add(pool.submit(() -> {
					start.await(10, TimeUnit.SECONDS);
					groups.adjustMemberCount(groupId, -1);
					return null;
				}));
			}
			start.countDown();
			for (Future<Void> future : futures) {
				future.get(60, TimeUnit.SECONDS);
			}
		}
		finally {
			pool.shutdownNow();
		}

		Integer stored = jdbc.queryForObject("select member_count from groups where id = ?", Integer.class, groupId);
		assertThat(stored).as("o piso continua zero, nunca negativo").isZero();
	}
}