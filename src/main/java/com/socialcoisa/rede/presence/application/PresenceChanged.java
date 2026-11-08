package com.socialcoisa.rede.presence.application;

import com.socialcoisa.rede.presence.domain.PresenceStatus;

/**
 * A pessoa mudou o próprio estado de presença. Quem depende disso (o toque da chamada direta, por
 * exemplo) escuta depois do commit, para não agir sobre uma escolha que ainda podia voltar atrás.
 */
public record PresenceChanged(String userId, PresenceStatus status) {
}
