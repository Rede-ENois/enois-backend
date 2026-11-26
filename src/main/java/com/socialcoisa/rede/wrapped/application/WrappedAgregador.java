package com.socialcoisa.rede.wrapped.application;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.socialcoisa.rede.wrapped.application.port.out.WrappedAssuntos;
import com.socialcoisa.rede.wrapped.application.port.out.WrappedComunidadesRepository;
import com.socialcoisa.rede.wrapped.application.port.out.WrappedEvento;
import com.socialcoisa.rede.wrapped.application.port.out.WrappedEvento.Tipo;
import com.socialcoisa.rede.wrapped.domain.WrappedAno;
import com.socialcoisa.rede.wrapped.domain.WrappedAssunto;
import com.socialcoisa.rede.wrapped.domain.WrappedComportamento;
import com.socialcoisa.rede.wrapped.domain.WrappedComunidade;
import com.socialcoisa.rede.wrapped.domain.WrappedEncerramento;
import com.socialcoisa.rede.wrapped.domain.WrappedImpacto;
import com.socialcoisa.rede.wrapped.domain.WrappedInteracao;
import com.socialcoisa.rede.wrapped.domain.WrappedMetricas;
import com.socialcoisa.rede.wrapped.domain.WrappedPerfil;
import com.socialcoisa.rede.wrapped.domain.WrappedPerfil.Caracteristica;
import com.socialcoisa.rede.wrapped.domain.WrappedPerfil.Chave;
import com.socialcoisa.rede.wrapped.domain.WrappedPublicacao;
import com.socialcoisa.rede.wrapped.domain.WrappedRetrospectiva;
import com.socialcoisa.rede.wrapped.domain.WrappedRitmo;
import com.socialcoisa.rede.wrapped.domain.WrappedTreta;

/**
 * O reconhecimento de padrões da retrospectiva, determinístico: mesma entrada, mesmo resultado.
 *
 * <p>Trabalha só com os eventos da CONTA (lidos página a página por {@link WrappedLeitura}) e com
 * as comunidades onde ela esteve. Todo número é contagem de um fato que veio do banco; nenhuma
 * métrica é estimada. A seção que não tem dado suficiente sai vazia (a view a esconde), e a conta
 * sem atividade nenhuma sai marcada como {@code dadosInsuficientes} — nunca uma retrospectiva
 * fabricada.</p>
 *
 * <p>A credibilidade NÃO entra na análise: o agregador não a recebe. Ela é usada só para priorizar
 * a fila e como contexto na seção de impacto (ver {@link #comCredibilidade}).</p>
 */
public final class WrappedAgregador {

	public static final int VERSAO_ALGORITMO = 1;

	private static final int LIMITE_ASSUNTOS = 5;
	private static final int LIMITE_PUBLICACOES = 3;
	private static final int LIMITE_TRETAS = 3;
	private static final int LIMITE_INTERACOES = 3;
	private static final int LIMITE_COMUNIDADES = 3;
	private static final int TRECHO_MAX = 140;
	/** Uma "treta" é discussão quando a conta falou nela ao menos duas vezes. */
	private static final int MINIMO_COMENTARIOS_TRETA = 2;
	/** O assunto principal precisa de ao menos esta concentração para o perfil "especialista". */
	private static final double CONCENTRACAO_ESPECIALISTA = 0.35;
	/** Razão "interações feitas / atividade" a partir da qual o perfil "observador" aparece. */
	private static final double RAZAO_OBSERVADOR = 0.6;

	private WrappedAgregador() {
	}

