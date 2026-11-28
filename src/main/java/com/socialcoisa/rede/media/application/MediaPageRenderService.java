package com.socialcoisa.rede.media.application;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.socialcoisa.rede.media.application.port.out.MediaAssetRepository;
import com.socialcoisa.rede.media.application.port.out.MediaDeletionJobRepository;
import com.socialcoisa.rede.media.application.port.out.MediaObjectStore;
import com.socialcoisa.rede.media.application.port.out.MediaPageRenderer;
import com.socialcoisa.rede.media.application.port.out.MediaPageRenderer.PageRenderException;
import com.socialcoisa.rede.media.application.port.out.MediaPageSets;
import com.socialcoisa.rede.media.application.port.out.MediaRenderJobRepository;
import com.socialcoisa.rede.media.application.port.out.StorageUrlFactory;
import com.socialcoisa.rede.media.domain.MediaAsset;
import com.socialcoisa.rede.media.domain.MediaRenderJob;
import com.socialcoisa.rede.media.domain.MediaRenderJobStatus;
import com.socialcoisa.rede.media.domain.MediaStorageProvider;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Renderiza as páginas de um PDF e as publica como imagens próprias, JÁ PASSADAS pela checagem de
 * conteúdo antes de irem para o bucket.
 *
 * <p><b>Por que existe:</b> o classificador de conteúdo explícito não lê PDF. O PDF passava inteiro
 * pela subida (o tratamento de PDF só limpa metadados) e ia para o storage público sem que nenhuma
 * barreira tivesse visto o que está desenhado nas folhas. Aqui cada página é desenhada, conferida
 * pelo mesmo verificador das imagens e só então gravada — a página que não passa nunca chega ao
 * bucket, e o documento inteiro é recusado como qualquer imagem explícita seria.</p>
 *
 * <p><b>Página como asset:</b> cada página é uma linha em {@code media_assets} com a chave
 * `paginas/<lote>/pagina-<n>.png`. Entrar no mesmo cadastro de mídia é o que faz a página cair na
 * varredura periódica e no takedown que já existem — não há um segundo caminho de moderação. O lote
 * é próprio, e não o id do PDF: a rota pública de leitura descobre a mídia pelo primeiro trecho
 * `media_*` da chave, e assim a página nunca é confundida com o documento.</p>
 *
 * <p><b>O documento não fica no ar enquanto as páginas não foram olhadas:</b> renderizar põe o PDF
 * a caminho da remoção no próprio cadastro de mídia; a saída dele do ar (remover a peça, a
 * publicação, parar de servir o arquivo) é decisão de quem pergunta "as páginas estão limpas?".
 * Se alguma página for barrada, o documento e as páginas saem juntos.</p>
 *
 * <p><b>Quem faz o quê:</b> a subida enfileira o render ({@link #renderLater}). A cada folha gravada
 * o pedido passa a RENDERED — as folhas existem, e é isso que decide se a mídia pode ir ao ar —;
 * PENDING é render a fazer (fila da subida, processo sem bucket), FAILED é recusa registrada. O
 * desenho interrompido não fica escondido: a conferência das folhas recusa o documento se a soma
 * das páginas for menor que o arquivo. A entrega das páginas à análise é uma fila única,
 * esvaziada pela varredura de segurança: analisar antes do render encontraria documento sem página
 * para classificar, e o documento inteiro seria barrado.</p>
 *
 * <p>Sem renderizador ou sem bucket (teste, pod sem credencial), o pedido fica PENDING e o worker
 * recolhe. O PDF sem página nunca fica liberado por acidente: o catálogo de páginas responde vazio,
 * e vazio não libera nada.</p>
 */
@Service
public class MediaPageRenderService {

	private static final Logger LOG = LoggerFactory.getLogger(MediaPageRenderService.class);

	/**
	 * Prefixo das páginas no bucket: `paginas/<lote>/pagina-<n>.png`.
	 *
	 * <p>Fora do prefixo `uploads/`, que é o que a CDN serve ao público: página de PDF é
	 * intermediária da renderização, não saída. Quem mostra PDF aos leitores é o leitor que desenha
	 * o documento, e o documento só continua no bucket se as páginas passarem.</p>
	 */
	static final String PAGES_PREFIX = "paginas/";
	private static final String PDF_MIME = "application/pdf";
	private static final String PAGE_MIME = "image/png";

	private final MediaAssetRepository assets;
	private final MediaRenderJobRepository jobs;
	private final MediaPageSets pages;
	private final MediaDeletionJobRepository deletions;
	private final ObjectProvider<MediaPageRenderer> renderer;
	private final ObjectProvider<MediaObjectStore> storeProvider;
	private final StorageUrlFactory urlFactory;
	private final MediaImageModerationService moderation;
	private final MediaPageRenderExecutor executor;
	private final Clock clock;
	private final int maxPages;
	private final TransactionTemplate transaction;

	public MediaPageRenderService(MediaAssetRepository assets, MediaRenderJobRepository jobs, MediaPageSets pages,
			MediaDeletionJobRepository deletions, ObjectProvider<MediaPageRenderer> renderer,
			ObjectProvider<MediaObjectStore> storeProvider, StorageUrlFactory urlFactory,
			MediaImageModerationService moderation,
			@Qualifier("mediaPageRenderExecutor") MediaPageRenderExecutor executor, Clock clock,
			PlatformTransactionManager transactionManager, @Value("${rede.media.pages.max:20}") int maxPages) {
		this.assets = assets;
		this.jobs = jobs;
		this.pages = pages;
		this.deletions = deletions;
		this.renderer = renderer;
		this.storeProvider = storeProvider;
		this.urlFactory = urlFactory;
		this.moderation = moderation;
		this.executor = executor;
		this.clock = clock;
		this.transaction = new TransactionTemplate(transactionManager);
		this.maxPages = Math.max(1, Math.min(maxPages, MediaPageRenderer.MAX_PAGES));
	}

	/**
	 * Pede o render do PDF recém-subido, sem segurar a resposta da subida, e põe o documento na fila
	 * de análise. O desenho é o mesmo caminho do job já PENDING: não há dois jeitos de um PDF virar
	 * página. Quem vê o desenho terminar pede as folhas gravadas.
	 */
	public void renderLater(MediaAsset asset) {
		if (!isPdf(asset)) {
			return;
		}
		executor.renderSoon(asset, this::render);
		// A análise é pedida agora: com a fila cheia ou o pod caindo a tarefa some, e o documento
		// continua na fila da varredura, que pergunta por dono mesmo sem o pedido da tarefa. Quem
		// detecta o fim do desenho (a varredura de render) é que pede as folhas gravadas.
		pages.checkSoon(asset.id());
	}

	/**
	 * Renderiza o documento e, com todas as folhas no ar, pede a análise: é a forma de quem detectou
	 * o fim do desenho. Análise que chega antes do desenho enxerga documento sem página.
	 */
	public void renderAndCheck(MediaAsset asset) {
		render(asset);
		if (renderedAndIntact(asset)) {
			pages.checkSoon(asset.id());
		}
	}

	/** Todas as folhas existem, estão no ar e pertencem a um render concluído. */
	private boolean renderedAndIntact(MediaAsset asset) {
		return jobs.find(asset.id())
			.filter(job -> job.status() == MediaRenderJobStatus.RENDERED && !job.pageKeyList().isEmpty())
			.filter(job -> !pagesHaveGone(job))
			.isPresent();
	}

	/**
	 * Render do documento. FAILED não é refeito. RENDERED não é redesenhado — redesenhar devolveria
	 * a folha que a barreira tirou. PENDING é render a fazer: com página gravada (processo caiu entre
	 * a primeira folha e o registro) refaz o desenho e, depois da conferência, desfaz as folhas que
	 * ficaram; sem página, renderiza do zero com a remoção do documento pedida na mesma transação.
	 */
	public void render(MediaAsset asset) {
		if (!isPdf(asset)) {
			return;
		}
		Optional<MediaRenderJob> existing = jobs.find(asset.id());
		if (existing.isPresent()) {
			MediaRenderJob job = existing.get();
			if (job.status() == MediaRenderJobStatus.FAILED) {
				return;
			}
			if (job.status() == MediaRenderJobStatus.RENDERED) {
				// Já desenhado: a análise é pedida por quem chama o render, e só com as folhas no ar.
				return;
			}
			if (!job.pageKeyList().isEmpty()) {
				// Pedido com página gravada: o desenho é refeito e as folhas antigas saem na conferência.
				renderJob(asset);
				return;
			}
		}
		renderWithdrawing(asset);
	}

	/**
	 * Recolhe o que a subida deixou parado: pedidos PENDING (sem página, com a fila cheia ou sem
	 * bucket; com página, quando o processo caiu entre a primeira folha e o registro) parados há mais
	 * que a carência. O carimbo é posto antes de desenhar, para outra rodada não recolher o mesmo
	 * documento no meio do trabalho.
	 */
	public int sweep(int batch, Duration grace) {
		int handled = 0;
		Instant before = clock.instant().minus(grace);
		for (MediaRenderJob candidate : jobs.findPending(batch, before)) {
			if (resume(candidate.mediaId(), before)) {
				handled++;
			}
		}
		return handled;
	}

	/** Refaz o pedido relendo-o antes: o que saiu de PENDING, ou foi carimbado depois da carência, fica como está. */
	private boolean resume(String mediaId, Instant before) {
		Optional<MediaRenderJob> current = jobs.find(mediaId);
		if (current.isEmpty()) {
			return false;
		}
		MediaRenderJob job = current.get();
		if (job.status() != MediaRenderJobStatus.PENDING || job.updatedAt().isAfter(before)) {
			return false;
		}
		Optional<MediaAsset> asset = assets.findById(mediaId);
		if (asset.isEmpty() || !isPdf(asset.get())) {
			jobs.save(job.failed("asset ausente ou nao e pdf", clock.instant()));
			return false;
		}
		// Carimbo antes de desenhar: a rodada seguinte não recolhe o mesmo documento pela metade.
		jobs.save(new MediaRenderJob(job.mediaId(), job.ownerId(), job.status(), job.attempt(), job.pages(),
			job.pageKeys(), job.lastError(), job.createdAt(), clock.instant()));
		try {
			renderAndCheck(asset.get());
			return true;
		}
		catch (RuntimeException failure) {
			jobs.save(currentJob(asset.get()).failed(errorOf(failure), clock.instant()));
			return false;
		}
	}

	/**
	 * Pedido de remoção do documento e das páginas de uma tentativa anterior, na mesma transação: ou
	 * todos existem — e o worker de remoção apaga o documento e o que ficou de páginas —, ou nenhum.
	 * Pedido parcial deixaria documento no ar sem as páginas. A remoção do documento também faz o PDF
	 * sair do ar enquanto as páginas não foram olhadas.
	 */
	private void renderWithdrawing(MediaAsset asset) {
		Instant now = clock.instant();
		transaction.executeWithoutResult(status -> {
			deletions.request(newId("media_deletion_job"), asset.id(), null, asset.ownerId(), now);
			jobs.ensurePending(MediaRenderJob.pending(asset.id(), asset.ownerId().value(), now));
		});
		try {
			renderJob(asset);
		}
		catch (RuntimeException failure) {
			// Falha do render não sobe daqui: a subida já gravou o arquivo e não pode responder erro por
			// causa de uma página. O pedido fica FAILED — documento sem página não é liberável.
			jobs.save(currentJob(asset).failed(errorOf(failure), clock.instant()));
			LOG.warn("media_pages_render_failed mediaId={} motivo={}", asset.id(), errorOf(failure));
		}
	}

	/** Pede a remoção das páginas de uma tentativa anterior (idempotente no cadastro de remoção). */
	private void withdrawPages(List<String> pageKeys, Instant now) {
		for (MediaAsset page : pagesOf(pageKeys)) {
			deletions.request(newId("media_deletion_job"), page.id(), null, page.ownerId(), now);
		}
	}

	/**
	 * Desenha as páginas, confere cada uma ANTES de gravar e registra o pedido como RENDERED.
	 *
	 * <p>Sem renderizador ou sem bucket o pedido fica PENDING: a varredura recolhe quando as peças
	 * técnicas existirem. Página recusada pelo classificador nunca chega ao bucket, e o pedido fica
	 * FAILED com as páginas já gravadas retiradas — meia página no ar não serve.</p>
	 */
	void renderJob(MediaAsset asset) {
		MediaPageRenderer render = renderer.getIfAvailable();
		MediaObjectStore store = storeProvider.getIfAvailable();
		if (render == null || store == null) {
			LOG.info("media_pages_deferred mediaId={} render={} store={}", asset.id(), render != null,
				store != null);
			return;
		}
		List<byte[]> rendered;
		try {
			rendered = render.renderPages(read(store, asset));
		}
		catch (PageRenderException | IOException | RuntimeException failure) {
			// Folha desenhada nesta tentativa nunca foi conferida: sai junto com o pedido, para nenhuma
			// parte do arquivo barrado ficar no ar.
			jobs.save(withoutPages(currentJob(asset)).failed(errorOf(failure), clock.instant()));
			LOG.warn("media_pages_render_failed mediaId={} motivo={}", asset.id(), errorOf(failure));
			return;
		}
		if (rendered.isEmpty()) {
			jobs.save(withoutPages(currentJob(asset)).failed("pdf sem pagina", clock.instant()));
			return;
		}
		List<byte[]> checked = rendered.subList(0, Math.min(rendered.size(), maxPages));
		for (byte[] page : checked) {
			// A página é conferida ANTES de existir no bucket: o que não passa não chega ao storage.
			moderation.verifyBeforeStorage(page, PAGE_MIME);
		}
		// Tentativa anterior: as folhas que ficaram no ar nunca foram olhadas, e a barreira precisa ver o
		// documento inteiro. Só depois da conferência elas são disputadas — render que não passa não
		// apaga página alheia.
		withdrawPages(currentJob(asset).pageKeyList(), clock.instant());
		List<String> written;
		try {
			written = writePages(asset, store, checked);
		}
		catch (RuntimeException failure) {
			// O que já foi gravado nesta tentativa não fica no ar esperando análise: sai junto.
			withdrawWritten(asset);
			jobs.save(withoutPages(currentJob(asset)).failed(errorOf(failure), clock.instant()));
			throw failure;
		}
		// RENDERED é o fim do desenho, e vem depois das folhas: ele é conferido pela chave das folhas,
		// e o documento nunca responde como desenhado antes de as folhas existirem.
		jobs.save(currentJob(asset).rendered(written, clock.instant()));
		LOG.info("media_pages_rendered mediaId={} paginas={}", asset.id(), written.size());
		// As folhas vão à análise: um desenho interrompido no meio deixa página no ar, e ela precisa
		// ser olhada do mesmo jeito.
		pages.checkSoon(asset.id());
	}

	/**
	 * O pedido de remoção do PDF só apaga depois que o render terminou; enquanto isso cada página
	 * gravada ganha o próprio pedido de remoção, para que o delete também a alcance.
	 */
	private List<String> writePages(MediaAsset pdf, MediaObjectStore store, List<byte[]> rendered) {
		String pageBatchId = "media_" + UUID.randomUUID().toString().replace("-", "");
		Instant now = clock.instant();
		List<String> written = new ArrayList<>();
		for (int page = 1; page <= rendered.size(); page++) {
			byte[] png = rendered.get(page - 1);
			String mediaId = newId("media");
			String objectKey = PAGES_PREFIX + pageBatchId + "/pagina-" + page + ".png";
			MediaAsset pageAsset = MediaAsset.pending(mediaId, pdf.ownerId(), pageName(pdf, page), PAGE_MIME,
				png.length, null, objectKey, urlFactory.cdnUrl(objectKey), now, pdf.expiresAt(),
				pdf.storageProvider() == null ? MediaStorageProvider.S3 : pdf.storageProvider());
			try {
				store.write(objectKey, PAGE_MIME, png.length, new java.io.ByteArrayInputStream(png));
			}
			catch (RuntimeException failure) {
				// Meia página gravada não serve: o documento fica sem página e a peça continua bloqueada.
				throw new ApiException(502, "MEDIA_PAGE_WRITE_FAILED",
					"Nao foi possivel preparar as paginas do arquivo agora.", failure);
			}
			written.add(objectKey);
			// Asset e chave no pedido na mesma transação: o pedido é o que diz que a folha existe, e a
			// varredura a achega pela chave. Uma não pode existir sem a outra.
			transaction.executeWithoutResult(status -> {
				assets.save(pageAsset);
				deletions.request(newId("media_deletion_job"), pageAsset.id(), null, pageAsset.ownerId(), now);
				jobs.save(currentJob(pdf).withPages(written, clock.instant()));
			});
		}
		return written;
	}

	/** Alguma folha do documento saiu do ar (barrada, retirada): o documento não é peça completa. */
	private boolean pagesHaveGone(MediaRenderJob job) {
		List<MediaAsset> found = pagesOf(job.pageKeyList());
		return found.size() != job.pageKeyList().size() || found.stream().anyMatch(page -> !page.isVisible());
	}

	/** Chaves das páginas → assets de página (a chave é única por asset). */
	private List<MediaAsset> pagesOf(List<String> pageKeys) {
		if (pageKeys == null || pageKeys.isEmpty()) {
			return List.of();
		}
		return pageKeys.stream()
			.filter(key -> key != null && key.startsWith(PAGES_PREFIX))
			.map(assets::findByObjectKey)
			.flatMap(Optional::stream)
			.toList();
	}

	/** Página gravada nunca conferida não fica no pedido: ela é pedida para remoção e a falha fecha o documento. */
	private void withdrawWritten(MediaAsset pdf) {
		List<String> keys = currentJob(pdf).pageKeyList();
		withdrawPages(keys, clock.instant());
		jobs.save(withoutPages(currentJob(pdf)));
	}

	/** O pedido sem as chaves de folha: página desenhada que não passou não conta como peça. */
	private MediaRenderJob withoutPages(MediaRenderJob job) {
		return job.pageKeyList().isEmpty() ? job
			: new MediaRenderJob(job.mediaId(), job.ownerId(), job.status(), job.attempt(), 0, null,
				job.lastError(), job.createdAt(), job.updatedAt());
	}

	private MediaRenderJob currentJob(MediaAsset asset) {
		return jobs.find(asset.id()).orElseThrow(() -> new IllegalStateException("pedido de paginas ausente"));
	}

	private static String pageName(MediaAsset pdf, int page) {
		String name = pdf.fileName() == null ? "documento" : pdf.fileName();
		String base = name.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")
			? name.substring(0, name.length() - 4)
			: name;
		return base + " - pagina " + page;
	}

	private static byte[] read(MediaObjectStore store, MediaAsset asset) throws IOException {
		try (InputStream body = store.read(asset.objectKey(), null).body()) {
			return body.readAllBytes();
		}
	}

	/** `paginas/<lote>/pagina-<n>.png` → `media_<lote>`. */
	static String mediaIdOfPage(String objectKey) {
		if (objectKey == null || !objectKey.startsWith(PAGES_PREFIX)) {
			return null;
		}
		String rest = objectKey.substring(PAGES_PREFIX.length());
		int slash = rest.indexOf('/');
		return slash <= 0 ? null : rest.substring(0, slash);
	}

	private static boolean isPdf(MediaAsset asset) {
		return asset.mimeType() != null && PDF_MIME.equals(asset.mimeType());
	}

	private static String newId(String prefix) {
		return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
	}

	private static String errorOf(Throwable failure) {
		if (failure instanceof ApiException api) {
			return api.getMessage();
		}
		return failure.getClass().getSimpleName();
	}
}
