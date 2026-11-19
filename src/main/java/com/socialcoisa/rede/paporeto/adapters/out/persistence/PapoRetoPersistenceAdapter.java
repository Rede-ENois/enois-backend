package com.socialcoisa.rede.paporeto.adapters.out.persistence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.paporeto.application.port.out.PapoRetoRepository;
import com.socialcoisa.rede.paporeto.domain.PapoAssunto;
import com.socialcoisa.rede.paporeto.domain.PapoIntervalo;
import com.socialcoisa.rede.paporeto.domain.PapoTemaFala;

/**
 * Onde as falas, os agregados e os intervalos do Papo Reto dormem.
 *
 * A fala recebe id sintético na gravação (a mesma pessoa pode repetir a expressão em
 * publicações distintas e ambas contam — o chão é de PESSOAS distintas). O agregado do escopo
 * é REESCRITO por completo a cada corte: primeiro as linhas vivas, depois a limpeza das que
 * saíram. A timeline é RECONCILIADA por escopo: o corte manda a lista completa e o adaptador
 * substitui a gravada — o mesmo bucket volta sempre ao mesmo número, e conteúdo removido sai
 * da série na passagem seguinte.
 */
@Component
@Transactional
public class PapoRetoPersistenceAdapter implements PapoRetoRepository {

	private static final Logger LOG = LoggerFactory.getLogger(PapoRetoPersistenceAdapter.class);

	private static final String PREFIXO_FALA = "papo_f_";

	/** Por página de leitura; o corte normal lê a janela de 7 dias de falas em poucas páginas. */
	private static final int PAGINA = 2000;

	/**
	 * O freio de segurança da leitura da janela: a 2000 falas por página, o corte lê até 400 mil
	 * falas — muito acima do volume da rede. É um TETO DE PROTEÇÃO, não um recorte de negócio: se
	 * ele for atingido, o agregado sai incompleto e o corte grita no log.
	 */
	private static final int PAGINAS_MAXIMAS = 200;

	private final SpringDataPapoFalaJpaRepository falas;

	private final SpringDataPapoAssuntoJpaRepository assuntos;

	private final SpringDataPapoIntervaloJpaRepository intervalos;

	PapoRetoPersistenceAdapter(SpringDataPapoFalaJpaRepository falas,
			SpringDataPapoAssuntoJpaRepository assuntos, SpringDataPapoIntervaloJpaRepository intervalos) {
		this.falas = falas;
		this.assuntos = assuntos;
		this.intervalos = intervalos;
	}

	@Override
	public void salvarFalas(List<Fala> novas) {
		if (novas.isEmpty()) {
			return;
		}
		List<PapoFalaJpaEntity> entidades = new ArrayList<>();
		for (Fala fala : novas) {
			entidades.add(new PapoFalaJpaEntity(PREFIXO_FALA + UUID.randomUUID().toString().replace("-", ""),
					fala.hora(), fala.expressao(), fala.fonte(), fala.autorId(), fala.communityId(),
					fala.publicacaoId(), fala.lidaEm()));
		}
		falas.saveAll(entidades);
	}

	/**
	 * As falas da janela, em páginas, da mais antiga para a mais nova. A leitura vai até o fim
	 * da janela (a página curta) e para no freio de segurança, avisando no log quando para por
	 * ele: antes, a leitura parava em dez páginas em silêncio e as falas mais NOVAS ficavam de
	 * fora — o agregado perdia justamente a matéria da recência.
	 */
	@Override
	public List<Fala> falasEntre(Instant de, Instant ate) {
		List<Fala> lidas = new ArrayList<>();
		Set<String> idsVistos = new HashSet<>();
		for (int pagina = 0; pagina < PAGINAS_MAXIMAS; pagina++) {
			List<PapoFalaJpaEntity> lote = falas.falasEntre(de, ate, PageRequest.of(pagina, PAGINA));
			for (PapoFalaJpaEntity entidade : lote) {
				Fala fala = new Fala(entidade.hora(), entidade.expressao(), entidade.fonte(), entidade.autorId(),
						entidade.communityId(), entidade.publicacaoId(), entidade.lidaEm());
				if (idsVistos.add(fala.publicacaoId() + "|" + fala.expressao())) {
					lidas.add(fala);
				}
			}
			if (lote.size() < PAGINA) {
				return lidas;
			}
		}
		LOG.warn("Leitura da janela do Papo Reto parou no teto de {} páginas ({} falas lidas): o agregado "
				+ "sai incompleto e pede janela menor ou teto maior", PAGINAS_MAXIMAS, lidas.size());
		return lidas;
	}