	/**
	 * A retrospectiva de uma conta a partir dos eventos do ano.
	 *
	 * @param userId      a conta (só para montar as interações; o dono é sempre a sessão autenticada)
	 * @param ano         o ano de referência
	 * @param leitura     a leitura paginada do histórico da conta
	 * @param comunidades as comunidades da conta no ano
	 * @param assuntos    o extrator de assuntos (Papo Reto)
	 */
	public static WrappedRetrospectiva montar(String userId, WrappedAno ano, WrappedLeitura leitura,
			WrappedComunidadesRepository comunidades, WrappedAssuntos assuntos) {
		List<WrappedEvento> eventos = carregar(leitura, userId, ano);
		Map<String, WrappedEscopo> escopos = organizar(eventos);
		List<WrappedComunidade> comunidadesDaConta = comunidadesPlenas(comunidades, userId, ano);
		WrappedMetricas metricas = metricas(eventos, comunidadesDaConta);

		List<WrappedAssunto> publicados = assuntos.extrair(textosDe(escopos, Tipo.PUBLICACAO), LIMITE_ASSUNTOS);
		List<WrappedAssunto> comentados = assuntos.extrair(textosDe(escopos, Tipo.COMENTARIO_PROPRIO),
				LIMITE_ASSUNTOS);

		WrappedPerfil perfil = perfil(metricas, publicados);
		WrappedImpacto impacto = impacto(eventos, metricas, comunidadesDaConta);
		WrappedEncerramento encerramento = encerramento(publicados, perfil, metricas);

		return new WrappedRetrospectiva(ano.ano(), VERSAO_ALGORITMO, metricas.semAtividade(), metricas,
				publicados, comentados, publicacoesMarcantes(escopos), tretas(escopos),
				interacoes(escopos, userId), ritmo(eventos), perfil, impacto,
				limitar(new ArrayList<>(comunidadesDaConta), LIMITE_COMUNIDADES), encerramento);
	}

	/** A retrospectiva com a credibilidade real (lida à parte, da coluna materializada). */
	public static WrappedRetrospectiva comCredibilidade(WrappedRetrospectiva base, Integer credibilidade) {
		WrappedImpacto i = base.impacto();
		return new WrappedRetrospectiva(base.ano(), base.versaoAlgoritmo(), base.dadosInsuficientes(),
				base.metricas(), base.assuntosPublicados(), base.assuntosComentados(),
				base.publicacoesMarcantes(), base.tretas(), base.interacoes(), base.ritmo(), base.perfil(),
				new WrappedImpacto(i.publicacoesComConversa(), i.comentariosComReacao(),
						i.interacoesRecebidas(), i.comunidades(), credibilidade == null ? 0 : credibilidade),
				base.comunidades(), base.encerramento());
	}

	// --- leitura -------------------------------------------------------------------------------

	private static List<WrappedEvento> carregar(WrappedLeitura leitura, String userId, WrappedAno ano) {
		List<WrappedEvento> todos = new ArrayList<>();
		String cursor = null;
		while (true) {
			List<WrappedEvento> pagina = leitura.pagina(userId, ano.inicio(), ano.fim(), cursor, 500);
			if (pagina.isEmpty()) {
				break;
			}
			todos.addAll(pagina);
			cursor = pagina.get(pagina.size() - 1).cursor();
		}
		return todos;
	}

	private static Map<String, WrappedEscopo> organizar(List<WrappedEvento> eventos) {
		Map<String, WrappedEscopo> escopos = new LinkedHashMap<>();
		for (WrappedEvento evento : eventos) {
			escopos.computeIfAbsent(evento.tipo().name(), k -> new WrappedEscopo()).eventos.add(evento);
		}
		return escopos;
	}

	private static List<WrappedEvento> lista(Map<String, WrappedEscopo> escopos, Tipo tipo) {
		WrappedEscopo escopo = escopos.get(tipo.name());
		return escopo == null ? List.of() : escopo.eventos;
	}

	private static int contar(List<WrappedEvento> eventos, Tipo tipo) {
		return (int) eventos.stream().filter(e -> e.tipo() == tipo).count();
	}

	// --- métricas ------------------------------------------------------------------------------

