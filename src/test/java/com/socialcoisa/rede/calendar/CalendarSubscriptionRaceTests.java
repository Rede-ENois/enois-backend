package com.socialcoisa.rede.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.calendar.application.CalendarSubscriptionService;
import com.socialcoisa.rede.calendar.application.port.out.CalendarSubscriptionRepository;
import com.socialcoisa.rede.calendar.domain.CalendarScope;
import com.socialcoisa.rede.calendar.domain.CalendarSubscription;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * Corrida na emissão da assinatura `.ics`: duas emissões do mesmo dono correndo juntas leem o mesmo
 * "não há ativa" e criam dois links vivos — e o revogar depois só mata um, deixando credencial
 * ativa que a pessoa não conhece. A emissão tem de ser serializada pela tranca da pessoa.
 */
@SpringBootTest(properties = "spring.task.scheduling.enabled=false")
@AutoConfigureMockMvc
class CalendarSubscriptionRaceTests {

	@Autowired MockMvc mockMvc;
	@Autowired ObjectMapper objectMapper;
	@Autowired CalendarSubscriptionService subscriptions;

	@MockitoSpyBean
	CalendarSubscriptionRepository subscriptionRepository;

	private TestAuthHelper.AuthSession account(String prefix) throws Exception {
		return new TestAuthHelper(mockMvc, objectMapper).authenticate(prefix + UUID.randomUUID());
	}

	@Test
	void twoIssuersRacingStillLeaveExactlyOneActiveSubscription() throws Exception {
		var user = account("cal_race_");
		String userId = user.userId();

		// Barreira de duas pontas: as DUAS transações fazem a leitura "há ativa?" antes de qualquer
		// uma gravar, que é a janela real da corrida. Sem a tranca, as duas leem "não" e as duas
		// criam. Com a tranca, a segunda só chega na leitura depois que a primeira comitou.
		CyclicBarrier bothRead = new CyclicBarrier(2);
		doAnswer(invocation -> {
			try {
				bothRead.await(5, TimeUnit.SECONDS);
			}
			catch (Exception contended) {
				// Com a tranca segurando a segunda, a primeira termina sozinha e a barreira
				// estoura: é o comportamento correto, não falha do teste.
			}
			return invocation.callRealMethod();
		}).when(subscriptionRepository).findActiveByUser(userId);

		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			CountDownLatch start = new CountDownLatch(1);
			Future<CalendarSubscriptionService.Issued> first = pool.submit(() -> {
				start.await();
				return subscriptions.issue(userId, CalendarScope.SOCIAL);
			});
			Future<CalendarSubscriptionService.Issued> second = pool.submit(() -> {
				start.await();
				return subscriptions.issue(userId, CalendarScope.SOCIAL);
			});
			start.countDown();
			List<CalendarSubscriptionService.Issued> issued = new ArrayList<>();
			for (Future<CalendarSubscriptionService.Issued> future : List.of(first, second)) {
				try {
					issued.add(future.get(60, TimeUnit.SECONDS));
				}
				catch (ExecutionException lostTheLock) {
					// O H2 tranca a TABELA inteira no `for update` e o perdedor estoura o timeout em
					// vez de esperar a vez, como o MariaDB de produção faz. Recusar o perdedor é
					// resposta correta também: a regra é nunca nascerem dois tokens.
					assertThat(lostTheLock.getCause())
						.as("quem perde a disputa pela tranca é recusado, não atende duas vezes")
						.isInstanceOf(CannotAcquireLockException.class);
				}
			}

			// No máximo um token nasceu: quem não foi recusado viu a ativa da primeira e devolveu ela.
			long tokensBorn = issued.stream().filter(i -> i.token() != null).count();
			assertThat(tokensBorn).as("duas emissões na corrida não criam dois tokens").isEqualTo(1);
			if (issued.size() == 2) {
				assertThat(issued.get(0).subscription().id()).isEqualTo(issued.get(1).subscription().id());
			}
		}
		finally {
			pool.shutdownNow();
		}
	}

	@Test
	void revokingAfterTheRaceLeavesNoActiveCredentialBehind() throws Exception {
		var user = account("cal_race_rev_");
		String userId = user.userId();

		AtomicInteger reads = new AtomicInteger();
		doAnswer(invocation -> {
			if (reads.incrementAndGet() == 1) {
				Thread.sleep(300);
			}
			return invocation.callRealMethod();
		}).when(subscriptionRepository).findActiveByUser(userId);

		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			List<Future<?>> races = List.of(
				pool.submit(() -> subscriptions.issue(userId, CalendarScope.SOCIAL)),
				pool.submit(() -> subscriptions.issue(userId, CalendarScope.SOCIAL)));
			for (Future<?> future : races) {
				try {
					future.get(60, TimeUnit.SECONDS);
				}
				catch (ExecutionException lostTheLock) {
					// H2 tranca a tabela e recusa o perdedor por timeout: recusa correta também.
					assertThat(lostTheLock.getCause()).isInstanceOf(CannotAcquireLockException.class);
				}
			}
		}
		finally {
			pool.shutdownNow();
		}

		subscriptions.revoke(userId);

		// Revogar mata a única ativa: não pode sobrar link vivo que a tela não conhece.
		// (No H2 o perdedor pode ter sido recusado por timeout de tranca de tabela — também
		// correto: a regra é nunca haver dois tokens, e revogar não deixa nenhum ativo.)
		assertThat(subscriptions.active(userId)).isEmpty();
	}

}
