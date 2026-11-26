package com.socialcoisa.rede.wrapped.application.port.out;

import java.time.Instant;

/** Uma conta elegível: id, a credibilidade (coluna materializada) e quando foi criada. */
public record WrappedConta(String userId, int credibilidade, Instant criadaEm) {
}
