package com.socialcoisa.rede.termometro.domain;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

/**
 * O corte agregado de uma janela do Termômetro: contagens por emoção, quantas manifestações
 * entraram na conta e de quantas pessoas distintas.
 *
 * É o único dado que o painel vê — texto e autoria ficam no módulo de origem e nunca chegam
 * aqui. As porcentagens são calculadas na hora da leitura a partir das contagens brutas, e a
 * linha do banco é reescrita pelo worker a cada corte de 5 minutos. O JSON das contagens é um
 * objeto plano {"ALEGRE":3,...} gerado aqui em ordem fixa de enum, para a linha gravada ser
 * determinística e auditável sem trazer Jackson para o domínio.
 */
public class CorteTermico {

	/** Quantas pessoas distintas, no mínimo, para o painel mostrar um indicador. */
	public static final int MINIMO_PARTICIPANTES = 20;

	private final TermometroPeriodo periodo;

	/** Contagem de manifestações classificadas em cada emoção da janela. */
	private final Map<Emocao, Long> contagens;

	private final long manifestacoes;

	/** Pessoas distintas que escreveram as manifestações da janela — o chão do painel. */
	private final long participantes;

	/** Fronteira de leitura das fontes que alimentou este corte (observabilidade do worker). */
	private final Instant watermark;

	public CorteTermico(TermometroPeriodo periodo, Map<Emocao, Long> contagens, long manifestacoes,
			long participantes, Instant watermark) {
		this.periodo = periodo;
		this.contagens = new EnumMap<>(contagens);
		this.manifestacoes = manifestacoes;
		this.participantes = participantes;
		this.watermark = watermark;
	}

	/** O corte lido da linha gravada: contagens vêm do JSON plano da coluna `contagens`. */
	public static CorteTermico de(TermometroPeriodo periodo, String contagensJson, long manifestacoes,
			long participantes, Instant watermark) {
		Map<Emocao, Long> contagens = new EnumMap<>(Emocao.class);
		for (Emocao emocao : Emocao.values()) {
			String chave = "\"" + emocao.name() + "\":";
			int em = contagensJson.indexOf(chave);
			if (em < 0) {
				continue;
			}
			int inicio = em + chave.length();
			int fim = contagensJson.indexOf(',', inicio);
			String numero = fim < 0 ? contagensJson.substring(inicio).replaceAll("[^0-9]", "")
					: contagensJson.substring(inicio, fim).replaceAll("[^0-9]", "");
			if (!numero.isEmpty()) {
				contagens.put(emocao, Long.parseLong(numero));
			}
		}
		return new CorteTermico(periodo, contagens, manifestacoes, participantes, watermark);
	}

	/** Corte vazio: zero em tudo, com o watermark na origem dos tempos para o worker começar. */
	public static CorteTermico vazio(TermometroPeriodo periodo) {
		return new CorteTermico(periodo, new EnumMap<>(Emocao.class), 0, 0, Instant.EPOCH);
	}

	public TermometroPeriodo periodo() {
		return periodo;
	}

	public Map<Emocao, Long> contagens() {
		return new EnumMap<>(contagens);
	}

	public long manifestacoes() {
		return manifestacoes;
	}

	public long participantes() {
		return participantes;
	}

	public Instant watermark() {
		return watermark;
	}

	/** A linha gravada da coluna `contagens`: objeto plano em ordem fixa de enum. */
	public String contagensComoJson() {
		StringBuilder json = new StringBuilder("{");
		String separador = "";
		for (Emocao emocao : Emocao.values()) {
			json.append(separador).append("\"").append(emocao.name()).append("\":")
				.append(contagens.getOrDefault(emocao, 0L));
			separador = ",";
		}
		return json.append("}").toString();
	}

	/** O painel mostra indicador? Menos gente que o chão é amostra pequena: nada se exibe. */
	public boolean temAmoSuficiente() {
		return participantes >= MINIMO_PARTICIPANTES;
	}

	/** Porcentagem da emoção na janela, inteira de 0 a 100, calculada das contagens brutas. */
	public int porcentagemDe(Emocao emocao) {
		if (manifestacoes == 0) {
			return 0;
		}
		long contagem = contagens.getOrDefault(emocao, 0L);
		return Math.round((contagem * 100.0f) / manifestacoes);
	}

	/** A emoção de maior contagem na janela; a primeira do enum em caso de empate. */
	public Emocao dominante() {
		return contagens.entrySet()
			.stream()
			.filter(entrada -> entrada.getValue() > 0)
			.max(Map.Entry.<Emocao, Long>comparingByValue()
				.thenComparing(entrada -> -entrada.getKey().ordinal()))
			.map(Map.Entry::getKey)
			.orElse(null);
	}

	/**
	 * O corte de outra janela com estes números (o worker monta cada janela do zero a cada
	 * corte; este método existe para testes e reuso da soma de contagens).
	 */
	public CorteTermico com(TermometroPeriodo periodo, long manifestacoes, long participantes,
			Instant watermark) {
		return new CorteTermico(periodo, this.contagens, manifestacoes, participantes, watermark);
	}

}