	private static WrappedMetricas metricas(List<WrappedEvento> eventos, List<WrappedComunidade> comunidades) {
		Set<String> dias = new HashSet<>();
		int[] porMes = new int[12];
		for (WrappedEvento e : eventos) {
			dias.add(e.diaLocal().toString());
			porMes[e.momentoLocal().getMonthValue() - 1]++;
		}
		return new WrappedMetricas(
				contar(eventos, Tipo.PUBLICACAO),
				contar(eventos, Tipo.COMENTARIO_PROPRIO),
				contar(eventos, Tipo.REACAO_RECEBIDA),
				contar(eventos, Tipo.COMENTARIO_RECEBIDO),
				contar(eventos, Tipo.COMPARTILHAMENTO_RECEBIDO),
				contar(eventos, Tipo.REPOST_RECEBIDO),
				contar(eventos, Tipo.REACAO_FEITA),
				contar(eventos, Tipo.COMENTARIO_FEITO),
				contar(eventos, Tipo.COMPARTILHAMENTO_FEITO),
				contar(eventos, Tipo.REPOST_FEITO),
				contar(eventos, Tipo.VOTO_FEITO),
				contar(eventos, Tipo.SALVO_FEITO),
				// TODAS as comunidades do ano (não a lista limitada exibida na seção): a métrica é honesta.
				comunidades.size(),
				dias.size(),
				// mesMaisAtivo é 1..12 (ou null sem atividade), como o resto do app lê o mês.
				pico(porMes) == null ? null : pico(porMes) + 1);
	}

	/** Os textos de uma fonte, na ordem em que os eventos chegaram (determinístico). */
	private static List<String> textosDe(Map<String, WrappedEscopo> escopos, Tipo tipo) {
		List<String> textos = new ArrayList<>();
		for (WrappedEvento e : lista(escopos, tipo)) {
			if (e.temTexto()) {
				textos.add(e.texto());
			}
		}
		return textos;
	}

	// --- publicações marcantes ----------------------------------------------------------------

	private static List<WrappedPublicacao> publicacoesMarcantes(Map<String, WrappedEscopo> escopos) {
		Map<String, PublicacaoAgregada> porPost = new LinkedHashMap<>();

		// O engajamento de uma publicação própria: quantas interações recebidas naquele post.
		for (Tipo tipo : List.of(Tipo.REACAO_RECEBIDA, Tipo.COMENTARIO_RECEBIDO,
				Tipo.COMPARTILHAMENTO_RECEBIDO, Tipo.REPOST_RECEBIDO)) {
			for (WrappedEvento e : lista(escopos, tipo)) {
				porPost.computeIfAbsent(e.postId(), k -> new PublicacaoAgregada(null)).engajamento++;
			}
		}
		// As publicações próprias: o trecho e a data vêm do evento da própria publicação.
		for (WrappedEvento e : lista(escopos, Tipo.PUBLICACAO)) {
			PublicacaoAgregada p = porPost.computeIfAbsent(e.postId(), k -> new PublicacaoAgregada(e));
			p.evento = e;
			p.propria = true;
		}
		// A participação da conta em conteúdo de terceiros, por post.
		for (Tipo tipo : List.of(Tipo.REACAO_FEITA, Tipo.COMPARTILHAMENTO_FEITO, Tipo.REPOST_FEITO)) {
			for (WrappedEvento e : lista(escopos, tipo)) {
				PublicacaoAgregada p = porPost.computeIfAbsent(e.postId(), k -> new PublicacaoAgregada(e));
				p.participacao++;
			}
		}

		List<WrappedPublicacao> resultado = new ArrayList<>();
		porPost.forEach((postId, p) -> {
			if (p.propria) {
				if (p.engajamento > 0 && p.evento != null) {
					resultado.add(new WrappedPublicacao(postId, p.evento.autorId(), p.evento.autorUsername(),
							p.evento.autorNome(), trecho(p.evento.texto()),
							WrappedPublicacao.Motivo.PROPRIA_ENGAJADA, p.engajamento,
							WrappedAno.dataIso(p.evento.momento())));
				}
				return;
			}
			if (p.participacao > 0 && p.evento != null) {
				resultado.add(new WrappedPublicacao(postId, p.evento.autorId(), p.evento.autorUsername(),
						p.evento.autorNome(), trecho(p.evento.texto()),
						WrappedPublicacao.Motivo.PARTICIPACAO, p.participacao,
						WrappedAno.dataIso(p.evento.momento())));
			}
		});
		resultado.sort(Comparator.comparingInt(WrappedPublicacao::metrica).reversed()
				.thenComparing(WrappedPublicacao::id));
		return limitar(resultado, LIMITE_PUBLICACOES);
	}

