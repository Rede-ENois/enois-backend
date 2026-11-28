package com.socialcoisa.rede.portfolio.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.portfolio.application.port.out.PortfolioMediaCatalog;
import com.socialcoisa.rede.portfolio.application.port.out.PortfolioPageRepository;
import com.socialcoisa.rede.portfolio.application.port.out.PortfolioProjectCatalog;
import com.socialcoisa.rede.portfolio.application.port.out.PortfolioPublicationRepository;
import com.socialcoisa.rede.portfolio.application.port.out.PortfolioRepository;
import com.socialcoisa.rede.portfolio.domain.PortfolioBlock;
import com.socialcoisa.rede.portfolio.domain.PortfolioBlockType;
import com.socialcoisa.rede.portfolio.domain.PortfolioPage;
import com.socialcoisa.rede.portfolio.domain.PortfolioPublication;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.projects.application.port.out.UserProjectRepository;
import com.socialcoisa.rede.projects.domain.UserProject;
import com.socialcoisa.rede.shared.html.SafeHtml;
import com.socialcoisa.rede.shared.sanctions.SanctionGate;
import com.socialcoisa.rede.shared.sanctions.SanctionGateReader;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * O portfólio profissional: a página com aparência própria do dono e as peças que ele monta.
 *
 * <p>Duas regras governam o módulo. A primeira é que o público nunca vê o rascunho: toda edição
 * grava no rascunho, e só {@link #publish} copia para a trilha pública e incrementa a versão. A
 * segunda é que HTML do autor passa exatamente pela mesma limpeza de página do resto do enois
 * ({@link SafeHtml#sanitizePortfolioHtml}); não existe um caminho alternativo.</p>
 */
@Service
public class PortfolioService {

	static final int MAX_BLOCKS = 30;
	static final int MAX_TITLE = 160;
	static final int MAX_TEXT = 4000;
	private static final String LINK_PREFIX = "https://";

	private final PortfolioPageRepository pages;
	private final PortfolioRepository blocks;
	private final PortfolioPublicationRepository publications;
	private final UserProfileRepository profiles;
	private final UserProjectRepository projects;
	private final PortfolioProjectCatalog projectCatalog;
	private final PortfolioMediaCatalog mediaCatalog;
	/** Portão de escrita: conta restrita não edita nem publica. */
	private final SanctionGate writeGate;
	/** Leitura do banimento: o portfólio de conta banida não aparece no perfil público. */
	private final SanctionGateReader sanctionGate;
	private final Clock clock;

	PortfolioService(PortfolioPageRepository pages, PortfolioRepository blocks,
			PortfolioPublicationRepository publications, UserProfileRepository profiles,
			UserProjectRepository projects, PortfolioProjectCatalog projectCatalog,
			PortfolioMediaCatalog mediaCatalog, SanctionGate writeGate, SanctionGateReader sanctionGate,
			Clock clock) {
		this.pages = pages;
		this.blocks = blocks;
		this.publications = publications;
		this.profiles = profiles;
		this.projects = projects;
		this.projectCatalog = projectCatalog;
		this.mediaCatalog = mediaCatalog;
		this.writeGate = writeGate;
		this.sanctionGate = sanctionGate;
		this.clock = clock;
	}

	/** O que o dono está editando: rascunho, peças e a última versão publicada. */
	@Transactional(readOnly = true)
	public OwnPortfolio own(String userId) {
		return new OwnPortfolio(pages.find(userId).orElse(emptyPage(userId)), blocks.findBlocks(userId));
	}

	/** O que qualquer pessoa vê: só a última publicação. Perfil inexistente e sem portfólio são 404. */
	@Transactional(readOnly = true)
	public PublicPortfolio byUsername(String username) {
		var profile = profiles.findByUsername(username)
			.filter(found -> !sanctionGate.banned(found.id()))
			.orElseThrow(() -> notFound());
		return publications.findLatest(profile.id())
			.filter(publication -> publication.version() > 0)
			.map(publication -> new PublicPortfolio(profile.username(), publication.version(), publication.html(),
				withProjectCards(profile.id(), publication.blocks()), publication.publishedAt()))
			.orElseThrow(PortfolioService::notFound);
	}

	/** Grava o rascunho da página. Não publica: o público continua vendo a versão anterior. */
	@Transactional
	public OwnPortfolio saveDraft(String userId, String rawHtml) {
		writeGate.requireNotRestricted(userId);
		String html = null;
		if (StringUtils.hasText(rawHtml)) {
			html = SafeHtml.sanitizePortfolioHtml(rawHtml);
			if (html == null) {
				throw new ApiException(400, "INVALID_PORTFOLIO_HTML",
					"A pagina do portfolio precisa de HTML valido.");
			}
		}
		PortfolioPage page = pages.find(userId).orElse(emptyPage(userId));
		return new OwnPortfolio(pages.save(page.withDraft(html, clock.instant())), blocks.findBlocks(userId));
	}

	/** Cria (sem id) ou atualiza (com id) uma peça no rascunho. */
	@Transactional
	public OwnPortfolio saveBlock(String userId, String blockId, BlockInput input) {
		writeGate.requireNotRestricted(userId);
		validate(userId, input);
		PortfolioBlock existing = StringUtils.hasText(blockId)
			? blocks.findBlock(userId, blockId)
				.orElseThrow(() -> new ApiException(404, "PORTFOLIO_BLOCK_NOT_FOUND", "Peca do portfolio nao encontrada."))
			: null;
		List<PortfolioBlock> current = blocks.findBlocks(userId);
		if (existing == null && current.size() >= MAX_BLOCKS) {
			throw new ApiException(400, "MAX_PORTFOLIO_BLOCKS",
				"O portfolio tem no maximo " + MAX_BLOCKS + " pecas.");
		}
		if (existing != null) {
			blocks.saveBlock(rebuild(existing, input).unpublished());
		}
		else {
			blocks.saveBlock(newBlock(userId, current.size(), input));
		}
		return new OwnPortfolio(pages.find(userId).orElse(emptyPage(userId)), blocks.findBlocks(userId));
	}

	@Transactional
	public OwnPortfolio deleteBlock(String userId, String blockId) {
		writeGate.requireNotRestricted(userId);
		if (blocks.findBlock(userId, blockId).isEmpty()) {
			throw new ApiException(404, "PORTFOLIO_BLOCK_NOT_FOUND", "Peca do portfolio nao encontrada.");
		}
		blocks.deleteBlock(userId, blockId);
		return new OwnPortfolio(pages.find(userId).orElse(emptyPage(userId)), blocks.findBlocks(userId));
	}

	/** Reordena as peças pela lista de ids; precisa ser exatamente o conjunto do dono, uma vez cada. */
	@Transactional
	public OwnPortfolio reorder(String userId, List<String> order) {
		writeGate.requireNotRestricted(userId);
		List<PortfolioBlock> current = blocks.findBlocks(userId);
		Map<String, PortfolioBlock> byId = current.stream()
			.collect(Collectors.toMap(PortfolioBlock::id, Function.identity()));
		if (order == null || order.size() != current.size()
				|| !byId.keySet().equals(new HashSet<>(order)) || new HashSet<>(order).size() != order.size()) {
			throw new ApiException(400, "PORTFOLIO_ORDER_INVALID",
				"A reordenacao precisa listar todas as pecas do portfolio, uma vez cada.");
		}
		for (int position = 0; position < order.size(); position++) {
			PortfolioBlock block = byId.get(order.get(position));
			blocks.saveBlock(reposition(block, position));
		}
		return new OwnPortfolio(pages.find(userId).orElse(emptyPage(userId)), blocks.findBlocks(userId));
	}

	/**
	 * Publica o rascunho como a próxima versão.
	 *
	 * <p>Só publica quem tem algo para mostrar: uma página com HTML ou ao menos uma peça. As peças
	 * do rascunho são carimbadas com a versão nova (o público lê o retrato da trilha) e saem da
	 * lista de alteradas.</p>
	 */
	@Transactional
	public PublicPortfolio publish(String userId) {
		writeGate.requireNotRestricted(userId);
		PortfolioPage page = pages.find(userId).orElse(emptyPage(userId));
		List<PortfolioBlock> draft = blocks.findBlocks(userId);
		if (page.draftHtml() == null && draft.isEmpty()) {
			throw new ApiException(409, "PORTFOLIO_EMPTY", "Monte a pagina ou uma peca antes de publicar.");
		}
		// Mídia pode ter sido trocada ou apagada depois de entrar na peça: confere de novo na hora de
		// ir ao ar, com a mesma regra da gravação (do dono, tipo certo, checagem de conteúdo feita).
		for (PortfolioBlock block : draft) {
			if (block.mediaId() != null && (block.type() == PortfolioBlockType.MEDIA
					|| block.type() == PortfolioBlockType.PDF)
					&& !mediaCatalog.usable(userId, block.mediaId(), block.type())) {
				throw new ApiException(400, "PORTFOLIO_MEDIA_NOT_CHECKED",
					"Uma peca usa midia que ainda nao foi liberada pela checagem de conteudo ou nao esta mais disponivel.");
			}
		}
		int version = page.publishedVersion() + 1;
		Instant now = clock.instant();
		List<PortfolioBlock> snapshot = new ArrayList<>();
		for (PortfolioBlock block : draft) {
			PortfolioBlock stamped = blocks.saveBlock(block.atVersion(version));
			snapshot.add(stamped);
		}
		pages.save(page.publishedAs(version, now));
		publications.save(new PortfolioPublication(newId("pub"), userId, version, page.draftHtml(), snapshot, now));
		return new PublicPortfolio(currentUsername(userId), version, page.draftHtml(), snapshot, now);
	}

	private String currentUsername(String userId) {
		return profiles.findById(userId).map(profile -> profile.username()).orElseThrow(PortfolioService::notFound);
	}

	private PortfolioPage emptyPage(String userId) {
		return new PortfolioPage(userId, null, 0, null, clock.instant());
	}

	private PortfolioBlock newBlock(String userId, int position, BlockInput input) {
		Instant now = clock.instant();
		return new PortfolioBlock(newId("pblk"), userId, position, input.type(), input.title(), input.text(),
			input.projectId(), input.mediaId(), input.linkUrl(), null, now, now);
	}

	private PortfolioBlock rebuild(PortfolioBlock existing, BlockInput input) {
		return new PortfolioBlock(existing.id(), existing.userId(), existing.position(), input.type(), input.title(),
			input.text(), input.projectId(), input.mediaId(), input.linkUrl(), existing.publishedVersion(),
			existing.createdAt(), clock.instant());
	}

	private PortfolioBlock reposition(PortfolioBlock block, int position) {
		return new PortfolioBlock(block.id(), block.userId(), position, block.type(), block.title(), block.text(),
			block.projectId(), block.mediaId(), block.linkUrl(), block.publishedVersion(), block.createdAt(),
			clock.instant());
	}

	private static String newId(String prefix) {
		return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
	}

	/**
	 * Troca a referência do card de projeto pelo conteúdo dele, para o público não precisar buscar
	 * o perfil à parte. Projeto que foi apagado depois da publicação some do retrato público — um
	 * card de outro dono nunca entra.
	 */
	private List<PortfolioBlock> withProjectCards(String userId, List<PortfolioBlock> snapshot) {
		Map<String, UserProject> cards = projects.findByUser(userId).stream()
			.collect(Collectors.toMap(UserProject::id, Function.identity()));
		List<PortfolioBlock> resolved = new ArrayList<>();
		for (PortfolioBlock block : snapshot) {
			if (block.type() != PortfolioBlockType.PROJECT) {
				resolved.add(block);
				continue;
			}
			if (cards.containsKey(block.projectId())) {
				resolved.add(block);
			}
		}
		return resolved;
	}

	private void validate(String userId, BlockInput input) {
		if (input == null || input.type() == null) {
			throw new ApiException(400, "PORTFOLIO_BLOCK_TYPE_REQUIRED", "O tipo da peca e obrigatorio.");
		}
		if (StringUtils.hasText(input.title()) && input.title().length() > MAX_TITLE) {
			throw new ApiException(400, "PORTFOLIO_BLOCK_TITLE_TOO_LONG",
				"O titulo da peca tem no maximo " + MAX_TITLE + " caracteres.");
		}
		if (StringUtils.hasText(input.text()) && input.text().length() > MAX_TEXT) {
			throw new ApiException(400, "PORTFOLIO_BLOCK_TEXT_TOO_LONG",
				"O texto da peca tem no maximo " + MAX_TEXT + " caracteres.");
		}
		switch (input.type()) {
			case PROJECT -> {
				if (!StringUtils.hasText(input.projectId())
						|| !projectCatalog.projectIdsOf(userId).contains(input.projectId())) {
					throw new ApiException(400, "PORTFOLIO_BLOCK_PROJECT_INVALID",
						"A peca de projeto precisa apontar para um projeto seu.");
				}
			}
			case TEXT, LINK -> {
				if (!StringUtils.hasText(input.text()) && !StringUtils.hasText(input.linkUrl())) {
					throw new ApiException(400, "PORTFOLIO_BLOCK_CONTENT_REQUIRED",
						"A peca precisa de texto ou de link.");
				}
				requireHttpsLink(input.linkUrl());
			}
			case MEDIA, PDF -> {
				if (!StringUtils.hasText(input.mediaId())) {
					throw new ApiException(400, "PORTFOLIO_BLOCK_MEDIA_INVALID",
						"A peca precisa de uma midia sua" + (input.type() == PortfolioBlockType.PDF
							? " e em PDF." : "."));
				}
				if (!mediaCatalog.usable(userId, input.mediaId(), input.type())) {
					// Duas causas diferentes com o mesmo olhar: mídia de outra pessoa/tipo errado, ou
					// mídia do dono que a checagem de conteúdo ainda não liberou (PDF sem página).
					boolean awaitingCheck = mediaCatalog.uncheckedMedia(List.of(input.mediaId()))
						.containsKey(input.mediaId());
					throw new ApiException(400, "PORTFOLIO_BLOCK_MEDIA_INVALID", awaitingCheck
						? "A midia desta peca ainda esta em analise de conteudo. Tente de novo em instantes."
						: "A peca precisa de uma midia sua" + (input.type() == PortfolioBlockType.PDF
							? " e em PDF." : "."));
				}
			}
		}
	}

	private void requireHttpsLink(String link) {
		if (StringUtils.hasText(link) && !link.startsWith(LINK_PREFIX)) {
			throw new ApiException(400, "PORTFOLIO_LINK_INVALID", "O link da peca precisa comecar com https://.");
		}
	}

	private static ApiException notFound() {
		return new ApiException(404, "PORTFOLIO_NOT_FOUND", "Portfolio nao encontrado.");
	}

	/** Entrada de peça como chega do editor; campos ausentes são nulos. */
	public record BlockInput(PortfolioBlockType type, String title, String text, String projectId, String mediaId,
			String linkUrl) {
	}

	/** Vista do dono: rascunho e peças, inclusive as que ainda não foram publicadas. */
	public record OwnPortfolio(PortfolioPage page, List<PortfolioBlock> blocks) {
	}

	/** Vista pública: só o que está na última publicação. */
	public record PublicPortfolio(String username, int version, String html, List<PortfolioBlock> blocks,
			Instant publishedAt) {
	}
}
