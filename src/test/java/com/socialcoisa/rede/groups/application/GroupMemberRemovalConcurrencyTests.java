package com.socialcoisa.rede.groups.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.socialcoisa.rede.groups.application.port.out.GroupRepository;
import com.socialcoisa.rede.groups.domain.Group;
import com.socialcoisa.rede.groups.domain.GroupJoinMode;
import com.socialcoisa.rede.groups.domain.GroupMember;
import com.socialcoisa.rede.groups.domain.GroupRole;
import com.socialcoisa.rede.shared.web.TestDatabaseSupport;

@SpringBootTest(properties = {
	"spring.task.scheduling.enabled=false",
	"spring.datasource.url=jdbc:h2:mem:group_removal_b5;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000",
	"rede.outbox.worker-initial-delay-ms=3600000",
	"rede.outbox.worker-delay-ms=3600000" })
class GroupMemberRemovalConcurrencyTests {

	@Autowired private GroupService servico;
	@Autowired private JdbcTemplate jdbc;
	@MockitoSpyBean private GroupRepository repositorio;
	@MockitoBean private GroupNotifier notificador;
	@MockitoBean private TestDatabaseSupport testDatabaseSupport;

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void saidaERemocaoConcorrentesDescontamUmaVez(boolean remocaoVence) throws Exception {
		String sufixo = UUID.randomUUID().toString().replace("-", "");
		String grupoId = "group_b5_" + sufixo;
		String donoId = "dono_b5_" + sufixo;
		String membroId = "membro_b5_" + sufixo;
		Instant criadoEm = Instant.now().minusSeconds(60);
		repositorio.save(Group.create(grupoId, "grupo-b5-" + sufixo, "Remoção concorrente",
			null, null, null, null, GroupJoinMode.OPEN, donoId, criadoEm));
		repositorio.saveMember(GroupMember.join(grupoId, donoId, GroupRole.OWNER, criadoEm));
		repositorio.saveMember(GroupMember.join(grupoId, membroId, GroupRole.MEMBER, criadoEm));
		repositorio.adjustMemberCount(grupoId, 2);

		CountDownLatch perdedoraLeu = new CountDownLatch(1);
		CountDownLatch vencedoraConcluiu = new CountDownLatch(1);
		AtomicReference<Thread> threadPerdedora = new AtomicReference<>();

		doAnswer(invocacao -> {
			Object resultado = invocacao.callRealMethod();
			if (Thread.currentThread() == threadPerdedora.get()) {
				perdedoraLeu.countDown();
				assertThat(vencedoraConcluiu.await(10, TimeUnit.SECONDS)).isTrue();
			}
			else {
				assertThat(perdedoraLeu.await(10, TimeUnit.SECONDS)).isTrue();
			}
			return resultado;
		}).when(repositorio).findMember(grupoId, membroId);

		Runnable sair = () -> servico.leave(grupoId, membroId);
		Runnable remover = () -> servico.removeMember(grupoId, donoId, membroId);
		var pool = Executors.newFixedThreadPool(2);
		try {
			var segunda = pool.submit(() -> {
				threadPerdedora.set(Thread.currentThread());
				(remocaoVence ? sair : remover).run();
			});
			var primeira = pool.submit(() -> {
				try {
					(remocaoVence ? remover : sair).run();
				}
				finally {
					vencedoraConcluiu.countDown();
				}
			});
			primeira.get(20, TimeUnit.SECONDS);
			segunda.get(20, TimeUnit.SECONDS);

			assertThat(repositorio.countMembers(grupoId)).isEqualTo(1L);
			assertThat(jdbc.queryForObject("select member_count from groups where id = ?",
				Integer.class, grupoId)).isEqualTo(1);
			verify(notificador, times(remocaoVence ? 1 : 0))
				.memberRemoved(any(Group.class), eq(membroId), eq(donoId));
		}
		finally {
			perdedoraLeu.countDown();
			vencedoraConcluiu.countDown();
			pool.shutdownNow();
			assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
		}
	}
}
