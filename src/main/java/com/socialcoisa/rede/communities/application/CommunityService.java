package com.socialcoisa.rede.communities.application;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.communities.application.port.out.CommunityRepository;
import com.socialcoisa.rede.communities.domain.Community;
import com.socialcoisa.rede.communities.domain.CommunityMember;
import com.socialcoisa.rede.communities.domain.MemberRole;
import com.socialcoisa.rede.feed.application.port.out.PostRepository;
import com.socialcoisa.rede.feed.domain.Post;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.shared.application.CacheConfig;
import com.socialcoisa.rede.shared.application.CacheInvalidator;
import com.socialcoisa.rede.shared.geo.CityState;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.TextFieldLimit;

@Service
public class CommunityService {

	private final CommunityRepository repository;
	private final PostRepository postRepository;
	private final com.socialcoisa.rede.shared.sanctions.SanctionGateReader sanctionGate;
	private final com.socialcoisa.rede.shared.security.SuspiciousText suspiciousText;
	private final Clock clock;
	private final com.socialcoisa.rede.coin.application.CoinService coins;
	private final CacheInvalidator cacheInvalidator;

	public CommunityService(CommunityRepository repository, PostRepository postRepository,
			com.socialcoisa.rede.shared.sanctions.SanctionGateReader sanctionGate,
			com.socialcoisa.rede.shared.security.SuspiciousText suspiciousText, Clock clock,
			com.socialcoisa.rede.coin.application.CoinService coins, CacheInvalidator cacheInvalidator) {
		this.coins = coins;
		this.repository = repository;
		this.postRepository = postRepository;
		this.sanctionGate = sanctionGate;
		this.suspiciousText = suspiciousText;
		this.clock = clock;
		this.cacheInvalidator = cacheInvalidator;
	}

	@Transactional
	public Community create(String name, String description, String topic, String cover, String avatar, String city,
			String state, String userId) {
		// Os tetos sao os das COLUNAS (`communities.name varchar(255)`, `description varchar(2000)`):
		// sem a checagem o banco recusava com "Data too long" e a pessoa levava 409 "conflita com o
		// estado atual dos dados".
		TextFieldLimit.check("COMMUNITY_FIELD_TOO_LONG", "nome", name, 255);
		TextFieldLimit.check("COMMUNITY_FIELD_TOO_LONG", "descricao", description, 2000);
		CityState place = CityState.of(city, state);
		String id = "community_" + UUID.randomUUID().toString().replace("-", "");
		// Descrição é opcional: em branco vira NULL (o banco guarda "sem descrição", não texto vazio).
		String finalDescription = org.springframework.util.StringUtils.hasText(description) ? description.trim()
			: null;
		Community community = Community.create(id, name, finalDescription, topic, cover, avatar, place, userId,
			clock.instant());
		repository.save(community);
		repository.saveMember(CommunityMember.join(id, userId, MemberRole.ADMIN, clock.instant()));
		cacheInvalidator.afterCommit(CacheConfig.COMMUNITIES);
		return community;
	}

	@Transactional
	public Community update(String communityId, String name, String description, String topic, String cover,
			String avatar, String bannerHtml, String city, String state, String userId) {
		Community community = repository.findById(communityId)
			.orElseThrow(() -> new ApiException(404, "COMMUNITY_NOT_FOUND", "Comunidade nao encontrada."));
		if (!community.createdBy().equals(userId)) {
			throw new ApiException(403, "FORBIDDEN", "So o criador pode atualizar a comunidade.");
		}
		TextFieldLimit.check("COMMUNITY_FIELD_TOO_LONG", "nome", name, 255);
		TextFieldLimit.check("COMMUNITY_FIELD_TOO_LONG", "descricao", description, 2000);
		String finalName = name != null ? name : community.name();
		String finalDesc = description != null ? description : community.description();
		String finalTopic = topic != null ? topic : community.topic();
		String finalCover = cover != null ? cover : community.cover();
		String finalAvatar = avatar != null ? avatar : community.avatar();
		// Capa em HTML: mesmo sanitizador da capa do perfil. Nulo = campo ausente (mantem); vazio =
		// limpa (volta para a foto); com texto = sanitiza e substitui.
		String finalBannerHtml = bannerHtml == null ? null
			: (org.springframework.util.StringUtils.hasText(bannerHtml)
				? com.socialcoisa.rede.shared.html.SafeHtml.requireProfileHtml(bannerHtml)
				: "");
		// Cidade/UF: os dois ausentes mantem; os dois vazios limpam; so um preenchido e 400.
		CityState finalPlace = city == null && state == null ? community.place() : CityState.of(city, state);
		Community updated = community.update(finalName, finalDesc, finalTopic, finalCover, finalAvatar,
			finalBannerHtml, finalPlace, clock.instant());
		repository.save(updated);
		cacheInvalidator.afterCommit(CacheConfig.COMMUNITIES);
		return updated;
	}

