package com.socialcoisa.rede.calendar.application.port.out;

import java.util.Optional;

import com.socialcoisa.rede.calendar.domain.CalendarSubscription;

/** Porta de persistência das assinaturas `.ics`. A interface não conhece JPA. */
public interface CalendarSubscriptionRepository {

	void save(CalendarSubscription subscription);

	/** A assinatura ativa da pessoa, se houver. Revogada não conta. */
	Optional<CalendarSubscription> findActiveByUser(String userId);

	/** A assinatura pelo hash do token, ativa ou não: quem decide se ainda vale é o serviço. */
	Optional<CalendarSubscription> findByTokenHash(String tokenHash);
}
