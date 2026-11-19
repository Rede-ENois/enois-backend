package com.socialcoisa.rede.colecoes.adapters.out.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.colecoes.application.port.out.ColecoesRepository;
import com.socialcoisa.rede.colecoes.domain.ColecaoPost;
import com.socialcoisa.rede.colecoes.domain.ColecaoSalva;
import com.socialcoisa.rede.feed.domain.UserId;

@Component
@Transactional
class ColecoesPersistenceAdapter implements ColecoesRepository {

	private final SpringDataColecaoJpaRepository colecoes;
	private final SpringDataColecaoPostJpaRepository posts;

	ColecoesPersistenceAdapter(SpringDataColecaoJpaRepository colecoes, SpringDataColecaoPostJpaRepository posts) {
		this.colecoes = colecoes;
		this.posts = posts;
	}

	@Override
	public ColecaoSalva saveColecao(ColecaoSalva colecao) {
		return colecoes.save(new ColecaoSalvaJpaEntity(colecao)).toDomain();
	}

	@Override
	public Optional<ColecaoSalva> findColecaoById(String id) {
		return colecoes.findById(id).map(ColecaoSalvaJpaEntity::toDomain);
	}

	@Override
	public List<ColecaoSalva> findByOwnerBefore(UserId ownerId, Instant before, int limit) {
		return colecoes.doDonoBefore(ownerId.value(), before, PageRequest.of(0, limit)).stream()
			.map(ColecaoSalvaJpaEntity::toDomain)
			.toList();
	}

	@Override
	public List<ColecaoSalva> findAllByOwner(UserId ownerId) {
		return colecoes.findByOwnerIdOrderByCreatedAtDesc(ownerId.value()).stream()
			.map(ColecaoSalvaJpaEntity::toDomain)
			.toList();
	}

	@Override
	public ColecaoPost savePost(ColecaoPost elo) {
		return posts.save(new ColecaoPostJpaEntity(elo)).toDomain();
	}

	@Override
	public Optional<ColecaoPost> findPost(String colecaoId, String postId) {
		return posts.findById(ColecaoPost.idDe(colecaoId, postId)).map(ColecaoPostJpaEntity::toDomain);
	}

	@Override
	public Collection<String> findColecaoIdsComPost(String postId, Collection<String> colecaoIds) {
		if (colecaoIds.isEmpty()) {
			return List.of();
		}
		return posts.peloPostNasColecoes(postId, colecaoIds).stream().map(linha -> (String) linha[0]).toList();
	}

	@Override
	public List<ColecaoPost> findPostsByColecao(String colecaoId, Instant before, int limit) {
		return posts.daColecaoBefore(colecaoId, before, PageRequest.of(0, limit)).stream()
			.map(ColecaoPostJpaEntity::toDomain)
			.toList();
	}

	@Override
	public Map<String, Long> countPosts(Collection<String> colecaoIds) {
		return posts.countByColecaoIdIn(colecaoIds).stream()
			.collect(Collectors.toMap(linha -> (String) linha[0], linha -> (Long) linha[1]));
	}

	@Override
	public void deletePost(String id) {
		posts.deleteById(id);
	}

	@Override
	public void deletePostsByColecao(String colecaoId) {
		posts.deleteByColecaoId(colecaoId);
	}

	@Override
	public void deleteColecao(String id) {
		colecoes.deleteById(id);
	}
}
