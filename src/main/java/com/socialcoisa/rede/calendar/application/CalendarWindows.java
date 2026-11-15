package com.socialcoisa.rede.calendar.application;

import java.time.Duration;
import java.time.Instant;

import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Teto de janela das leituras da agenda (fase 11).
 *
 * <p>Toda leitura por intervalo — lista, conflito e export — carrega os compromissos da pessoa no
 * intervalo pedido. O intervalo vem do cliente e, sem teto, um `from` de décadas atrás transforma
 * uma leitura em varredura da agenda inteira, com join de convidados junto. O teto é o mesmo da
 * assinatura `.ics` (um ano), que já nasce dentro dele.
 *
 * <p>A validação mora aqui, e não em cada serviço, para que um caminho novo de leitura não nasça
 * sem teto por esquecimento.
 */
public final class CalendarWindows {

	/** Um ano: o mesmo intervalo que a assinatura entrega sem sessão. */
	public static final Duration MAX_SPAN = Duration.ofDays(366);

	private CalendarWindows() {
	}

	public static void validate(Instant from, Instant to, String purpose) {
		if (from == null || to == null) {
			throw new ApiException(400, "INVALID_WINDOW", "Informe from e to para " + purpose + ".");
		}
		if (!to.isAfter(from)) {
			throw new ApiException(400, "INVALID_WINDOW", "to precisa ser depois de from.");
		}
		if (Duration.between(from, to).compareTo(MAX_SPAN) > 0) {
			throw new ApiException(400, "WINDOW_TOO_LONG",
				"A janela cobre no máximo um ano; divida em intervalos menores.");
		}
	}
}
