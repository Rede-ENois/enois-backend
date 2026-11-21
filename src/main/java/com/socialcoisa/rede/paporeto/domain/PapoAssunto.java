package com.socialcoisa.rede.paporeto.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * O assunto agregado do Papo Reto: a expressão que nomeia a conversa, com os números que a
 * pontuação usa e o resultado pronto para a tela.
 *
 * O assunto é um subject normalizado (a expressão do cluster, dobrada sem acento), nunca citação
 * literal de ninguém — a linha gravada não guarda texto de publicação, só a contabilidade por
 * expressão. O assunto NÃO sabe de onde veio: o mesmo assunto falado numa comunidade e no feed
 * geral é um assunto só no escopo global, e um assunto próprio no escopo da comunidade. Quem
 * decide o recorte é o worker, que monta um agregado por escopo.
 *
 * <p>
 * <b>A pontuação tem dois fatores, e os dois são necessários.</b>
 *
 * <p>
 * O primeiro é a POTÊNCIA: o quanto a conversa está viva agora. Ela soma as fontes pesadas e
 * DECAÍDAS fala a fala (uma fala de 24 horas atrás vale metade, duas valem um quarto), a gente
 * distinta e o crescimento da última hora. A repetição de uma pessoa para no teto de
 * {@link #TETO_FALAS_POR_AUTOR} falas: quem publica vinte vezes a mesma palavra soma três, e o
 * resto é conversa de uma pessoa só — o que trava a inflação é o teto, não a vontade de quem
 * publica. Como o decaimento é por fala, assunto velho cai sozinho: uma fala nova não ressuscita
 * as quarenta de anteontem.
 *
 * <p>
 * O segundo é a DISCRIMINAÇÃO: o quanto o assunto é DA CONVERSA, e não da língua. Frequência
 * bruta não distingue "gente" de "seguranca digital" — a primeira aparece em toda publicação e
 * a segunda em poucas, e a soma de falas premia as duas igual. O que separa é a fração de
 * PUBLICAÇÕES DISTINTAS do escopo que falam do assunto: palavra que está em
 * {@link #LIMIAR_ONIPRESENTE} ou mais das publicações da janela não nomeia conversa nenhuma —
 * ela é a fala comum da rede. Expressão composta é específica por natureza e por isso não cai
 * nessa peneira: ela fica com o piso {@link #PISO_ONIPRESENTE_COMPOSTO}. Abaixo do limiar, o
 * assunto RARO vale mais: o fator cresce com o logaritmo do inverso da fração, com o teto de
 * {@link #TETO_DISCRIMINACAO} para a raridade não virar sozinha o ranking.
 *
 * <p>
 * A régua de publicações distintas só vale com massa de dados suficiente
 * ({@link #MINIMO_PUBLICACOES_REGUA} publicações na janela do escopo): em amostra pequena, a
 * fração é ruído e o assunto vale pelo que é. Expressão de uma palavra é a que cai na peneira;
 * composta não.
 */
public class PapoAssunto {

	/** A meia-vida do decaimento temporal: uma fala de 24 horas atrás vale metade. */
	public static final Duration MEIA_VIDA = Duration.ofHours(24);

	/**
	 * Quantas pessoas distintas, no mínimo, para o assunto aparecer na lista.
	 *
	 * O chão é ADAPTATIVO: numa base com gente de sobra, cinco pessoas separam a conversa da
	 * repetição de uma mão de contas; numa rede do tamanho de hoje, cinco pessoas na mesma
	 * semana tornam a lista quase vazia. Quando o escopo tem menos de
	 * {@link #BASE_PARA_CHAO_CHEIO} PUBLICAÇÕES na janela — a mesma massa em que a régua de
	 * discriminação já não julga —, o chão cai para {@link #MINIMO_PESSOAS_BASE_MENOR}; o assunto
	 * que só passa pela porta estreita aparece marcado como amostragem menor na tela, com o
	 * número de pessoas. O chão nunca desce abaixo de dois: uma pessoa repetindo não é conversa.
	 */
	public static final int MINIMO_PESSOAS = 5;

	/** O chão da base pequena: com pouca gente publicando, dois já estão conversando. */
	public static final int MINIMO_PESSOAS_BASE_MENOR = 2;

	/**
	 * O chão de pessoas do assunto, dado o tamanho da base (publicações distintas no escopo na
	 * janela): cinco pessoas quando o escopo tem massa de dados, dois quando tem pouco — e o
	 * assunto que só passa pela porta estreita aparece marcado como amostragem menor.
	 */
	public static int chaoDePessoas(long publicacoesNaBase) {
		return publicacoesNaBase >= BASE_PARA_CHAO_CHEIO ? MINIMO_PESSOAS : MINIMO_PESSOAS_BASE_MENOR;
	}

	/**
	 * O teto de falas que UMA pessoa soma no assunto: repetir a mesma palavra na mesma conversa
	 * não infla o assunto — três falas por pessoa é o que uma pessoa conta.
	 */
	public static final int TETO_FALAS_POR_AUTOR = 3;

	/**
	 * A partir de que fração de publicações distintas a palavra de UMA palavra é considerada
	 * onipresente e sai do ranking: presente em 15% das publicações da janela, ela é a fala
	 * comum da rede ("gente", "tipo", "sei"), não um assunto.
	 */
	public static final double LIMIAR_ONIPRESENTE = 0.15;

	/** O piso da expressão composta onipresente: conversa grande e legítima não desaparece. */
	public static final double PISO_ONIPRESENTE_COMPOSTO = 0.5;

	/** O teto do bônus de raridade: o assunto raro vale no máximo o dobro do assunto comum. */
	public static final double TETO_DISCRIMINACAO = 2.0;

	/** Sem esta massa de publicações na janela, a fração de publicações distintas não julga. */
	public static final int MINIMO_PUBLICACOES_REGUA = 20;

	/**
	 * A partir de quantas publicações distintas no escopo a base é grande o bastante para o
	 * chão cheio. Abaixo disso a amostragem é menor e o chão é o de
	 * {@link #MINIMO_PESSOAS_BASE_MENOR} — a mesma massa de {@link #MINIMO_PUBLICACOES_REGUA}.
	 */
	public static final int BASE_PARA_CHAO_CHEIO = MINIMO_PUBLICACOES_REGUA;

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

	/** Publicações distintas do assunto na janela — a matéria da discriminação. */
	private final long publicacoes;

	/** Publicações distintas do ESCOPO na janela — o denominador da discriminação. */
	private final long janelaPublicacoes;

	/** A potência medida no corte, com o decaimento avaliado naquele instante. */
	private final double potencia;

	/** A potência medida 24 horas antes do corte — a trajetória de verdade, não a fórmula. */
	private final double potenciaAntes;

	/** A linha foi gravada antes da régua nova e ainda não passou por um corte? */
	private final boolean legado;

	public PapoAssunto(String subject, long falasPost, long falasComentario, long pessoas, long falasUltimaHora,
			Instant ultimaFala, long publicacoes, long janelaPublicacoes, double potencia, double potenciaAntes) {
		this(subject, falasPost, falasComentario, pessoas, falasUltimaHora, ultimaFala, publicacoes, janelaPublicacoes,
				potencia, potenciaAntes, false);
	}

	private PapoAssunto(String subject, long falasPost, long falasComentario, long pessoas, long falasUltimaHora,
			Instant ultimaFala, long publicacoes, long janelaPublicacoes, double potencia, double potenciaAntes,
			boolean legado) {
		this.subject = subject;
		this.falasPost = falasPost;
		this.falasComentario = falasComentario;
		this.pessoas = pessoas;
		this.falasUltimaHora = falasUltimaHora;
		this.ultimaFala = ultimaFala;
		this.publicacoes = publicacoes;
		this.janelaPublicacoes = janelaPublicacoes;
		this.potencia = potencia;
		this.potenciaAntes = potenciaAntes;
		this.legado = legado;
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

	public long publicacoes() {
		return publicacoes;
	}

	public long janelaPublicacoes() {
		return janelaPublicacoes;
	}

	/** A potência do corte: as falas vivas agora, com os pesos e o decaimento já aplicados. */
	public double potencia() {
		return potencia;
	}

	/** A potência de 24 horas atrás, medida nas falas que existiam então. */
	public double potenciaAntes() {
		return potenciaAntes;
	}

	/** A linha gravada antes da régua nova, que ainda não passou por um corte. */
	public boolean legado() {
		return legado;
	}

	/**
	 * A potência de um assunto: fontes pesadas e decaídas + gente distinta + crescimento da
	 * última hora. Os pesos vêm da configuração; a soma é a mesma em todo corte.
	 */
	public static double potenciaDe(double fontesDecaidas, long pessoas, long falasUltimaHora, double pesoPessoas,
			double pesoCrescimento) {
		double gente = Math.sqrt(pessoas) * pesoPessoas;
		double crescimento = Math.sqrt(falasUltimaHora) * pesoCrescimento;
		return fontesDecaidas + gente + crescimento;
	}

	/**
	 * O fator de decaimento de uma fala pela sua idade até o instante de referência: uma
	 * meia-vida atrás vale metade, duas valem um quarto. É o que faz fala velha pesar menos que
	 * fala nova — e o que desinfla o assunto que ficou para trás sem apagar o histórico dele.
	 */
	public static double decaimento(Instant fala, Instant referencia) {
		long idadeSegundos = Math.max(0, referencia.getEpochSecond() - fala.getEpochSecond());
		double meiasVidas = idadeSegundos / (MEIA_VIDA.getSeconds() * 1.0);
		return Math.pow(0.5, meiasVidas);
	}

	/** A expressão nomeia mais de uma palavra? Então é composta — o específico, não o genérico. */
	public boolean composta() {
		return subject != null && subject.trim().indexOf(' ') > 0;
	}

	/**
	 * A palavra de UMA palavra que está em toda parte: sem expressão composta que a especifique,
	 * ela é a fala comum da rede e não vira linha do ranking.
	 */
	public boolean onipresente() {
		return !composta() && reguaAplicavel() && frequenciaEmPublicacoes() >= LIMIAR_ONIPRESENTE;
	}

	/**
	 * O quanto o assunto é da conversa: 1 quando a régua não julga (amostra pequena), o piso da
	 * expressão composta onipresente, zero para a palavra onipresente, e acima de 1 o bônus de
	 * raridade — com o teto.
	 */
	public double discriminacao() {
		if (!reguaAplicavel()) {
			return 1.0;
		}
		double frequencia = frequenciaEmPublicacoes();
		if (frequencia >= LIMIAR_ONIPRESENTE) {
			return composta() ? PISO_ONIPRESENTE_COMPOSTO : 0.0;
		}
		return Math.min(TETO_DISCRIMINACAO, 1.0 + Math.log(LIMIAR_ONIPRESENTE / frequencia));
	}

	/** A pontuação do assunto: a potência do corte ajustada pela discriminação. */
	public double pontuacao() {
		return potencia * discriminacao();
	}

	/**
	 * O assunto passou pela porta estreita: tem gente de verdade, mas menos que o chão cheio —
	 * a tela mostra o marcador de amostragem menor com o número de pessoas. Também cobre a base
	 * pequena, onde dois já são conversa por falta de gente, não por excesso de ruído.
	 */
	public boolean amostragemMenor() {
		return pessoas >= MINIMO_PESSOAS_BASE_MENOR && pessoas < MINIMO_PESSOAS;
	}

	/**
	 * A potência da régua ANTIGA, usada só para ler a linha gravada antes da virada (até o
	 * primeiro corte reescrevê-la): contagens acumuladas com um único decaimento, o da última
	 * fala. Mantida de propósito porque a leitura tolera o que já está na base.
	 */
	public double potenciaLegada(double pesoPosts, double pesoComentarios, double pesoPessoas, double pesoCrescimento,
			Instant agora) {
		double decaimentoUltimaFala = decaimento(ultimaFala, agora);
		double fonte = (falasPost * pesoPosts + falasComentario * pesoComentarios) * decaimentoUltimaFala;
		return potenciaDe(fonte, pessoas, falasUltimaHora, pesoPessoas, pesoCrescimento);
	}

	private boolean reguaAplicavel() {
		return janelaPublicacoes >= MINIMO_PUBLICACOES_REGUA && publicacoes > 0;
	}

	private double frequenciaEmPublicacoes() {
		return (double) publicacoes / janelaPublicacoes;
	}

	/** A linha gravada do agregado: objeto plano determinístico {"falasPost":n,...}. */
	public String estadoComoJson() {
		return "{\"falasPost\":" + falasPost + ",\"falasComentario\":" + falasComentario + ",\"pessoas\":" + pessoas
				+ ",\"falasUltimaHora\":" + falasUltimaHora + ",\"ultimaFalaEpoch\":" + ultimaFala.getEpochSecond()
				+ ",\"publicacoes\":" + publicacoes + ",\"janelaPublicacoes\":" + janelaPublicacoes
				+ ",\"potencia\":" + formatar(potencia) + ",\"potenciaAntes\":" + formatar(potenciaAntes) + "}";
	}

	/**
	 * A leitura da linha gravada; `nulo` deixa o assunto em estado zerado (subject permanece).
	 * A linha sem potência é da régua antiga: ela é marcada como {@link #legado()} e quem lê
	 * decide como pontuar até o próximo corte.
	 */
	public static PapoAssunto de(String subject, String estadoJson) {
		Map<String, String> campos = new LinkedHashMap<>();
		if (estadoJson != null) {
			for (String par : estadoJson.replaceAll("[{}\"]", "").split(",")) {
				String[] chaveValor = par.split(":");
				if (chaveValor.length == 2 && !chaveValor[0].isBlank()) {
					campos.put(chaveValor[0].trim(), chaveValor[1].trim());
				}
			}
		}
		Instant ultimaFala = Instant.ofEpochSecond(numero(campos, "ultimaFalaEpoch"));
		boolean legado = !campos.containsKey("potencia");
		return new PapoAssunto(subject, numero(campos, "falasPost"), numero(campos, "falasComentario"),
				numero(campos, "pessoas"), numero(campos, "falasUltimaHora"), ultimaFala, numero(campos, "publicacoes"),
				numero(campos, "janelaPublicacoes"), decimal(campos, "potencia"), decimal(campos, "potenciaAntes"),
				legado);
	}

	private static long numero(Map<String, String> campos, String chave) {
		return Long.parseLong(campos.getOrDefault(chave, "0"));
	}

	private static double decimal(Map<String, String> campos, String chave) {
		return Double.parseDouble(campos.getOrDefault(chave, "0"));
	}

	/** O decimal do JSON: ponto como separador, sem notação científica, independente da máquina. */
	private static String formatar(double valor) {
		return String.format(Locale.ROOT, "%.6f", valor);
	}

	@Override
	public String toString() {
		return subject + " " + estadoComoJson();
	}

}
