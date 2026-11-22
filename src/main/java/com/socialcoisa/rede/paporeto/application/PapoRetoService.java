package com.socialcoisa.rede.paporeto.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.socialcoisa.rede.paporeto.application.port.out.PapoAutorReader;
import com.socialcoisa.rede.paporeto.application.port.out.PapoPublicacaoReader;
import com.socialcoisa.rede.paporeto.application.port.out.PapoRetoRepository;
import com.socialcoisa.rede.paporeto.domain.PapoAssunto;
import com.socialcoisa.rede.paporeto.domain.PapoIntervalo;
import com.socialcoisa.rede.paporeto.domain.PapoStatus;
import com.socialcoisa.rede.shared.blocks.BlockedUsersReader;

/**
 * A leitura do Papo Reto para a página: o ranking dos assuntos em alta (global e por
 * comunidade), a timeline de um subject e as PUBLICAÇÕES RELACIONADAS de um assunto.
 *
 * O ranking e a timeline não carregam nada de individual: subject agregado, contagens do
 * conjunto e série por bucket — o que o worker agregou é o teto da tela. As publicações
 * relacionadas são a origem do assunto, e por isso trazem o texto e a autoria da publicação
 * pública: é o mesmo conteúdo que já está no feed (ou nos espaços anônimos), com as regras de
 * exibição deles valendo aqui — bloqueio do visitante esconde, desabafo e confissão não
 * revelam autor.
 *
 * A pontuação do ranking NÃO é recalculada aqui: ela vem medida do corte, junto com a potência
 * da véspera — é essa diferença de verdade que dá o status e o crescimento. A leitura só julga
 * o que já está gravado, e por três réguas: o chão de pessoas distintas, a palavra onipresente
 * (que o corte marcou como fala comum da rede) e a régua do extrator — assunto que não nomeia
 * conversa (o "http" que entrou antes da correção) não aparece no ranking nem abre publicações,
 * sem esperar a janela de 7 dias expirar para a tela ficar limpa.
 */
@Component
public class PapoRetoService {

	/**
	 * O assunto pronto para a tela: subject, status de trajetória, pontuação e números. O
	 * crescimento é nulo quando o assunto não tem véspera comparável (novo em alta) — a tela
	 * diz "novo", não inventa percentual.
	 */
	public record SubjectView(String subject, String status, double pontuacao, long falasPost,
			long falasComentario, long pessoas, long falasUltimaHora, Double crescimento) {
	}

	/** O ponto da timeline: bucket de hora e quantas falas o assunto teve ali. */
	public record IntervaloView(String hora, long falas) {
	}

	/**
	 * Uma publicação relacionada ao assunto: o id público, a fonte, a prévia do texto (sem
	 * formatação), quem assinou (nulo em espaço anônimo e para conta sancionada), o @ quando
	 * houver, o instante ISO e o caminho para abrir a publicação.
	 */
	public record PublicacaoRelacionadaView(String id, String fonte, String texto, String autor, String handle,
			String criadoEm, String href, boolean anonima) {
	}

	/** Quantas publicações relacionadas a tela mostra (o painel é lista curta, não feed). */
	private static final int LIMITE_PUBLICACOES = 20;

	/** O teto da prévia: o painel mostra o começo da publicação, não o texto inteiro. */
	private static final int TAMANHO_PREVIA = 280;

	/** Fontes em que o autor NUNCA aparece: o anonimato é do espaço, não da tela. */
	private static final Set<String> FONTES_ANONIMAS = Set.of("DESABAFO", "CONFISSAO");

	private final PapoRetoRepository repository;

	private final PapoRetoWorker worker;

	private final PapoAssuntoExtractor extractor;

	private final List<PapoPublicacaoReader> fontes;

	private final PapoAutorReader autoresDosPerfis;

	private final BlockedUsersReader bloqueios;

	private final Clock clock;

	PapoRetoService(PapoRetoRepository repository, PapoRetoWorker worker, PapoAssuntoExtractor extractor,
			List<PapoPublicacaoReader> fontes, PapoAutorReader autoresDosPerfis, BlockedUsersReader bloqueios,
			Clock clock) {
		this.repository = repository;
		this.worker = worker;
		this.extractor = extractor;
		this.fontes = fontes;
		this.autoresDosPerfis = autoresDosPerfis;
		this.bloqueios = bloqueios;
		this.clock = clock;
	}

	/** O ranking global: assuntos do feed geral E das comunidades, com o chão de gente. */
	public List<SubjectView> ranking() {
		return rankingDe(PapoRetoWorker.ESCOPO_GLOBAL);
	}

