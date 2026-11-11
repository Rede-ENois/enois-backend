package com.socialcoisa.rede.network.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.communities.application.CommunityService;
import com.socialcoisa.rede.communities.domain.Community;
import com.socialcoisa.rede.network.application.port.out.ContentVersionRepository;
import com.socialcoisa.rede.network.domain.CommunityProjection;
import com.socialcoisa.rede.network.domain.ContentKind;
import com.socialcoisa.rede.network.domain.ContentVersion;
import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.network.domain.NodeProfile;
import com.socialcoisa.rede.network.domain.UserProfileProjection;
import com.socialcoisa.rede.profile.application.ProfileService;
import com.socialcoisa.rede.profile.application.UserProfileView;
import com.socialcoisa.rede.shared.audit.AuditTrail;
import com.socialcoisa.rede.shared.security.Hashes;
import com.socialcoisa.rede.shared.web.ApiException;

import tools.jackson.databind.ObjectMapper;

/**
 * Conteúdo público versionado para os nós de borda (fase 3, NODE_EDGE).
 *
 * <p>O critério de aceite é "entrega conteúdo público com versão; nunca decide autorização de
 * usuário". As duas metades estão aqui:</p>
 *
 * <ul>
 * <li><b>Com versão.</b> O corpo servido é a projeção pública serializada; o hash do corpo é a
 * identidade da versão. Versão nova só nasce quando o corpo muda — recalcular e obter o mesmo JSON
 * não é mudança, e subir a versão ali faria todo nó baixar de novo o que não mudou.</li>
 * <li><b>Nunca autoriza usuário.</b> A rota é autenticada pela chave do NÓ, exige o perfil
 * {@code NODE_EDGE} e devolve projeções que não têm campo dependente de leitor: a comunidade sai
 * sem {@code joined}, o perfil sai sem nada que só o dono lê. Quem pergunta "eu participo?" continua
 * perguntando ao Control Plane, com a sessão da pessoa.</li>
 * </ul>
 *
 * <p>O modelo é <b>pull</b>, como o resto da rede: o nó pede o que precisa, informa a versão que já
 * tem e recebe {@code changed=false} quando está em dia. O Control Plane nunca empurra conteúdo
 * para um nó.</p>
 */
@Service
public class ContentSyncService {

	private static final Logger LOG = LoggerFactory.getLogger(ContentSyncService.class);

	private final ContentVersionRepository versions;
	private final ProfileService profiles;
	private final CommunityService communities;
	private final ObjectMapper objectMapper;
	private final AuditTrail audit;
	private final Clock clock;

	ContentSyncService(ContentVersionRepository versions, ProfileService profiles, CommunityService communities,
			ObjectMapper objectMapper, AuditTrail audit, Clock clock) {
		this.versions = versions;
		this.profiles = profiles;
		this.communities = communities;
		this.objectMapper = objectMapper;
		this.audit = audit;
		this.clock = clock;
	}

	/**
	 * O conteúdo público na versão vigente, para um nó de borda.
	 *
	 * @param knownVersion versão que o nó já tem; quando é a vigente, a resposta vem sem corpo
	 */
	@Transactional
	public EdgeContent serve(NetworkNode node, ContentKind kind, String contentId, Long knownVersion) {
		requireEdge(node);
		String id = normalize(kind, contentId);
		// O corpo é montado UMA vez e é o mesmo que o hash/versiona: serializar de novo para a
		// resposta abriria a janela em que o conteúdo muda entre a versão cunhada e o corpo
		// servido, e o nó guardaria um corpo que não corresponde ao hash que ele confirmou.
		String body = body(kind, id);
		ContentVersion current = publishHash(kind, id, Hashes.sha256Hex(body));
		if (knownVersion != null && knownVersion == current.version()) {
			return new EdgeContent(kind, id, current.version(), current.contentHash(), false, null);
		}
		return new EdgeContent(kind, id, current.version(), current.contentHash(), true, body);
	}

