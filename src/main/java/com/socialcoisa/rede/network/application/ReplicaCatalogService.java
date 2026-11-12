package com.socialcoisa.rede.network.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.communities.application.CommunityService;
import com.socialcoisa.rede.communities.application.port.out.CommunityRepository;
import com.socialcoisa.rede.communities.application.port.out.ReplicaCommunityKey;
import com.socialcoisa.rede.network.domain.ContentKind;
import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.network.domain.NodeProfile;
import com.socialcoisa.rede.network.domain.ReplicaCursor;
import com.socialcoisa.rede.network.domain.ReplicaPage;
import com.socialcoisa.rede.profile.application.ProfileService;
import com.socialcoisa.rede.profile.application.port.out.ReplicaProfileKey;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * O catálogo que a réplica de leitura percorre (fase 3, {@code NODE_READ_REPLICA}).
 *
 * <p>A pergunta desta classe é só uma: <b>quais projeções existem, em que ordem, e onde a réplica
 * parou</b>. O corpo de cada item continua vindo de {@code GET /network/content/{kind}/{id}}, que já
 * carrega a versão vigente e o hash do corpo. Separá-los é de propósito: o catálogo é a lista de
 * trabalho da réplica, e a leitura é a mesma projeção que a borda lê — se o catálogo trouxesse o
 * corpo, existiriam dois caminhos de serialização para o mesmo agregado e uma versão que não fecha
 * com o hash do que foi guardado.</p>
 *
 * <p>Ao contrário da borda, que descobre agregado por id, a réplica precisa <b>enumerar</b>. A
 * enumeração é por chave (criado em, id) e não por posição: a réplica leva horas para percorrer o
 * catálogo inteiro, e nesse tempo contas nascem e comunidades são apagadas. Com {@code offset}, a
 * conta nova empurra as seguintes para frente e uma página inteira é pulada; com chave, "continua
 * depois de (criado em, id)" devolve exatamente o que ainda não foi lido.</p>
 *
 * <p><b>O catálogo nunca é mais largo que a vitrine.</b> Tudo que a lista pública esconde — conta
 * removida, comunidade de conta suspensa, comunidade com o texto do ataque — fica fora daqui, com o
 * mesmo critério. A réplica é superfície pública: se ela recebesse o que a vitrine esconde, o que
 * não aparece no site apareceria na rede.</p>
 */
@Service
public class ReplicaCatalogService {

	/** Teto de uma página do catálogo: a réplica pagina, não pede o catálogo inteiro de uma vez. */
	private static final int MAX_PAGE = 500;

	/**
	 * A allowlist da réplica, explícita e versionada no código.
	 *
	 * <p>São as duas projeções públicas que já existem na borda — perfil por @ e vitrine de
	 * comunidade por id —, e nada além. A réplica não lê tabela: não há caminho daqui para o banco a
	 * não ser pelos módulos donos. Ampliar a allowlist é uma decisão de contrato, com versão nova,
	 * não um parâmetro de requisição.</p>
	 */
	private static final Set<ContentKind> ALLOWLIST = Set.of(ContentKind.PROFILE, ContentKind.COMMUNITY);

	private final UserProfileRepository profiles;
	private final CommunityRepository communities;
	private final ProfileService profileService;
	private final CommunityService communityService;

	ReplicaCatalogService(UserProfileRepository profiles, CommunityRepository communities, ProfileService profileService,
			CommunityService communityService) {
		this.profiles = profiles;
		this.communities = communities;
		this.profileService = profileService;
		this.communityService = communityService;
	}

	/** Os tipos que a réplica pode pedir. A rota recusa qualquer outro antes de consultar. */
	public static Set<ContentKind> allowlist() {
		return ALLOWLIST;
	}

	/**
	 * Uma página do catálogo depois do cursor, no máximo {@code limit} itens.
	 *
	 * @param cursor posição que a réplica já tem; {@code start} pede desde o começo
	 */
	@Transactional(readOnly = true)
	public ReplicaPage page(NetworkNode node, ContentKind kind, ReplicaCursor cursor, int limit) {
		requireReplica(node);
		if (!ALLOWLIST.contains(kind)) {
			throw new ApiException(403, "CONTENT_KIND_NOT_REPLICATED",
				"Tipo de conteudo fora da allowlist da replica.");
		}
		int size = Math.clamp(limit, 1, MAX_PAGE);
		ReplicaCursor from = cursor == null ? ReplicaCursor.start() : cursor;
		return switch (kind) {
			case PROFILE -> profilePage(from, size);
			case COMMUNITY -> communityPage(from, size);
		};
	}

