package com.socialcoisa.rede.paporeto.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

/**
 * O assunto agregado do Papo Reto: a expressão que nomeia a conversa, com os números que a
 * pontuação usa e o resultado pronto para a tela.
 *
 * O assunto é um subject normalizado (a expressão mais comum do cluster, dobrada sem acento),
 * nunca citação literal de ninguém — a linha gravada não guarda texto de publicação, só a
 * contabilidade por expressão. A pontuação soma quatro pesos (falas de post, falas de
 * comentário, pessoas distintas, crescimento recente) com decaimento exponencial pela idade
 * de cada fala; a meia-vida define o ritmo do esquecimento.
 *
 * O assunto NÃO sabe de onde veio: o mesmo assunto falado numa comunidade e no feed geral é um
 * assunto só no escopo global, e um assunto próprio no escopo da comunidade. Quem decide o
 * recorte é o worker, que monta um agregado por escopo.
 */
public class PapoAssunto {

	/** A meia-vida do decaimento temporal: uma fala de 24 horas atrás vale metade. */
	public static final Duration MEIA_VIDA = Duration.ofHours(24);

	/** Quantas pessoas distintas, no mínimo, para o assunto aparecer na lista. */
	public static final int MINIMO_PESSOAS = 5;

	private final String subject;

	/** Falas do assunto por fonte: POST e COMMENT (DESABAFO e CONFISSAO contam como POST). */
	private final long falasPost;

	private final long falasComentario;

	/** Pessoas distintas que falaram o assunto na janela — o chão anti-manipulação. */
	private final long pessoas;

	/** Falas da última hora relativa ao corte — a matéria do crescimento. */
	private final long falasUltimaHora;

	/** O instante da fala mais recente do assunto (observabilidade do decaimento). */
	private final Instant ultimaFala;

	public PapoAssunto(String subject, long falasPost, long falasComentario, long pessoas,
			long falasUltimaHora, Instant ultimaFala) {
		this.subject = subject;
		this.falasPost = falasPost;
		this.falasComentario = falasComentario;
		this.pessoas = pessoas;
		this.falasUltimaHora = falasUltimaHora;
		this.ultimaFala = ultimaFala;
	}

	public String subject() {
		return subject;
	}

	public long falasPost() {
		return falasPost;
	}

	public long falasComentario() {
		return falasComentario;
	}

	public long pessoas() {
		return pessoas;
	}

	public long falasUltimaHora() {
		return falasUltimaHora;
	}

	public Instant ultimaFala() {
		return ultimaFala;
	}

	/**
	 * A potência do assunto em `agora`: fontes pesadas, gente distinta com chão e crescimento
	 * recente, tudo com decaimento pela idade das falas. Mesmos números, mesma potência.
	 */
	public double potencia(double pesoPosts, double pesoComentarios, double pesoPessoas, double pesoCrescimento,
			Instant agora) {
		double decaimentoPost = decaimento(ultimaFala, agora);
		double fonte = (falasPost * pesoPosts + falasComentario * pesoComentarios) * decaimentoPost;
		double gente = Math.sqrt(pessoas) * pesoPessoas;
		double crescimento = Math.sqrt(falasUltimaHora) * pesoCrescimento;
		return fonte + gente + crescimento;
	}

	/**
	 * O decaimento exponencial pela idade da última fala: uma meia-vida atrás vale metade,
	 * duas valem um quarto. Assunto sem fala nova esmorece sozinho, sem remoção manual.
	 */
	static double decaimento(Instant ultimaFala, Instant agora) {
		long idadeSegundos = Math.max(0, agora.getEpochSecond() - ultimaFala.getEpochSecond());
		double meiasVidas = idadeSegundos / (MEIA_VIDA.getSeconds() * 1.0);
		return Math.pow(0.5, meiasVidas);
	}

	/** A linha gravada do agregado: objeto plano determinístico {"falasPost":n,...}. */
	public String estadoComoJson() {
		return "{\"falasPost\":" + falasPost + ",\"falasComentario\":" + falasComentario + ",\"pessoas\":" + pessoas
				+ ",\"falasUltimaHora\":" + falasUltimaHora + ",\"ultimaFalaEpoch\":" + ultimaFala.getEpochSecond()
				+ "}";
	}

	/** A leitura da linha gravada; `nulo` deixa o assunto em estado zerado (subject permanece). */
	public static PapoAssunto de(String subject, String estadoJson) {
		Map<String, Long> numeros = new TreeMap<>();
		for (String par : estadoJson.replaceAll("[{}\"]", "").split(",")) {
			String[] chaveValor = par.split(":");
			if (chaveValor.length == 2 && !chaveValor[0].isBlank()) {
				numeros.put(chaveValor[0].trim(), Long.parseLong(chaveValor[1].trim()));
			}
		}
		Instant ultimaFala = Instant.ofEpochSecond(numeros.getOrDefault("ultimaFalaEpoch", 0L));
		return new PapoAssunto(subject, numeros.getOrDefault("falasPost", 0L),
				numeros.getOrDefault("falasComentario", 0L), numeros.getOrDefault("pessoas", 0L),
				numeros.getOrDefault("falasUltimaHora", 0L), ultimaFala);
	}

	@Override
	public String toString() {
		return subject + " " + estadoComoJson();
	}

}