	/**
	 * O nó confirma o que guardou. A versão informada é a que ele baixou; o hash é o do corpo que
	 * ele tem. Guardar isso é o que permite a reconciliação comparar nó e origem sem perguntar ao
	 * nó de novo.
	 */
	@Transactional
	public void recordSynced(NetworkNode node, ContentKind kind, String contentId, long sourceVersion,
			String contentHash) {
		requireEdge(node);
		if (sourceVersion < 1) {
			throw new ApiException(400, "CONTENT_VERSION_INVALID", "Versao de conteudo invalida.");
		}
		if (contentHash == null || contentHash.isBlank()) {
			throw new ApiException(400, "CONTENT_HASH_REQUIRED", "Informe o hash do conteudo guardado.");
		}
		String id = normalize(kind, contentId);
		versions.recordSnapshot(node.id(), kind, id, sourceVersion, contentHash.trim().toLowerCase(), clock.instant());
	}

	/**
	 * Recalcula a versão do agregado e sobe o contador só se o corpo mudou.
	 *
	 * <p>Chamado na leitura da borda: a versão é propriedade do corpo que o Control Plane serve
	 * agora, então ela é cunhada no momento em que alguém pergunta. Não existe job varrendo todo o
	 * conteúdo público — o que ninguém pede não precisa de versão.</p>
	 */
	@Transactional
	public ContentVersion publish(ContentKind kind, String contentId) {
		return publishHash(kind, contentId, Hashes.sha256Hex(body(kind, contentId)));
	}

	/** A cunhagem em si, sobre um hash já calculado do corpo que será servido. */
	private ContentVersion publishHash(ContentKind kind, String contentId, String hash) {
		Instant now = clock.instant();
		Optional<ContentVersion> existing = versions.find(kind, contentId);
		if (existing.isEmpty()) {
			ContentVersion first = new ContentVersion(kind, contentId, 1, hash, now);
			// Duas instâncias cunhando a versão 1 ao mesmo tempo: a segunda não grava e relê.
			if (versions.save(first, 0)) {
				return first;
			}
			return versions.find(kind, contentId).orElse(first);
		}
		ContentVersion current = existing.get();
		if (current.sameBody(hash)) {
			return current;
		}
		ContentVersion next = current.next(hash, now);
		if (versions.save(next, current.version())) {
			return next;
		}
		// Outra instância cunhou a versão seguinte entre a leitura e a escrita: a versão dela vale.
		return versions.find(kind, contentId).orElse(next);
	}

	/**
	 * O que o nó tem em atraso, por tipo. É a leitura do painel e a base do alerta
	 * {@code SYNC_DIVERGENCE} da reconciliação.
	 */
	@Transactional(readOnly = true)
	public List<KindLag> lag(NetworkNode node) {
		List<KindLag> result = new ArrayList<>();
		for (ContentKind kind : ContentKind.values()) {
			result.add(new KindLag(kind, versions.countBehind(node.id(), kind)));
		}
		return result;
	}

	/**
	 * Reconciliação: para cada agregado que o nó diz ter, recalcula a versão da origem e compara o
	 * hash. Divergência vira auditoria {@code SYNC_DIVERGENCE} — o alerta que o painel lê.
	 *
	 * <p>Compara hash e não versão porque versão igual com corpo diferente seria o pior caso: o nó
	 * acharia que está em dia. E o agregado que sumiu da origem (perfil banido, comunidade
	 * excluída) também é divergência: o nó está servindo o que não existe mais.</p>
	 */
	@Transactional
	public List<Divergence> reconcile(NetworkNode node, List<SnapshotRef> snapshots) {
		List<Divergence> divergences = new ArrayList<>();
		for (SnapshotRef ref : snapshots) {
			Optional<ContentVersionRepository.NodeSnapshot> stored = versions.snapshot(node.id(), ref.kind(),
				ref.contentId());
			if (stored.isEmpty()) {
				continue;
			}
			ContentVersionRepository.NodeSnapshot snapshot = stored.get();
			ContentVersion current;
			try {
				current = publish(ref.kind(), ref.contentId());
			}
			catch (ApiException gone) {
				divergences.add(new Divergence(ref.kind(), ref.contentId(), snapshot.sourceVersion(), null,
					"CONTENT_GONE"));
				audit.record(node.id(), "SYNC_DIVERGENCE", ref.contentId(),
					"{\"kind\":\"" + ref.kind().name() + "\",\"reason\":\"CONTENT_GONE\"}");
				continue;
			}
			if (!current.contentHash().equals(snapshot.contentHash())) {
				divergences.add(new Divergence(ref.kind(), ref.contentId(), snapshot.sourceVersion(),
					current.version(), "CONTENT_STALE"));
				audit.record(node.id(), "SYNC_DIVERGENCE", ref.contentId(),
					"{\"kind\":\"" + ref.kind().name() + "\",\"nodeVersion\":" + snapshot.sourceVersion()
						+ ",\"reason\":\"CONTENT_STALE\"}");
			}
		}
		return divergences;
	}