	/**
	 * O ranking da comunidade: o agregado do escopo da comunidade — só o que foi falado lá.
	 * O mesmo assunto pode estar nos dois rankings com números diferentes: no global ele soma
	 * toda a rede, na comunidade só a conversa dela.
	 */
	public List<SubjectView> rankingDaComunidade(String communityId) {
		return rankingDe(communityId);
	}

	private List<SubjectView> rankingDe(String escopo) {
		Instant agora = clock.instant();
		Instant antes = agora.minus(PapoAssunto.MEIA_VIDA);

		// A ORDEM sai da pontuação (a potência já ajustada pela discriminação); a TRAJETÓRIA sai
		// da potência crua das duas pontas — são perguntas diferentes ("o que é relevante agora"
		// e "como isso está se movendo") e por isso os dois números não se misturam.
		record Classificado(PapoAssunto assunto, double pontuacao, double potencia, double potenciaAntes) {
		}
		List<Classificado> classificados = new ArrayList<>();
		for (PapoAssunto assunto : repository.assuntosDoEscopo(escopo)) {
			if (assunto.pessoas() < PapoAssunto.MINIMO_PESSOAS || assunto.onipresente()
					|| !extractor.assuntoValido(assunto.subject())) {
				continue;
			}
			// A linha do corte já traz a pontuação e a potência da véspera medidas pelo worker.
			// A linha gravada antes da virada ainda não tem as duas: ela é pontuada pela régua
			// antiga até o primeiro corte reescrevê-la.
			double pontuacao;
			double potencia;
			double potenciaAntes;
			if (assunto.legado()) {
				pontuacao = worker.pontuacaoLegadaDe(assunto, agora);
				potencia = pontuacao;
				potenciaAntes = worker.pontuacaoLegadaDe(assunto, antes);
			}
			else {
				pontuacao = assunto.pontuacao();
				potencia = assunto.potencia();
				potenciaAntes = assunto.potenciaAntes();
			}
			classificados.add(new Classificado(assunto, pontuacao, potencia, potenciaAntes));
		}

		List<SubjectView> ranking = new ArrayList<>();
		for (Classificado classificado : classificados) {
			PapoStatus status = PapoStatus.de(classificado.potencia(), classificado.potenciaAntes());
			ranking.add(new SubjectView(classificado.assunto().subject(), status.name(), classificado.pontuacao(),
					classificado.assunto().falasPost(), classificado.assunto().falasComentario(),
					classificado.assunto().pessoas(), classificado.assunto().falasUltimaHora(),
					crescimento(classificado.assunto().falasDia(), classificado.assunto().falasDiaAnterior())));
		}
		ranking.sort((a, b) -> Double.compare(b.pontuacao(), a.pontuacao()) != 0
				? Double.compare(b.pontuacao(), a.pontuacao()) : a.subject().compareToIgnoreCase(b.subject()));
		return ranking;
	}

	/** A timeline do subject no escopo: os buckets da janela, do mais antigo ao mais recente. */
	public List<IntervaloView> timeline(String escopo, String subject, Duration janela) {
		if (!extractor.assuntoValido(subject)) {
			return List.of();
		}
		Instant agora = clock.instant();
		Instant de = agora.minus(janela);
		List<IntervaloView> timeline = new ArrayList<>();
		for (PapoIntervalo intervalo : repository.intervalosDe(escopo, subject, de, agora)) {
			timeline.add(new IntervaloView(intervalo.hora().toString(), intervalo.falas()));
		}
		// A ordem do tempo é promessa da leitura, não do armazenamento: o service garante.
		timeline.sort(java.util.Comparator.comparing(IntervaloView::hora));
		return timeline;
	}

