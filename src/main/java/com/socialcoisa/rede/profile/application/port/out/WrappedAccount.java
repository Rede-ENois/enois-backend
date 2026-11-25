package com.socialcoisa.rede.profile.application.port.out;

import java.time.Instant;

/**
 * A projeção mínima de uma conta para a retrospectiva anual: só o que a fila do Énóis Wrapped
 * precisa (identidade, credibilidade materializada e data de criação). Não é um perfil.
 */
public record WrappedAccount(String userId, int reputation, Instant createdAt) {
}
