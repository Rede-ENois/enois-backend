package com.socialcoisa.rede.network.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.OptionalDouble;

/**
 * A disponibilidade histórica de um nó, em dias medidos (brief §7, V369).
 *
 * <p><b>Antes:</b> não existia. "Disponibilidade histórica" era o último item do §7 marcado como não
 * entregue, porque a rede guardava transições (quantas vezes o nó voltou) e não uma série — e a soma
 * do tempo fora parava nas 200 linhas mais recentes, sem nunca virar total.</p>
 *
 * <p><b>Agora:</b> um contador por dia, e a proporção é uma soma sobre uma soma. É a mesma regra da
 * média ponderada das operações do Storage: somar as proporções dos dias (ou tirar a média delas)
 * daria peso igual a um dia com uma hora de medida e a um dia inteiro — o número sairia de dados
 * desiguais como se fossem iguais.</p>
 *
 * <h2>O que a proporção diz e o que não diz</h2>
 *
 * <ul>
 *   <li>Só conta dia <b>medido</b> (com pelo menos um heartbeat). Dia sem linha é "sem dado", nunca
 *       "0% no ar" — o Control Plane não pode afirmar o que não mediu.</li>
 *   <li>O denominador é o tempo <b>observado</b>, não 24 h: uma janela de 7 dias de um nó que entrou
 *       na rede ontem mede ontem, não a semana toda. Somar as horas em que ninguém estava olhando
 *       transformaria "entrou ontem e está bem" em "esteve fora quase uma semana".</li>
 *   <li>Ausência antes do primeiro e depois do último heartbeat do dia fica de fora: é falta de
 *       dado, não prova de queda.</li>
 * </ul>
 *
 * @param days           os dias medidos, do mais antigo para o mais novo
 * @param measuredDays   quantos dias têm medida
 * @param presentSeconds soma do tempo no ar nos dias medidos
 * @param observedSeconds soma do tempo observado nos dias medidos
 * @param offlineSeconds o tempo FORA em toda a janela — componente e não getter derivado, porque
 *                       o JSON da rota é o contrato da tela, e um getter não vira campo
 * @param percentage     a proporção ponderada, ou {@code null} quando não houve tempo observado:
 *                       {@code null} é "sem dado", que é diferente de 0%
 */
public record NodeAvailability(List<Day> days, int measuredDays, long presentSeconds, long observedSeconds,
		long offlineSeconds, Double percentage) {

	/** Um dia medido: quanto esteve no ar e sobre quanto tempo houve medida. */
	public record Day(LocalDate date, long presentSeconds, long observedSeconds) {

		public Day {
			if (presentSeconds < 0 || observedSeconds < 0) {
				throw new IllegalArgumentException("Segundos negativos em " + date);
			}
			// Um dia com mais tempo no ar do que observado é impossível: o presente é um subconjunto
			// do observado. Recusar em vez de cortar em silêncio — um dado gravado assim é defeito de
			// gravação, e mostrar 100% esconderia o defeito atrás de uma conta bonita.
			if (presentSeconds > observedSeconds) {
				throw new IllegalArgumentException("Dia " + date + " com " + presentSeconds
					+ " s no ar sobre " + observedSeconds + " s observados");
			}
		}

		/** Proporção do dia, ou vazio quando o dia não tem tempo observado. */
		public OptionalDouble percentage() {
			return observedSeconds == 0 ? OptionalDouble.empty()
				: OptionalDouble.of(100.0 * presentSeconds / observedSeconds);
		}
	}

	/** Nenhum dia medido — o estado de um nó recém-criado. */
	public static NodeAvailability empty() {
		return new NodeAvailability(List.of(), 0, 0, 0, 0, null);
	}

	/**
	 * Monta a série com os dois números derivados calculados uma vez — a proporção é uma soma sobre
	 * uma soma, ponderada pelos segundos observados de cada dia, nunca a média das proporções.
	 */
	public static NodeAvailability of(List<Day> days) {
		if (days.isEmpty()) {
			return empty();
		}
		long presente = 0;
		long observado = 0;
		for (Day d : days) {
			presente += d.presentSeconds();
			observado += d.observedSeconds();
		}
		Double proporcao = observado == 0 ? null : 100.0 * presente / observado;
		return new NodeAvailability(List.copyOf(days), days.size(), presente, observado,
			Math.max(0, observado - presente), proporcao);
	}
}