	/**
	 * As publicações relacionadas do assunto no escopo e na janela: o texto de origem do
	 * assunto, da mais recente para a mais antiga. O visitante decide o recorte de bloqueio —
	 * o que ele bloqueou (ou quem o bloqueou) não aparece. Publicação apagada ou que deixou de
	 * ser pública simplesmente não volta; a contagem agregada continua sendo a do ranking.
	 */
	public List<PublicacaoRelacionadaView> publicacoesRelacionadas(String escopo, String subject, Duration janela,
			String viewerId) {
		if (!extractor.assuntoValido(subject)) {
			return List.of();
		}
		Instant agora = clock.instant();
		List<PapoRetoRepository.PublicacaoDaFala> referencias = repository.publicacoesDoAssunto(escopo, subject,
				agora.minus(janela), agora, LIMITE_PUBLICACOES);
		if (referencias.isEmpty()) {
			return List.of();
		}

		Map<String, PapoPublicacaoReader> leitoresPorFonte = new HashMap<>();
		for (PapoPublicacaoReader fonte : fontes) {
			leitoresPorFonte.put(fonte.fonte(), fonte);
		}
		Map<String, List<String>> idsPorFonte = new HashMap<>();
		for (PapoRetoRepository.PublicacaoDaFala referencia : referencias) {
			if (leitoresPorFonte.containsKey(referencia.fonte())) {
				idsPorFonte.computeIfAbsent(referencia.fonte(), chave -> new ArrayList<>())
					.add(referencia.publicacaoId());
			}
		}
		Map<String, PapoPublicacaoReader.Detalhe> detalhesPorId = new HashMap<>();
		for (Map.Entry<String, List<String>> lote : idsPorFonte.entrySet()) {
			for (PapoPublicacaoReader.Detalhe detalhe : leitoresPorFonte.get(lote.getKey()).detalhes(lote.getValue())) {
				detalhesPorId.put(detalhe.publicacaoId(), detalhe);
			}
		}

		Set<String> escondidos = bloqueios.hiddenIdsFor(viewerId);
		Set<String> autores = new HashSet<>();
		for (PapoPublicacaoReader.Detalhe detalhe : detalhesPorId.values()) {
			autores.add(detalhe.autorId());
		}
		Set<String> banidos = autoresDosPerfis.banidos(autores);
		Map<String, PapoAutorReader.Autor> perfis = autoresDosPerfis.autores(autores);

		List<PublicacaoRelacionadaView> relacionadas = new ArrayList<>();
		for (PapoRetoRepository.PublicacaoDaFala referencia : referencias) {
			PapoPublicacaoReader.Detalhe detalhe = detalhesPorId.get(referencia.publicacaoId());
			if (detalhe == null || escondidos.contains(detalhe.autorId())) {
				continue;
			}
			boolean anonima = FONTES_ANONIMAS.contains(referencia.fonte());
			// Banido não tem nome na tela: a conta some da apresentação, como no resto do feed.
			boolean semNome = anonima || banidos.contains(detalhe.autorId());
			PapoAutorReader.Autor autor = semNome ? null : perfis.get(detalhe.autorId());
			relacionadas.add(new PublicacaoRelacionadaView(referencia.publicacaoId(), referencia.fonte(),
					previa(detalhe.texto()), autor != null ? autor.nome() : null,
					autor != null ? autor.handle() : null, detalhe.criadoEm().toString(),
					hrefDe(referencia.fonte(), detalhe), anonima));
		}
		return relacionadas;
	}

	/** Quantas falas a última janela de corte analisou (a contagem que a página mostra). */
	public long falasAnalisadas() {
		Instant agora = clock.instant();
		return repository.falasTotais(agora.minus(Duration.ofDays(7)), agora);
	}

	/**
	 * O crescimento exibido é proporção de VOLUME real: as falas das últimas 24 horas contra as
	 * da véspera (as duas com teto por autor, medidas no corte). Sem véspera comparável, é
	 * nulo — o assunto é novo em alta, e não "+100%" inventado. Queda exibe estável (0).
	 */
	private Double crescimento(long dia, long diaAnterior) {
		if (diaAnterior > 0) {
			return Math.max(0.0, ((dia - diaAnterior) / (double) diaAnterior) * 100.0);
		}
		return dia > 0 ? null : 0.0;
	}

	/**
	 * O caminho para abrir a publicação de origem: post tem rota própria; comentário abre a
	 * discussão do post que o abriga; desabafo e confissão abrem o espaço (o item não tem rota
	 * pública por id, e o anonimato é do espaço).
	 */
	private static String hrefDe(String fonte, PapoPublicacaoReader.Detalhe detalhe) {
		return switch (fonte) {
			case "COMMENT" -> detalhe.postId() == null ? "/papo-reto" : "/post/" + detalhe.postId();
			case "DESABAFO" -> "/desabafos";
			case "CONFISSAO" -> "/confessionario";
			default -> "/post/" + detalhe.publicacaoId();
		};
	}

	/** A prévia do texto: espaço colapsado e corte no teto, sem quebrar par de surrogates. */
	private static String previa(String texto) {
		String limpo = texto.replaceAll("\\s+", " ").trim();
		if (limpo.length() <= TAMANHO_PREVIA) {
			return limpo;
		}
		int corte = TAMANHO_PREVIA - 1;
		if (Character.isHighSurrogate(limpo.charAt(corte - 1))) {
			corte--;
		}
		return limpo.substring(0, corte) + "…";
	}

}
