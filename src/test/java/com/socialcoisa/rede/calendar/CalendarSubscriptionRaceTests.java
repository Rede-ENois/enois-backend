package com.socialcoisa.rede.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

import java.util.UUID;
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
			CalendarSubscriptionService.Issued issuedFirst = first.get(60, TimeUnit.SECONDS);
			CalendarSubscriptionService.Issued issuedSecond = second.get(60, TimeUnit.SECONDS);

			// Exatamente uma emissão gerou token; a outra viu a ativa já gravada e devolveu ela.
			int tokensBorn = (issuedFirst.token() != null ? 1 : 0) + (issuedSecond.token() != null ? 1 : 0);
			assertThat(tokensBorn).as("duas emissões na corrida não criam dois tokens").isEqualTo(1);
			assertThat(issuedFirst.subscription().id()).isEqualTo(issuedSecond.subscription().id());
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
			Future<?> a = pool.submit(() -> subscriptions.issue(userId, CalendarScope.SOCIAL));
			Future<?> b = pool.submit(() -> subscriptions.issue(userId, CalendarScope.SOCIAL));
			a.get(60, TimeUnit.SECONDS);
			b.get(60, TimeUnit.SECONDS);
		}
		finally {
			pool.shutdownNow();
		}

		subscriptions.revoke(userId);

		// Revogar mata a única ativa: não pode sobrar link vivo que a tela não conhece.
		assertThat(subscriptions.active(userId)).isEmpty();
	}

}