	// --- tretas --------------------------------------------------------------------------------

	private static List<WrappedTreta> tretas(Map<String, WrappedEscopo> escopos) {
		Map<String, List<WrappedEvento>> porPost = new LinkedHashMap<>();
		// Os comentários da conta — os próprios (no próprio post) e os em conteúdo de terceiros.
		for (Tipo tipo : List.of(Tipo.COMENTARIO_PROPRIO, Tipo.COMENTARIO_FEITO)) {
			for (WrappedEvento e : lista(escopos, tipo)) {
				porPost.computeIfAbsent(e.postId(), k -> new ArrayList<>()).add(e);
			}
		}
		// O trecho de uma publicação própria comentada vem do evento da publicação.
		Map<String, String> textos = new HashMap<>();
		for (WrappedEvento e : lista(escopos, Tipo.PUBLICACAO)) {
			textos.put(e.postId(), e.texto());
		}

		List<WrappedTreta> resultado = new ArrayList<>();
		porPost.forEach((postId, lista) -> {
			if (lista.size() < MINIMO_COMENTARIOS_TRETA) {
				return;
			}
			lista.sort(Comparator.comparing(WrappedEvento::momento));
			Set<String> dias = new HashSet<>();
			for (WrappedEvento e : lista) {
				dias.add(e.diaLocal().toString());
			}
			Instant primeira = lista.get(0).momento();
			Instant ultima = lista.get(lista.size() - 1).momento();
			resultado.add(new WrappedTreta(postId, lista.size(), dias.size(),
					Duration.between(primeira, ultima).toDays(), primeira, ultima, trecho(textos.get(postId))));
		});
		resultado.sort(Comparator.comparingInt(WrappedTreta::comentarios).reversed()
				.thenComparing(Comparator.comparingLong(WrappedTreta::duracaoDias).reversed())
				.thenComparing(WrappedTreta::publicacaoId));
		return limitar(resultado, LIMITE_TRETAS);
	}

	// --- interações ----------------------------------------------------------------------------

	private static List<WrappedInteracao> interacoes(Map<String, WrappedEscopo> escopos, String userId) {
		Map<String, MapaContato> interagi = new LinkedHashMap<>();
		// O que a conta fez no conteúdo de terceiros: reação, comentário, compartilhamento, repost e
		// voto. O comentário entra porque é o cruzamento mais forte de todos e estava de fora.
		for (Tipo tipo : List.of(Tipo.REACAO_FEITA, Tipo.COMENTARIO_FEITO, Tipo.COMPARTILHAMENTO_FEITO,
				Tipo.REPOST_FEITO, Tipo.VOTO_FEITO)) {
			for (WrappedEvento e : lista(escopos, tipo)) {
				contabilizar(interagi, e, userId);
			}
		}
		Map<String, MapaContato> interagiram = new LinkedHashMap<>();
		for (Tipo tipo : List.of(Tipo.REACAO_RECEBIDA, Tipo.REACAO_RECEBIDA_COMENTARIO,
				Tipo.COMENTARIO_RECEBIDO, Tipo.COMPARTILHAMENTO_RECEBIDO, Tipo.REPOST_RECEBIDO)) {
			for (WrappedEvento e : lista(escopos, tipo)) {
				contabilizar(interagiram, e, userId);
			}
		}

		List<WrappedInteracao> resultado = new ArrayList<>();
		resultado.addAll(topContatos(interagi, WrappedInteracao.Tipo.INTERAGI));
		resultado.addAll(topContatos(interagiram, WrappedInteracao.Tipo.INTERAGIRAM));
		return resultado;
	}