	/**
	 * Perfis, por (criado em, id) crescente.
	 *
	 * <p>Pede uma linha a mais que a página: é assim que se sabe se há próxima sem uma contagem — com
	 * {@code limit} exato, "página cheia" e "fim do catálogo" ficam indistinguíveis, e a réplica faria
	 * uma última chamada vazia.</p>
	 *
	 * <p>O filtro de suspensão é <b>por página</b>, não por item, e o cursor anda sobre o último item
	 * <i>lido do banco</i>, não sobre o último devolvido: se a página inteira fosse de contas banidas,
	 * um cursor no último devolvido ficaria parado e a réplica repetiria a mesma página para sempre.</p>
	 */
	private ReplicaPage profilePage(ReplicaCursor from, int size) {
		Instant afterAt = from.isStart() ? null : parseInstantOf(from.key());
		String afterId = from.isStart() ? null : from.id();
		List<ReplicaProfileKey> rows = profiles.replicaPage(afterAt, afterId, size + 1);
		boolean hasMore = rows.size() > size;
		List<ReplicaProfileKey> slice = hasMore ? rows.subList(0, size) : rows;

		Set<String> visible = profileService.accountsVisible(
			slice.stream().map(ReplicaProfileKey::userId).toList());
		List<String> ids = new ArrayList<>();
		for (ReplicaProfileKey row : slice) {
			// O catálogo devolve o @: é a chave que a rota de conteúdo aceita.
			if (visible.contains(row.userId())) {
				ids.add(row.username());
			}
		}
		// A chave anda sobre o último item LIDO do banco, mesmo quando ele não foi devolvido por estar
		// fora da vitrine. Na última página a consulta vem incompleta (não há a linha a mais), e o
		// cursor dela ainda precisa apontar para depois do último item: é o que a réplica guarda como
		// "li até aqui". Cursor nulo junto com a última página faria a réplica recomeçar do início e
		// rebaixar o catálogo inteiro de novo.
		ReplicaCursor next = slice.isEmpty() ? from : cursorOf(slice.get(slice.size() - 1));
		return new ReplicaPage(ContentKind.PROFILE, ids, hasMore, next);
	}

	/**
	 * Comunidades, por (criado em, id) crescente, com o mesmo filtro da vitrine.
	 *
	 * <p>O critério é o da lista pública — criador suspenso e texto do ataque —, decidido pelo serviço
	 * de comunidades e aplicado em lote: as comunidades da página vêm numa consulta e a sanção dos
	 * criadores em outra. O que a réplica acrescenta é só a forma da resposta: ela guarda ids.</p>
	 */
	private ReplicaPage communityPage(ReplicaCursor from, int size) {
		Instant afterAt = from.isStart() ? null : parseInstantOf(from.key());
		String afterId = from.isStart() ? null : from.id();
		List<ReplicaCommunityKey> rows = communities.replicaPage(afterAt, afterId, size + 1);
		boolean hasMore = rows.size() > size;
		List<ReplicaCommunityKey> slice = hasMore ? rows.subList(0, size) : rows;

		Set<String> visible = communityService.communitiesVisible(
			slice.stream().map(ReplicaCommunityKey::communityId).toList());
		List<String> ids = new ArrayList<>();
		for (ReplicaCommunityKey row : slice) {
			if (visible.contains(row.communityId())) {
				ids.add(row.communityId());
			}
		}
		ReplicaCursor next = slice.isEmpty() ? from : cursorOf(slice.get(slice.size() - 1));
		return new ReplicaPage(ContentKind.COMMUNITY, ids, hasMore, next);
	}

	private static ReplicaCursor cursorOf(ReplicaProfileKey row) {
		return new ReplicaCursor(row.createdAt() == null ? "" : row.createdAt().toString(), row.userId());
	}

	private static ReplicaCursor cursorOf(ReplicaCommunityKey row) {
		return new ReplicaCursor(row.createdAt() == null ? "" : row.createdAt().toString(), row.communityId());
	}

	/**
	 * A chave volta como texto opaco no cursor. Se ela não for um instante legível, o cursor é de
	 * outra versão do formato — recusar com erro claro é melhor que recomeçar em silêncio e
	 * duplicar o catálogo na réplica.
	 */
	private static Instant parseInstantOf(String key) {
		try {
			return Instant.parse(key);
		}
		catch (RuntimeException invalid) {
			throw new ApiException(400, "REPLICA_CURSOR_INVALID", "Cursor de replica invalido.");
		}
	}

	/**
	 * Só nó ativo com perfil de réplica enumera o catálogo.
	 *
	 * <p>{@code NODE_LOCAL} também pode: a contingência local guarda a mesma projeção pública para
	 * continuar servindo com o Control Plane fora, e serve o mesmo recorte da vitrine. {@code
	 * NODE_EDGE} não está aqui de propósito — ele descobre conteúdo pelo feed e pelo id; dar a ele o
	 * catálogo inteiro seria entregar a lista de todo mundo a quem só precisa do que o usuário pediu.</p>
	 */
	private static void requireReplica(NetworkNode node) {
		if (!node.status().canOperate()) {
			throw new ApiException(403, "NODE_" + node.status().name(), "No nao esta ativo.");
		}
		if (!node.profiles().contains(NodeProfile.NODE_READ_REPLICA)
				&& !node.profiles().contains(NodeProfile.NODE_LOCAL)) {
			throw new ApiException(403, "NODE_PROFILE_REQUIRED", "No sem perfil de replica de leitura.");
		}
	}
}