	/** O corpo público do agregado, já serializado. É a única fonte do hash e da resposta. */
	private String body(ContentKind kind, String contentId) {
		Object projection = switch (kind) {
			case PROFILE -> profileProjection(contentId);
			case COMMUNITY -> communityProjection(contentId);
		};
		try {
			return objectMapper.writeValueAsString(projection);
		}
		catch (RuntimeException serialization) {
			// Serializar a própria projeção não deveria falhar; se falhar, é defeito nosso e o nó
			// não pode receber corpo parcial.
			throw new IllegalStateException("Falha ao serializar conteudo publico " + kind + "/" + contentId,
				serialization);
		}
	}

	private UserProfileProjection profileProjection(String username) {
		UserProfileView view = profiles.getByUsername(username);
		return new UserProfileProjection(view.id(), view.username(), view.name(), view.headline(), view.company(),
			view.location(), view.avatar(), view.banner(), view.badge(), view.bio(), view.bioHtml(), view.bannerHtml(),
			view.skills(), view.goals(), view.availability(), view.portfolio(), view.externalLinks(),
			view.openToWork(), view.isMentor(), view.connectionsCount(), view.followersCount(), view.reputation(),
			view.postsCount(), view.pronouns(), view.badges(), view.aka(), view.avatarFrame(), view.nameTheme(),
			view.nameFont(), view.nameColor(), view.contributorTagColor(), view.cardTheme(), view.nameplate(),
			view.bannerItem(), view.profileEffect(), view.statusText(), view.statusIcon(), view.city(), view.state(),
			view.gardenStyle());
	}

	private CommunityProjection communityProjection(String communityId) {
		Community community = communities.getById(communityId);
		return new CommunityProjection(community.id(), community.name(), community.description(),
			communities.countMembers(community.id()), community.topic(), community.cover(), community.avatar(),
			community.bannerHtml(), community.place() == null ? null : community.place().city(),
			community.place() == null ? null : community.place().state());
	}

	/**
	 * Só nó de borda ativo serve conteúdo. O perfil é a permissão: um nó {@code NODE_MEDIA} com a
	 * chave certa continua sem poder pedir projeção de perfil — capacidade não dá permissão.
	 */
	private static void requireEdge(NetworkNode node) {
		if (!node.status().canOperate()) {
			throw new ApiException(403, "NODE_" + node.status().name(), "No nao esta ativo.");
		}
		if (!node.profiles().contains(NodeProfile.NODE_EDGE) && !node.profiles().contains(NodeProfile.NODE_LOCAL)) {
			throw new ApiException(403, "NODE_PROFILE_REQUIRED", "No sem perfil de borda.");
		}
	}

	/** O @ do perfil é comparado sem caixa, como no cache público; o id da comunidade é literal. */
	private static String normalize(ContentKind kind, String contentId) {
		if (contentId == null || contentId.isBlank()) {
			throw new ApiException(400, "CONTENT_ID_REQUIRED", "Informe o conteudo.");
		}
		String trimmed = contentId.trim();
		return kind == ContentKind.PROFILE ? trimmed.toLowerCase(java.util.Locale.ROOT) : trimmed;
	}

	/** Conteúdo público na versão vigente. {@code body} nulo quando o nó já está em dia. */
	public record EdgeContent(ContentKind kind, String contentId, long version, String contentHash, boolean changed,
			String body) {
	}

	/** Atraso de um tipo: quantos agregados o nó não tem na versão da origem. */
	public record KindLag(ContentKind kind, long behind) {
	}

	/** Um agregado que o nó diz ter, para a reconciliação conferir. */
	public record SnapshotRef(ContentKind kind, String contentId) {
	}

	/** Divergência encontrada: o nó está atrás ou servindo o que não existe mais. */
	public record Divergence(ContentKind kind, String contentId, long nodeVersion, Long sourceVersion, String reason) {
	}
}
