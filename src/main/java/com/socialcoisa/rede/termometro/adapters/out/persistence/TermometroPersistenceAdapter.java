package com.socialcoisa.rede.termometro.adapters.out.persistence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.termometro.application.port.out.TermometroRepository;
import com.socialcoisa.rede.termometro.domain.CorteTermico;
import com.socialcoisa.rede.termometro.domain.TermometroPeriodo;

interface SpringDataTermometroFatiaJpaRepository extends JpaRepository<TermometroFatiaJpaEntity, String> {

	@Query("""
			select f from TermometroFatiaJpaEntity f
			where f.hora >= :de and f.hora < :ate
			order by f.hora asc
			""")
	List<TermometroFatiaJpaEntity> fatiasEntre(@Param("de") Instant de, @Param("ate") Instant ate);

	@Query("select max(f.lidaEm) from TermometroFatiaJpaEntity f")
	Optional<Instant> maiorLeitura();

}

interface SpringDataTermometroCorteJpaRepository extends JpaRepository<TermometroCorteJpaEntity, String> {

	Optional<TermometroCorteJpaEntity> findByPeriodo(String periodo);

}

/**
 * Onde as fatias e os cortes do Termômetro dormem.
 *
 * A fatia recebe id sintético na gravação — a mesma pessoa pode publicar duas manifestações da
 * mesma emoção na mesma hora, e ambas contam, então (hora, fonte, emoção, autor) não é chave.
 * O corte é `save` por cima da linha da janela: reescrever é o comportamento, não efeito
 * colateral. O JSON das contagens nasce no domínio e volta para ele na leitura; o adaptador
 * não interpreta nada.
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
		List<TermometroFatiaJpaEntity> entidades = new ArrayList<>();
		for (Fatia fatia : novas) {
			entidades.add(new TermometroFatiaJpaEntity(
					PREFIXO_FATIA + UUID.randomUUID().toString().replace("-", ""), fatia.hora(),
					fatia.fonte(), fatia.emocao(), fatia.autorId(), fatia.lidaEm()));
		}
		fatias.saveAll(entidades);
	}

	@Override
	public List<Fatia> fatiasEntre(Instant de, Instant ate) {
		return fatias.fatiasEntre(de, ate).stream()
			.map(entidade -> new Fatia(entidade.hora(), entidade.fonte(),
					entidade.emocao(), entidade.autorId(), entidade.lidaEm()))
			.toList();
	}

	@Override
	public Optional<Instant> ultimaFronteira() {
		return fatias.maiorLeitura();
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