	@Transactional
	public CommunityMember join(String communityId, String userId) {
		// Without this an unknown id left a membership row pointing at nothing.
		repository.findById(communityId)
			.orElseThrow(() -> new ApiException(404, "COMMUNITY_NOT_FOUND", "Comunidade nao encontrada."));
		if (repository.isMember(communityId, userId)) {
			throw new ApiException(409, "ALREADY_MEMBER", "Voce ja e membro desta comunidade.");
		}
		CommunityMember member = CommunityMember.join(communityId, userId, MemberRole.MEMBER, clock.instant());
		repository.saveMember(member);
		coins.recordPresence(userId);
		// O total de membros aparece na vitrine: a entrada ja conta no proximo pedido.
		cacheInvalidator.afterCommit(CacheConfig.COMMUNITIES);
		return member;
	}

	/**
	 * Sair da comunidade. O dono NAO pode simplesmente sair enquanto houver outros membros: a
	 * propriedade e o que autoriza editar/excluir, entao ele precisa transferir antes (409
	 * OWNERSHIP_TRANSFER_REQUIRED). Antes, a saida do dono promovia alguem aleatorio a ADMIN, mas o
	 * novo admin nao conseguia editar (a checagem usa o dono) e quem saiu continuava no controle —
	 * estado inconsistente e inseguro. Ser o ultimo membro apaga a comunidade.
	 */
	@Transactional
	public void leave(String communityId, String userId) {
		Community community = repository.findById(communityId)
			.orElseThrow(() -> new ApiException(404, "COMMUNITY_NOT_FOUND", "Comunidade nao encontrada."));
		if (!repository.isMember(communityId, userId)) {
			throw new ApiException(404, "NOT_MEMBER", "Voce nao e membro desta comunidade.");
		}
		if (community.createdBy().equals(userId) && repository.countMembers(communityId) > 1) {
			throw new ApiException(409, "OWNERSHIP_TRANSFER_REQUIRED",
				"Transfira a administracao para outro membro antes de sair.");
		}
		repository.removeMember(communityId, userId);
		if (repository.countMembers(communityId) == 0) {
			repository.removeAllMembers(communityId);
			repository.delete(communityId);
		}
		cacheInvalidator.afterCommit(CacheConfig.COMMUNITIES);
	}

	/**
	 * Transfere a propriedade para outro membro. So o dono atual transfere; o novo dono vira ADMIN e
	 * o antigo passa a membro comum (se continuar na comunidade).
	 */
	@Transactional
	public Community transferOwnership(String communityId, String currentUserId, String newOwnerId) {
		Community community = repository.findById(communityId)
			.orElseThrow(() -> new ApiException(404, "COMMUNITY_NOT_FOUND", "Comunidade nao encontrada."));
		if (!community.createdBy().equals(currentUserId)) {
			throw new ApiException(403, "FORBIDDEN", "So o dono pode transferir a administracao.");
		}
		if (newOwnerId == null || newOwnerId.isBlank()) {
			throw new ApiException(400, "INVALID_NEW_OWNER", "Escolha quem vai administrar a comunidade.");
		}
		if (newOwnerId.equals(currentUserId)) {
			throw new ApiException(400, "INVALID_NEW_OWNER", "Voce ja e o administrador desta comunidade.");
		}
		if (!repository.isMember(communityId, newOwnerId)) {
			throw new ApiException(400, "NOT_MEMBER", "O novo administrador precisa ser membro da comunidade.");
		}
		Community transferred = community.transferOwnership(newOwnerId, clock.instant());
		repository.save(transferred);
		repository.updateMemberRole(communityId, newOwnerId, MemberRole.ADMIN);
		if (repository.isMember(communityId, currentUserId)) {
			repository.updateMemberRole(communityId, currentUserId, MemberRole.MEMBER);
		}
		return transferred;
	}

	/** Exclui a comunidade. So o dono; remove os vinculos de membros para nao deixar referencia orfa. */
	@Transactional
	public void deleteCommunity(String communityId, String userId) {
		deleteCommunity(communityId, userId, false);
	}

	/** Admin pode excluir comunidade de terceiros (moderacao de ataque/spam, ECA Digital). */
	public void deleteCommunity(String communityId, String userId, boolean adminOverride) {
		Community community = repository.findById(communityId)
			.orElseThrow(() -> new ApiException(404, "COMMUNITY_NOT_FOUND", "Comunidade nao encontrada."));
		if (!adminOverride && !community.createdBy().equals(userId)) {
			throw new ApiException(403, "FORBIDDEN", "So o dono pode excluir a comunidade.");
		}
		repository.removeAllMembers(communityId);
		repository.delete(communityId);
		cacheInvalidator.afterCommit(CacheConfig.COMMUNITIES);
	}

	/** Comunidades das quais o usuario participa (menu lateral). */
	public List<Community> listMine(String userId, int limit) {
		return repository.findByMember(userId, limit);
	}