	private static void contabilizar(Map<String, MapaContato> contatos, WrappedEvento e, String userId) {
		if (e.autorId() == null || e.autorId().equals(userId)) {
			return;
		}
		contatos.computeIfAbsent(e.autorId(), k -> new MapaContato(e.autorId(), e.autorUsername(), e.autorNome()))
				.vezes++;
	}

	private static List<WrappedInteracao> topContatos(Map<String, MapaContato> contatos,
			WrappedInteracao.Tipo tipo) {
		return limitar(contatos.values().stream()
				.sorted(Comparator.comparingInt((MapaContato c) -> c.vezes).reversed()
						.thenComparing(c -> c.userId))
				.map(c -> new WrappedInteracao(c.userId, c.username, c.nome, c.vezes, tipo))
				.toList(), LIMITE_INTERACOES);
	}

	// --- ritmo ---------------------------------------------------------------------------------

	private static WrappedRitmo ritmo(List<WrappedEvento> eventos) {
		int[] porMes = new int[12];
		int[] porDia = new int[7];
		int[] porHora = new int[24];
		for (WrappedEvento e : eventos) {
			var local = e.momentoLocal();
			porMes[local.getMonthValue() - 1]++;
			// DayOfWeek: segunda=1 ... domingo=7 -> índice domingo=0
			porDia[local.getDayOfWeek().getValue() % 7]++;
			porHora[local.getHour()]++;
		}
		List<Integer> meses = lista(porMes);
		List<Integer> trimestres = List.of(meses.get(0) + meses.get(1) + meses.get(2),
				meses.get(3) + meses.get(4) + meses.get(5),
				meses.get(6) + meses.get(7) + meses.get(8),
				meses.get(9) + meses.get(10) + meses.get(11));
		return new WrappedRitmo(pico(porDia), pico(porHora), pico(porMes), meses, lista(porDia), lista(porHora),
				new WrappedComportamento(trimestres, WrappedComportamento.sentido(trimestres)));
	}

	private static Integer pico(int[] valores) {
		int melhor = 0;
		Integer indice = null;
		for (int i = 0; i < valores.length; i++) {
			if (valores[i] > melhor) {
				melhor = valores[i];
				indice = i;
			}
		}
		return indice;
	}

	private static List<Integer> lista(int[] valores) {
		List<Integer> l = new ArrayList<>(valores.length);
		for (int v : valores) {
			l.add(v);
		}
		return l;
	}

	// --- perfil --------------------------------------------------------------------------------

	private static WrappedPerfil perfil(WrappedMetricas m, List<WrappedAssunto> assuntos) {
		List<Caracteristica> caracteristicas = new ArrayList<>();
		int atividadeFeita = m.publicacoes() + m.comentariosProprios() + m.interacoesFeitas();

		if (m.publicacoes() > 0 && m.comentariosFeitos() >= m.publicacoes()) {
			caracteristicas.add(new Caracteristica(Chave.DEBATEDOR,
					m.comentariosFeitos() + " comentários em " + m.publicacoes() + " publicações"));
		}
		int totalAssuntos = assuntos.stream().mapToInt(WrappedAssunto::ocorrencias).sum();
		if (!assuntos.isEmpty() && totalAssuntos > 0
				&& ((double) assuntos.get(0).ocorrencias() / totalAssuntos) >= CONCENTRACAO_ESPECIALISTA) {
			caracteristicas.add(new Caracteristica(Chave.ESPECIALISTA,
					"o assunto \"" + assuntos.get(0).expressao() + "\" apareceu "
							+ assuntos.get(0).ocorrencias() + " vezes"));
		}
		if (atividadeFeita > 0 && ((double) m.interacoesFeitas() / atividadeFeita) >= RAZAO_OBSERVADOR) {
			caracteristicas.add(new Caracteristica(Chave.OBSERVADOR,
					m.interacoesFeitas() + " interações feitas"));
		}
		if (m.publicacoes() > 0) {
			caracteristicas.add(new Caracteristica(Chave.CRIADOR, m.publicacoes() + " publicações"));
		}
		if (m.comunidadesParticipadas() > 0) {
			caracteristicas.add(new Caracteristica(Chave.COMUNITARIO,
					m.comunidadesParticipadas() + " comunidades"));
		}
		return new WrappedPerfil(caracteristicas.isEmpty() ? null : caracteristicas.get(0).chave(),
				caracteristicas);
	}

