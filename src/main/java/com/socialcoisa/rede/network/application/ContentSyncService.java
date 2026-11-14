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
import com.socialcoisa.rede.network.domain.ContentChange;
import com.socialcoisa.rede.network.domain.ContentKind;
import com.socialcoisa.rede.network.domain.ContentVersion;
import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.network.domain.NodeProfile;
import com.socialcoisa.rede.network.domain.UserProfileProjection;
import com.socialcoisa.rede.profile.application.ProfileService;
import com.socialcoisa.rede.profile.application.UserProfileView;
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

	/** Teto de uma página do feed: o nó pagina, não pede "tudo" de uma vez. */
	private static final int MAX_CHANGES_PAGE = 500;

	private final ContentVersionRepository versions;
	private final ProfileService profiles;
	private final CommunityService communities;
	private final ObjectMapper objectMapper;
	private final Clock clock;

	ContentSyncService(ContentVersionRepository versions, ProfileService profiles, CommunityService communities,
			ObjectMapper objectMapper, Clock clock) {
		this.versions = versions;
		this.profiles = profiles;
		this.communities = communities;
		this.objectMapper = objectMapper;
		this.clock = clock;
	}

	/**
	 * O conteúdo público na versão vigente, para um nó de borda.
	 *
	 * @param knownVersion versão que o nó já tem; quando é a vigente, a resposta vem sem corpo
	 */
	@Transactional
	public EdgeContent serve(NetworkNode node, ContentKind kind, String contentId, Long knownVersion) {
		requireContentReader(node);
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
		requireContentReader(node);
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
				versions.recordChange(first);
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
			// O feed é gravado na MESMA transação da versão: mudança sem linha no feed é mudança que
			// nenhum nó de borda descobre, e o cache dele ficaria velho até o TTL — ou para sempre,
			// se o agregado nunca mais for editado.
			versions.recordChange(next);
			return next;
		}
		// Outra instância cunhou a versão seguinte entre a leitura e a escrita: a versão dela vale,
		// e o feed dela também — esta chamada não grava nada.
		return versions.find(kind, contentId).orElse(next);
	}

	/**
	 * O feed de mudanças depois do cursor do nó (V298).
	 *
	 * <p>É o que faltava para o cache de borda: sem o feed, o nó só descobria mudança perguntando
	 * agregado por agregado, então quem guardava mil entradas não tinha como saber quais revalidar e
	 * o que ninguém perguntava nunca convergia. O feed não carrega corpo — só o que mudou, para o nó
	 * revalidar o que ele tem.</p>
	 *
	 * <p><b>A poda precisa ser visível.</b> O feed é janela, não histórico. Um cursor que ficou atrás
	 * do que já foi apagado não pode receber "nada mudou": isso deixaria o nó servindo conteúdo velho
	 * para sempre achando que está em dia. Nesse caso a resposta vem com {@code reset=true} e o nó
	 * recarrega do zero.</p>
	 */
	@Transactional(readOnly = true)
	public Feed changes(NetworkNode node, long afterSeq, int limit) {
		requireContentReader(node);
		int page = Math.clamp(limit, 1, MAX_CHANGES_PAGE);
		long cursor = Math.max(0, afterSeq);
		boolean reset = resetNeeded(cursor);
		List<ContentChange> changes = reset ? List.of() : versions.changesAfter(cursor, page + 1);
		boolean hasMore = changes.size() > page;
		List<ContentChange> slice = hasMore ? changes.subList(0, page) : changes;
		long nextSeq = slice.isEmpty() ? cursor : slice.get(slice.size() - 1).seq();
		return new Feed(reset, cursor, nextSeq, hasMore, List.copyOf(slice));
	}

	/**
	 * O cursor ficou atrás da janela retida?
	 *
	 * <p>Só quem <b>tinha</b> posição pode ter perdido alguma: {@code cursor == 0} é o nó que nunca
	 * sincronizou, e para ele a carga completa é o caminho normal, não um reset. Já um cursor atrás
	 * do menor retido perdeu mudanças que não existem mais para reler — esse precisa recarregar.</p>
	 *
	 * <p>A condição é {@code cursor < menor - 1} e não {@code cursor < menor}: com o cursor
	 * exatamente uma posição antes do menor, a próxima linha é justamente a menor, sem buraco.</p>
	 */
	private boolean resetNeeded(long cursor) {
		return cursor > 0 && versions.oldestRetainedSeq().filter(oldest -> cursor < oldest - 1).isPresent();
	}

	/** O que o nó tem em atraso, por tipo. É a leitura do painel e a base do alerta
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
	 * Quem pode ler e confirmar conteúdo público: borda, contingência local e réplica de leitura.
	 *
	 * <p>O perfil é a permissão, e a permissão é sobre <b>o que</b> se lê: a projeção pública. Um nó
	 * {@code NODE_MEDIA} com a chave certa continua sem poder pedir projeção de perfil — capacidade
	 * não dá permissão. A réplica entra porque precisa ler exatamente a projeção que enumerou e
	 * confirmar o que guardou; sem isso a reconciliação dela não teria contra o que comparar.</p>
	 */
	private static void requireContentReader(NetworkNode node) {
		if (!node.status().canOperate()) {
			throw new ApiException(403, "NODE_" + node.status().name(), "No nao esta ativo.");
		}
		if (!node.profiles().contains(NodeProfile.NODE_EDGE) && !node.profiles().contains(NodeProfile.NODE_LOCAL)
				&& !node.profiles().contains(NodeProfile.NODE_READ_REPLICA)) {
			throw new ApiException(403, "NODE_PROFILE_REQUIRED", "No sem perfil de leitura de conteudo.");
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

	/**
	 * Uma página do feed de mudanças.
	 *
	 * @param reset o cursor do nó ficou atrás da janela retida: ele precisa recarregar tudo
	 * @param cursor o cursor que o nó mandou
	 * @param nextSeq onde o nó deve continuar da próxima vez
	 * @param hasMore há mais mudanças além desta página
	 */
	public record Feed(boolean reset, long cursor, long nextSeq, boolean hasMore, List<ContentChange> changes) {
	}
}
