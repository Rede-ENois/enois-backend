package com.socialcoisa.rede.termometro.adapters.out.persistence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.termometro.application.port.out.ManifestacaoReader;
import com.socialcoisa.rede.termometro.application.port.out.TermometroRepository;
import com.socialcoisa.rede.termometro.domain.CorteTermico;
import com.socialcoisa.rede.termometro.domain.TermometroPeriodo;

interface SpringDataTermometroFatiaJpaRepository extends JpaRepository<TermometroFatiaJpaEntity, String> {

	@Query("""
			select f from TermometroFatiaJpaEntity f
			where f.hora >= :de and f.hora < :ate
			order by f.hora asc, f.id asc
			""")
	List<TermometroFatiaJpaEntity> fatiasEntre(@Param("de") Instant de, @Param("ate") Instant ate);

	@Query("""
			select f from TermometroFatiaJpaEntity f
			where f.hora >= :de and f.hora < :ate and f.publicacaoId is null
			""")
	List<TermometroFatiaJpaEntity> semPublicacaoEntre(@Param("de") Instant de, @Param("ate") Instant ate);

	@Query("""
			select f from TermometroFatiaJpaEntity f
			where f.fonte = :fonte and f.publicacaoId = :publicacaoId
			""")
	List<TermometroFatiaJpaEntity> daPublicacao(@Param("fonte") String fonte,
			@Param("publicacaoId") String publicacaoId);

}

interface SpringDataTermometroCorteJpaRepository extends JpaRepository<TermometroCorteJpaEntity, String> {

	Optional<TermometroCorteJpaEntity> findByPeriodo(String periodo);

}

/**
 * Onde as fatias e os cortes do Termômetro dormem.
 *
 * A fatia nasce com id sintético, mas quem manda é a chave natural (fonte, publicação): a mesma
 * manifestação lida de novo ATUALIZA a linha que já existe — sem isso, reler o horizonte de 31
 * dias inflava a contagem da janela a cada recomeço. A linha antiga, gravada antes da coluna,
 * casa pela hora do bucket (adota o id da publicação na primeira vez que for relida). A fatia
 * de publicação que a releitura achou SEM texto (apagada, desativada, editada) sai: o corte não
 * conta o que não está mais no ar. Na janela recém-lida, só sai a fatia que a leitura ATUAL releu
 * vazia — a que a leitura pulou (já contada) continua contando, e a que só sumiu da consulta
 * (removida do banco) expira com a janela. O corte é `save` por cima da linha da janela:
 * reescrever é o comportamento, não efeito colateral.
 */
@Component
@Transactional
public class TermometroPersistenceAdapter implements TermometroRepository {

	private static final String PREFIXO_FATIA = "termo_f_";

	private final SpringDataTermometroFatiaJpaRepository fatias;

	private final SpringDataTermometroCorteJpaRepository cortes;

	TermometroPersistenceAdapter(SpringDataTermometroFatiaJpaRepository fatias,
			SpringDataTermometroCorteJpaRepository cortes) {
		this.fatias = fatias;
		this.cortes = cortes;
	}

	@Override
	public void salvarFatias(List<Fatia> novas) {
		if (novas.isEmpty()) {
			return;
		}
		List<TermometroFatiaJpaEntity> paraGravar = new ArrayList<>();
		List<TermometroFatiaJpaEntity> paraApagar = new ArrayList<>();
		for (Fatia fatia : novas) {
			List<TermometroFatiaJpaEntity> existentes = fatias.daPublicacao(fatia.fonte(), fatia.publicacaoId());
			if (!existentes.isEmpty()) {
				// O update é descritor: a linha da gravação anterior já não pertence a esta sessão.
				existentes.get(0).atualizar(fatia.hora(), fatia.emocao(), fatia.autorId(), fatia.lidaEm());
				paraGravar.add(existentes.get(0));
				// Duplicata de uma leitura anterior sem chave: a linha extra sai.
				paraApagar.addAll(existentes.subList(1, existentes.size()));
				continue;
			}
			List<TermometroFatiaJpaEntity> herdeiras = legadoDaMesmaHora(fatia);
			if (!herdeiras.isEmpty()) {
				// Linha gravada antes da coluna: a releitura adota o id da publicação.
				herdeiras.get(0).adotar(fatia.publicacaoId(), fatia.hora(), fatia.emocao(), fatia.autorId(),
						fatia.lidaEm());
				paraGravar.add(herdeiras.get(0));
				paraApagar.addAll(herdeiras.subList(1, herdeiras.size()));
				continue;
			}
			paraGravar.add(new TermometroFatiaJpaEntity(
					PREFIXO_FATIA + UUID.randomUUID().toString().replace("-", ""), fatia.hora(), fatia.fonte(),
					fatia.emocao(), fatia.autorId(), fatia.publicacaoId(), fatia.lidaEm()));
		}
		if (!paraApagar.isEmpty()) {
			fatias.deleteAll(paraApagar);
		}
		fatias.saveAll(paraGravar);
	}

	/**
	 * A fatia gravada antes da coluna `publicacao_id` não tem como ser reconhecida pela
	 * publicação: o que a reconhece é o bucket (hora, fonte, autor, emoção). Ela é adotada uma vez
	 * pela leitura seguinte — e adota o id da publicação a partir daí.
	 */
	private List<TermometroFatiaJpaEntity> legadoDaMesmaHora(Fatia fatia) {
		return fatias.semPublicacaoEntre(fatia.hora(), fatia.hora().plusSeconds(3600))
			.stream()
			.filter(linha -> linha.fonte().equals(fatia.fonte()) && linha.autorId().equals(fatia.autorId())
					&& linha.emocao().equals(fatia.emocao()))
			.toList();
	}

	@Override
	public List<Fatia> fatiasEntre(Instant de, Instant ate) {
		return fatias.fatiasEntre(de, ate).stream()
			.map(entidade -> new Fatia(entidade.hora(), entidade.fonte(),
					entidade.emocao(), entidade.autorId(), entidade.publicacaoId(), entidade.lidaEm()))
			.toList();
	}

	@Override
	public Optional<Instant> leituraDaFatia(String fonte, String publicacaoId) {
		return fatias.daPublicacao(fonte, publicacaoId).stream()
			.map(TermometroFatiaJpaEntity::lidaEm)
			.max(Instant::compareTo);
	}

	@Override
	public void removerFatiasDaJanela(Instant de, Instant ate, List<ManifestacaoReader.Manifestacao> lidas) {
		List<String> vazias = lidas.stream()
			.filter(manifestacao -> manifestacao.texto() == null || manifestacao.texto().isBlank())
			.map(ManifestacaoReader.Manifestacao::publicacaoId)
			.toList();
		if (!vazias.isEmpty()) {
			List<TermometroFatiaJpaEntity> orfas = fatias.fatiasEntre(de, ate)
				.stream()
				.filter(linha -> vazias.contains(linha.publicacaoId()))
				.toList();
			fatias.deleteAll(orfas);
		}
	}

	@Override
	public void salvarCorte(CorteTermico corte) {
		cortes.save(TermometroCorteJpaEntity.de(corte));
	}

	@Override
	public Optional<CorteTermico> corteDe(TermometroPeriodo periodo) {
		return cortes.findByPeriodo(periodo.name()).map(TermometroCorteJpaEntity::toDomain);
	}

}