	// --- impacto -------------------------------------------------------------------------------

	private static WrappedImpacto impacto(List<WrappedEvento> eventos, WrappedMetricas m,
			List<WrappedComunidade> comunidades) {
		// comentários da conta que receberam pelo menos uma reação de outra pessoa.
		Set<String> comentariosComReacao = new HashSet<>();
		for (WrappedEvento e : eventos) {
			if (e.tipo() == Tipo.REACAO_RECEBIDA_COMENTARIO) {
				// postId carrega o comment_id neste evento.
				comentariosComReacao.add(e.postId());
			}
		}
		// publicações que geraram conversa: próprias com algum comentário recebido
		Set<String> comComentario = new HashSet<>();
		for (WrappedEvento e : eventos) {
			if (e.tipo() == Tipo.COMENTARIO_RECEBIDO) {
				comComentario.add(e.postId());
			}
		}
		int publicacoesComConversa = 0;
		for (WrappedEvento e : eventos) {
			if (e.tipo() == Tipo.PUBLICACAO && comComentario.contains(e.postId())) {
				publicacoesComConversa++;
			}
		}
		return new WrappedImpacto(publicacoesComConversa, comentariosComReacao.size(),
				m.interacoesRecebidas(), comunidades.size(), 0);
	}

	private static List<WrappedComunidade> comunidadesPlenas(WrappedComunidadesRepository repo, String userId,
			WrappedAno ano) {
		List<WrappedComunidade> comunidades = new ArrayList<>();
		for (WrappedComunidadesRepository.Participacao p : repo.daConta(userId, ano.inicio(), ano.fim())) {
			comunidades.add(new WrappedComunidade(p.communityId(), p.nome(), p.publicacoes(), 1));
		}
		comunidades.sort(Comparator.comparingInt(WrappedComunidade::publicacoes).reversed()
				.thenComparing(WrappedComunidade::communityId));
		return comunidades;
	}

	// --- encerramento --------------------------------------------------------------------------

	private static WrappedEncerramento encerramento(List<WrappedAssunto> assuntos, WrappedPerfil perfil,
			WrappedMetricas metricas) {
		String tema = assuntos.isEmpty() ? null : assuntos.get(0).expressao();
		String estatistica = metricas.publicacoes() > 0
				? metricas.publicacoes() + " publicações, " + metricas.diasAtivos() + " dias de Énóis"
				: metricas.interacoesFeitas() + " interações";
		return new WrappedEncerramento(tema, perfil.principal(), estatistica);
	}

	// --- auxiliares ----------------------------------------------------------------------------

	private static <T> List<T> limitar(List<T> lista, int limite) {
		return lista.size() > limite ? new ArrayList<>(lista.subList(0, limite)) : lista;
	}

	private static String trecho(String texto) {
		if (texto == null) {
			return null;
		}
		String limpo = texto.replaceAll("\\s+", " ").trim();
		return limpo.length() <= TRECHO_MAX ? limpo : limpo.substring(0, TRECHO_MAX) + "…";
	}

	private static final class WrappedEscopo {
		private final List<WrappedEvento> eventos = new ArrayList<>();
	}

	private static final class PublicacaoAgregada {
		private WrappedEvento evento;
		private Boolean propria = false;
		private int engajamento;
		private int participacao;

		private PublicacaoAgregada(WrappedEvento evento) {
			this.evento = evento;
			if (evento != null && evento.autorId() == null) {
				this.propria = false;
			}
		}
	}

	private static final class MapaContato {
		private final String userId;
		private final String username;
		private final String nome;
		private int vezes;

		private MapaContato(String userId, String username, String nome) {
			this.userId = userId;
			this.username = username;
			this.nome = nome;
		}
	}
}
