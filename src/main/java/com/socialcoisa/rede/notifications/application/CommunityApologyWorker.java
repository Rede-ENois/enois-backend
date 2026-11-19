package com.socialcoisa.rede.notifications.application;

import java.time.Clock;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.socialcoisa.rede.coin.application.CoinService;
import com.socialcoisa.rede.notifications.application.port.out.SystemAnnouncementRepository;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;

/**
 * Desculpas públicas pelos últimos dias (instabilidade, segurança) + 100 faíscas para cada conta.
 *
 * <p>Dispara UMA vez, independente do S3. Substitui o comunicado antigo do incidente de
 * armazenamento (que esperava o CDN responder) — assim ninguém recebe dois créditos de 100
 * faíscas pela mesma desculpa.</p>
 *
 * <p>A idempotência é por conta ({@code ref = chave:userId}) e pela marca em
 * {@code system_announcements}: reiniciar o pod não duplica faíscas nem notificações.</p>
 */
@Component
public class CommunityApologyWorker {

	private static final Logger LOG = LoggerFactory.getLogger(CommunityApologyWorker.class);

	public static final String ANNOUNCEMENT_KEY = "desculpa-comunidade-2026-10-21";
	public static final String TITULO = "Estamos trabalhando para melhorar o Énóis!";
	public static final String CORPO = "Pedimos desculpas pelos inconvenientes dos últimos dias. "
		+ "Estamos trabalhando ativamente para corrigir problemas, reforçar a segurança e tornar a "
		+ "plataforma cada vez melhor para todo mundo. Obrigada pela paciência e por fazer parte dessa "
		+ "comunidade. O Énóis é feito por pessoas, para pessoas, e seguimos construindo tudo isso com "
		+ "muito cuidado e transparência. Seguimos juntos. E para agradecer, você recebeu 100 faíscas "
		+ "para usar como quiser na loja, no marketplace e nos espaços da comunidade.";
	public static final String HREF = "/marketplace";
	public static final String CTA = "Ver as novidades";
	public static final long BONUS = CoinService.FAIS(100L);

	private static final int PAGE = 200;

	private final UserProfileRepository profiles;
	private final CoinService coins;
	private final NotificationService notifications;
	private final SystemAnnouncementRepository announcements;
	private final Clock clock;

	CommunityApologyWorker(UserProfileRepository profiles, CoinService coins, NotificationService notifications,
			SystemAnnouncementRepository announcements, Clock clock) {
		this.profiles = profiles;
		this.coins = coins;
		this.notifications = notifications;
		this.announcements = announcements;
		this.clock = clock;
	}

	@Scheduled(fixedDelayString = "${rede.communications.apology-delay-ms:1800000}",
		initialDelayString = "${rede.communications.apology-initial-delay-ms:90000}")
	public void announce() {
		if (announcements.isSent(ANNOUNCEMENT_KEY)) {
			return;
		}
		int announced = 0;
		String afterId = null;
		while (true) {
			List<String> ids = profiles.findAllIds(afterId, PAGE);
			if (ids.isEmpty()) {
				break;
			}
			for (String userId : ids) {
				try {
					String ref = ANNOUNCEMENT_KEY + ":" + userId;
					if (!coins.hasReward(ref)) {
						coins.reward(userId, BONUS, ref, "Desculpas pelos últimos dias — 100 faíscas de presente");
					}
					// Duas réplicas podem rodar o worker ao mesmo tempo: o crédito é idempotente
					// pela referência, e o aviso é conferido antes para não duplicar na caixa.
					boolean alreadyNotified = notifications.list(userId, null, 10).stream()
						.anyMatch(aviso -> TITULO.equals(aviso.title()));
					if (!alreadyNotified) {
						notifications.create(userId, "SISTEMA", "sistema", TITULO, CORPO, HREF, CTA);
					}
					announced++;
				}
				catch (RuntimeException perUser) {
					// Uma conta problemática não pode impedir o comunicado das demais.
					LOG.warn("Falha ao comunicar o usuário {} sobre as desculpas: {}", userId, perUser.getMessage());
				}
			}
			if (ids.size() < PAGE) {
				break;
			}
			afterId = ids.getLast();
		}
		announcements.markSent(ANNOUNCEMENT_KEY, clock.instant());
		LOG.info("Comunicado de desculpas enviado: {} usuários, {} faíscas cada.", announced,
			BONUS / CoinService.SCALE);
	}
}