	/** Favorita (salva) uma comunidade para acesso rapido. Nao exige participar dela. */
	@Transactional
	public void favorite(String communityId, String userId) {
		getById(communityId);
		repository.addFavorite(communityId, userId, clock.instant());
	}

	@Transactional
	public void unfavorite(String communityId, String userId) {
		repository.removeFavorite(communityId, userId);
	}

	public List<Community> listFavorites(String userId, int limit) {
		return repository.findFavorites(userId, limit);
	}

	/**
	 * Vitrine. `state` sozinho filtra pela UF; `city` exige `state` (o mesmo nome de cidade existe em
	 * varios estados). A cidade compara sem acento e sem caixa ("Sao Paulo" acha "São Paulo").
	 *
	 * <p>O resultado NAO depende de quem pede — o "sou membro" de cada leitor e aplicado depois, na
	 * camada web —, entao pode ficar alguns segundos em cache: abrir a vitrine, trocar de filtro e
	 * voltar custava a mesma varredura toda vez. A chave e o filtro + a paginacao + o cursor.</p>
	 */
	@Cacheable(cacheNames = CacheConfig.COMMUNITIES,
		key = "#topic + '|' + #query + '|' + #city + '|' + #state + '|' + #before + '|' + #limit", sync = true)
	public List<Community> list(String topic, String query, String city, String state, java.time.Instant before,
			int limit) {
		String uf = null;
		String cityKey = null;
		if (org.springframework.util.StringUtils.hasText(city)) {
			CityState place = CityState.of(city, state);
			uf = place.state();
			cityKey = place.cityKey();
		} else if (org.springframework.util.StringUtils.hasText(state)) {
			uf = CityState.requireState(state);
		}
		return visible(repository.findAll(topic, query, uf, cityKey, before, limit));
	}

	/**
	 * Comunidades perto de quem pede: as da mesma cidade primeiro, depois as do mesmo estado, cada
	 * grupo por numero de membros. Sem cidade no perfil, lista vazia (nada de chutar o lugar).
	 */
	public List<Community> listLocal(CityState viewerPlace, int limit) {
		if (viewerPlace == null) {
			return List.of();
		}
		return visible(repository.findLocal(viewerPlace.state(), viewerPlace.cityKey(), limit));
	}

	// Comunidade criada por conta banida sai da vitrine (o conteúdo dela já é limpo).
	private List<Community> visible(List<Community> communities) {
		return communities.stream()
			.filter(community -> !sanctionGate.banned(community.createdBy()))
			.filter(community -> !suspiciousText.matches(community.name(), community.description()))
			.toList();
	}

	public Community getById(String id) {
		return repository.findById(id)
			.orElseThrow(() -> new ApiException(404, "COMMUNITY_NOT_FOUND", "Comunidade nao encontrada."));
	}

	public boolean isMember(String communityId, String userId) {
		return repository.isMember(communityId, userId);
	}

	/**
	 * Fixa UM post no topo da comunidade. Só ADMIN (dono/administração) fixa; o post precisa ser
	 * daquela comunidade e estar ativo. Fixar outro substitui o anterior (a PK é a comunidade).
	 */
	@Transactional
	public String pinPost(String communityId, String postId, String userId) {
		getById(communityId);
		requireAdmin(communityId, userId);
		Post post = postRepository.findById(new PostId(postId))
			.filter(Post::isActive)
			.orElseThrow(() -> new ApiException(404, "POST_NOT_FOUND", "Post nao encontrado."));
		if (!communityId.equals(post.communityId())) {
			throw new ApiException(404, "POST_NOT_FOUND", "Post nao pertence a esta comunidade.");
		}
		repository.savePinnedPost(communityId, postId, userId, clock.instant());
		return postId;
	}

	@Transactional
	public void unpinPost(String communityId, String userId) {
		getById(communityId);
		requireAdmin(communityId, userId);
		repository.removePinnedPost(communityId);
	}

	public Optional<String> pinnedPostId(String communityId) {
		return repository.findPinnedPostId(communityId);
	}

	private void requireAdmin(String communityId, String userId) {
		repository.findMember(communityId, userId)
			.filter(member -> member.role() == MemberRole.ADMIN)
			.orElseThrow(() -> new ApiException(403, "FORBIDDEN",
				"So quem administra a comunidade pode fixar publicacoes."));
	}

	public int countMembers(String communityId) {
		return repository.countMembers(communityId);
	}

	/** Contagem de membros da pagina inteira, numa consulta (a vitrine fazia uma por comunidade). */
	public java.util.Map<String, Integer> countMembersByCommunityIds(java.util.Collection<String> communityIds) {
		return repository.countMembersByCommunityIds(communityIds);
	}

	/** Das comunidades da pagina, quais a pessoa participa — numa consulta. */
	public java.util.Set<String> memberCommunityIds(String userId, java.util.Collection<String> communityIds) {
		return repository.memberCommunityIds(userId, communityIds);
	}

	public List<CommunityMember> findMembers(String communityId) {
		return repository.findMembersByCommunity(communityId);
	}

}
