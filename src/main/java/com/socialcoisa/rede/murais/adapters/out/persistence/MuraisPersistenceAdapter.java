package com.socialcoisa.rede.murais.adapters.out.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.murais.application.port.out.MuraisRepository;
import com.socialcoisa.rede.murais.domain.Mural;
import com.socialcoisa.rede.murais.domain.MuralContribuicao;
import com.socialcoisa.rede.murais.domain.MuralStatus;

@Component
@Transactional
class MuraisPersistenceAdapter implements MuraisRepository {

	private final SpringDataMuralJpaRepository murais;
	private final SpringDataMuralContribuicaoJpaRepository contribuicoes;

	MuraisPersistenceAdapter(SpringDataMuralJpaRepository murais,
			SpringDataMuralContribuicaoJpaRepository contribuicoes) {
		this.murais = murais;
		this.contribuicoes = contribuicoes;
	}

	@Override
	public Mural saveMural(Mural mural) {
		return murais.save(new MuralJpaEntity(mural)).toDomain();
	}

	@Override
	public Optional<Mural> findMuralById(String id) {
		return murais.findById(id).map(MuralJpaEntity::toDomain);
	}

	@Override
	public List<Mural> findActiveMuraisBefore(Instant before, int limit) {
		return murais.ativosBefore(MuralStatus.ACTIVE, before, PageRequest.of(0, limit)).stream()
			.map(MuralJpaEntity::toDomain)
			.toList();
	}

	@Override
	public List<Mural> findActiveMuraisByOwner(com.socialcoisa.rede.feed.domain.UserId ownerId) {
		return murais.ativosDoDono(ownerId.value()).stream().map(MuralJpaEntity::toDomain).toList();
	}

	@Override
	public MuralContribuicao saveContribuicao(MuralContribuicao contribuicao) {
		return contribuicoes.save(new MuralContribuicaoJpaEntity(contribuicao)).toDomain();
	}

	@Override
	public Optional<MuralContribuicao> findContribuicao(String muralId, String postId) {
		return contribuicoes.findById(MuralContribuicao.idDe(muralId, postId)).map(MuralContribuicaoJpaEntity::toDomain);
	}

	@Override
	public Collection<String> findMuralIdsComPost(String postId, Collection<String> muralIds) {
		if (muralIds.isEmpty()) {
			return List.of();
		}
		return contribuicoes.peloPostNosMurais(postId, muralIds).stream()
			.map(linha -> (String) linha[0])
			.toList();
	}

	@Override
	public List<MuralContribuicao> findContribuicoesByMural(String muralId, Instant before, int limit) {
		return contribuicoes.doMuralBefore(muralId, before, PageRequest.of(0, limit)).stream()
			.map(MuralContribuicaoJpaEntity::toDomain)
			.toList();
	}

	@Override
	public Map<String, Long> countContribuicoes(Collection<String> muralIds) {
		return contribuicoes.countByMuralIdIn(muralIds).stream()
			.collect(Collectors.toMap(linha -> (String) linha[0], linha -> (Long) linha[1]));
	}

	@Override
	public boolean deleteContribuicao(String id) {
		if (!contribuicoes.existsById(id)) {
			return false;
		}
		contribuicoes.deleteById(id);
		return true;
	}
}