	@Override
	public List<PapoTemaFala> temasDePublicacao(String publicacaoId) {
		return falas.expressoesDePublicacao(publicacaoId)
			.stream()
			.map(expressao -> new PapoTemaFala(expressao, null))
			.toList();
	}

	@Override
	public Optional<Instant> ultimaFronteira() {
		return falas.maiorLeitura();
	}

	@Override
	public void salvarAssuntos(String escopo, List<PapoAssunto> novos) {
		for (PapoAssunto assunto : novos) {
			assuntos.save(PapoAssuntoJpaEntity.de(escopo, assunto));
		}
	}

	@Override
	public List<PapoAssunto> assuntosDoEscopo(String escopo) {
		List<PapoAssunto> lidos = new ArrayList<>();
		for (PapoAssuntoJpaEntity entidade : assuntos.findByEscopo(escopo)) {
			lidos.add(entidade.toDomain());
		}
		return lidos;
	}

	@Override
	public Optional<PapoAssunto> assuntoDe(String escopo, String subject) {
		return assuntos.findByEscopo(escopo)
			.stream()
			.filter(entidade -> entidade.toDomain().subject().equals(subject))
			.map(PapoAssuntoJpaEntity::toDomain)
			.findFirst();
	}

	@Override
	public void apagarAssuntosSemFala(String escopo) {
		List<String> zerados = new ArrayList<>();
		for (PapoAssuntoJpaEntity entidade : assuntos.findByEscopo(escopo)) {
			PapoAssunto assunto = entidade.toDomain();
			if (assunto.falasPost() + assunto.falasComentario() == 0) {
				zerados.add(assunto.subject());
			}
		}
		if (!zerados.isEmpty()) {
			assuntos.deleteByEscopoAndSubjectIn(escopo, zerados);
		}
	}

	@Override
	public void reconciliarIntervalos(String escopo, List<PapoIntervalo> contagemDoCorte) {
		// A substituição completa do escopo: apaga o que estava gravado e grava a lista do
		// corte. O mesmo bucket volta sempre ao mesmo número — é esta propriedade que permite
		// corrigir a série quando conteúdo é removido.
		intervalos.deleteByEscopo(escopo);
		intervalos.flush();
		Map<String, PapoIntervaloJpaEntity> porBucket = new HashMap<>();
		for (PapoIntervalo intervalo : contagemDoCorte) {
			porBucket.put(intervalo.subject() + "@" + intervalo.hora(),
					new PapoIntervaloJpaEntity(escopo, intervalo.subject(), intervalo.hora(), intervalo.falas()));
		}
		intervalos.saveAll(porBucket.values());
	}

	@Override
	public List<PapoIntervalo> intervalosDe(String escopo, String subject, Instant de, Instant ate) {
		List<PapoIntervalo> lidos = new ArrayList<>();
		for (PapoIntervaloJpaEntity entidade : intervalos.intervalosDe(escopo, subject, de, ate)) {
			lidos.add(new PapoIntervalo(entidade.escopo(), entidade.subject(), entidade.hora(), entidade.falas()));
		}
		return lidos;
	}

	@Override
	public List<PublicacaoDaFala> publicacoesDoAssunto(String escopo, String subject, Instant de, Instant ate,
			int limite) {
		List<PublicacaoDaFala> lidas = new ArrayList<>();
		for (PapoPublicacaoProjecao linha : falas.publicacoesDoAssunto(escopo, subject, de, ate,
				PageRequest.of(0, Math.max(1, limite)))) {
			lidas.add(new PublicacaoDaFala(linha.getPublicacaoId(), linha.getFonte(), linha.getCommunityId(),
					linha.getHora()));
		}
		return lidas;
	}

	@Override
	public long falasTotais(Instant de, Instant ate) {
		return falas.falasTotais(de, ate);
	}

}
