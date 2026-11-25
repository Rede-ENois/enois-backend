package com.socialcoisa.rede.wrapped.domain;

/**
 * O ciclo de vida de UMA retrospectiva (conta + ano).
 *
 * <p>{@code PENDING} é a única fila: o worker pega a próxima por posição. {@code PROCESSING} é a
 * conta reservada por uma instância; a reserva expira ({@code claim_expira_em}) e a linha volta
 * para {@code PENDING} sem perder progresso — a geração é atômica por conta, então nunca há
 * parcial grave. {@code DONE} é o resultado persistido: não volta para a fila e não é recalculado.
 * {@code FAILED} guardou a falha e também deixa de ocupar a fila.</p>
 */
public enum WrappedEstado {

	PENDING,
	PROCESSING,
	DONE,
	FAILED;

	public static WrappedEstado parse(String raw) {
		return WrappedEstado.valueOf(raw == null ? "" : raw.trim().toUpperCase(java.util.Locale.ROOT));
	}
}
