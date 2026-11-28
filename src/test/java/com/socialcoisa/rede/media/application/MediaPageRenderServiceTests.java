package com.socialcoisa.rede.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.media.adapters.out.render.PdfBoxPageRenderer;
import com.socialcoisa.rede.media.adapters.out.render.PdfBoxPageRendererTests;
import com.socialcoisa.rede.media.application.port.out.MediaAssetRepository;
import com.socialcoisa.rede.media.application.port.out.MediaDeletionJobRepository;
import com.socialcoisa.rede.media.application.port.out.MediaObjectStore;
import com.socialcoisa.rede.media.application.port.out.MediaPageSets;
import com.socialcoisa.rede.media.application.port.out.MediaRenderJobRepository;
import com.socialcoisa.rede.media.application.port.out.StorageUrlFactory;
import com.socialcoisa.rede.media.domain.MediaAsset;
import com.socialcoisa.rede.media.domain.MediaRenderJob;
import com.socialcoisa.rede.media.domain.MediaRenderJobStatus;
import com.socialcoisa.rede.media.domain.MediaStorageProvider;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * O PDF só pode ir ao ar depois que cada página foi olhada pelo classificador — e a página
 * explícita não pode chegar ao bucket nem por um instante.
 */
class MediaPageRenderServiceTests {

	private static final Clock RELOGIO = Clock.fixed(Instant.parse("2026-11-25T12:00:00Z"), ZoneOffset.UTC);
	private static final String PDF_MIME = "application/pdf";

	private MediaAssetRepository assets;
	private MediaRenderJobRepository jobs;
	private MediaDeletionJobRepository deletions;
	private MediaPageSets pages;
	private MediaObjectStore store;
	private MediaImageModerationService moderation;
	private MapStore mediaStore;
	private final List<MediaAsset> pageAssets = new ArrayList<>();
	private MediaRenderJob job;
	private MediaPageRenderService service;

	@BeforeEach
	void preparar() {
		assets = mock(MediaAssetRepository.class);
		jobs = mock(MediaRenderJobRepository.class);
		deletions = mock(MediaDeletionJobRepository.class);
		pages = mock(MediaPageSets.class);
		store = mock(MediaObjectStore.class);
		moderation = mock(MediaImageModerationService.class);
		mediaStore = new MapStore();
		job = MediaRenderJob.pending("media_pdf", "user_1", RELOGIO.instant());

		when(jobs.find(anyString())).thenReturn(Optional.empty());
		when(jobs.ensurePending(any())).thenAnswer(invocation -> {
			// O job é mutável por natureza (PENDING → RENDERED/FAILED): o double reflete o que foi pago.
			job = invocation.getArgument(0);
			return job;
		});
		when(jobs.save(any())).thenAnswer(invocation -> {
			job = invocation.getArgument(0);
			return job;
		});
		when(assets.save(any())).thenAnswer(invocation -> {
			MediaAsset saved = invocation.getArgument(0);
			pageAssets.add(saved);
			// A chave é a única com que a busca por objeto encontra a página depois.
			when(assets.findByObjectKey(saved.objectKey())).thenReturn(Optional.of(saved));
			return saved;
		});
		when(jobs.find("media_pdf")).thenAnswer(invocation -> Optional.of(job));
		service = service(new PdfBoxPageRenderer(), mediaStore, moderation);
	}

	/** Executor que guarda as tarefas: o teste decide quando o render roda, como no pod. */
	private final List<Runnable> tarefas = new ArrayList<>();

	private MediaPageRenderService service(com.socialcoisa.rede.media.application.port.out.MediaPageRenderer renderer,
			MediaObjectStore objectStore, MediaImageModerationService nsfw) {
		MediaPageRenderExecutor executor = new MediaPageRenderExecutor(tarefas::add);
		return new MediaPageRenderService(assets, jobs, pages, deletions, provider(renderer), provider(objectStore),
			urlFactory(), nsfw, executor, RELOGIO, new TransacaoSemBanco(), 20);
	}

	/** O caminho da subida: põe o render na fila e roda a tarefa, que também pede a checagem. */
	private void renderizar(MediaAsset asset) {
		service.renderLater(asset);
		List<Runnable> fila = List.copyOf(tarefas);
		tarefas.clear();
		fila.forEach(Runnable::run);
	}

	private static <T> ObjectProvider<T> provider(T value) {
		return new ObjectProvider<>() {
			@Override
			public T getObject(Object... args) {
				return value;
			}

			@Override
			public T getIfAvailable() {
				return value;
			}

			@Override
			public T getObject() {
				return value;
			}
		};
	}

	/** Transações de teste: sem banco, a marcação de rollback só precisa ser aceita. */
	static final class TransacaoSemBanco extends AbstractPlatformTransactionManager {

		private static final long serialVersionUID = 1L;

		@Override
		protected Object doGetTransaction() {
			return new Object();
		}

		@Override
		protected void doBegin(Object transaction, TransactionDefinition definition) {
		}

		@Override
		protected void doCommit(DefaultTransactionStatus status) {
		}

		@Override
		protected void doRollback(DefaultTransactionStatus status) {
		}
	}

	private static StorageUrlFactory urlFactory() {
		return new StorageUrlFactory() {
			@Override
			public MediaStorageProvider provider() {
				return MediaStorageProvider.S3;
			}

			@Override
			public String objectKey(String mediaId, String fileName) {
				return "uploads/" + mediaId + "/" + fileName;
			}

			@Override
			public String cdnUrl(String objectKey) {
				return "https://cdn.exemplo.com/" + objectKey;
			}

			@Override
			public String uploadUrl(String objectKey) {
				return cdnUrl(objectKey);
			}
		};
	}

	@Test
	void pdfLimpoGravaUmaPaginaPorFolhaERelevaCadaUmaAChecagem() throws Exception {
		byte[] pdf = PdfBoxPageRendererTests.pdfCom(2, false);
		mediaStore.put("uploads/media_pdf/file.pdf", pdf);

		renderizar(pdf("media_pdf", "user_1"));

		assertThat(pageAssets).hasSize(2);
		assertThat(pageAssets).allSatisfy(page -> {
			assertThat(page.mimeType()).isEqualTo("image/png");
			assertThat(page.ownerId().value()).isEqualTo("user_1");
			assertThat(page.objectKey()).matches("paginas/media_[0-9a-f]+/pagina-[12]\\.png");
		});
		// Toda página passa pelo classificador ANTES de existir no bucket.
		verify(moderation, org.mockito.Mockito.times(2)).verifyBeforeStorage(any(), eq("image/png"));
		verify(moderation, never()).verifyBeforeStorage(any(), eq(PDF_MIME));
		assertThat(mediaStore.written()).hasSize(2);
		assertThat(job.status()).isEqualTo(MediaRenderJobStatus.RENDERED);
		// A subida põe o documento na fila e o fim do desenho o repõe: quem esvazia a fila é a
		// varredura, depois de o render terminar. Pedido repetido não refaz página, o ledger impede.
		verify(pages, org.mockito.Mockito.times(2)).checkSoon("media_pdf");
	}

	@Test
	void oPdfRecebePedidoDeRemocaoAntesDeSerRenderizado() throws Exception {
		byte[] pdf = PdfBoxPageRendererTests.pdfCom(1, false);
		mediaStore.put("uploads/media_pdf/file.pdf", pdf);

		renderizar(pdf("media_pdf", "user_1"));

		// O documento sai do ar mesmo que alguma página seja barrada: a remoção já está pedida, e o
		// worker só apaga depois que o render leu o arquivo.
		verify(deletions).request(anyString(), eq("media_pdf"), any(), any(), any());
	}

	@Test
	void paginaGravadaTambemEntraNoWorkerDeRemocao() throws Exception {
		byte[] pdf = PdfBoxPageRendererTests.pdfCom(2, false);
		mediaStore.put("uploads/media_pdf/file.pdf", pdf);

		renderizar(pdf("media_pdf", "user_1"));

		for (MediaAsset page : pageAssets) {
			verify(deletions).request(anyString(), eq(page.id()), any(), any(), any());
		}
	}

	@Test
	void paginaBloqueadaImpedeQualquerPaginaDeChegarAoBucket() throws Exception {
		byte[] pdf = PdfBoxPageRendererTests.pdfCom(3, false);
		mediaStore.put("uploads/media_pdf/file.pdf", pdf);
		doThrow(new ApiException(422, "IMAGE_CONTENT_NOT_ALLOWED", "conteudo proibido"))
			.when(moderation).verifyBeforeStorage(any(), eq("image/png"));

		// A recusa não sobe: a subida já gravou o arquivo e não pode responder erro por uma página.
		renderizar(pdf("media_pdf", "user_1"));

		// O render para na primeira página recusada: nada é gravado, nenhuma página fica servível.
		assertThat(mediaStore.written()).isEmpty();
		assertThat(pageAssets).isEmpty();
		verify(assets, never()).save(any());
		// A recusa fica registrada: o documento não volta a ser renderizado a cada página.
		assertThat(job.status()).isEqualTo(MediaRenderJobStatus.FAILED);
		// Sem folha no ar não há o que analisar: só o pedido da subida, nenhum depois do render.
		verify(pages, org.mockito.Mockito.times(1)).checkSoon("media_pdf");
	}

	@Test
	void falhaAoGravarPaginaDesfazOPedidoEmVezDeDeixarMeiaPaginaNoAr() throws Exception {
		byte[] pdf = PdfBoxPageRendererTests.pdfCom(2, false);
		mediaStore.put("uploads/media_pdf/file.pdf", pdf);
		MediaObjectStore quebrado = mock(MediaObjectStore.class);
		when(quebrado.read(anyString(), any())).thenReturn(new MediaObjectStore.StoredObject(
			new ByteArrayInputStream(pdf), pdf.length, PDF_MIME, null, null));
		doThrow(new IllegalStateException("bucket fora")).when(quebrado)
			.write(anyString(), anyString(), anyLong(), any());
		MediaPageRenderService comFalha = service(new PdfBoxPageRenderer(), quebrado, moderation);

		comFalha.renderAndCheck(pdf("media_pdf", "user_1"));

		assertThat(pageAssets).isEmpty();
		assertThat(job.status()).isEqualTo(MediaRenderJobStatus.FAILED);
	}

	@Test
	void pedidoPendenteQueChegouPelaVarreduraTambemERenderizado() throws Exception {
		// O pedido é o próprio registro do trabalho: PENDING é "falta desenhar", venha da subida ou da
		// varredura. A prova de que alguém já o pegou é a página gravada dentro do render.
		byte[] documento = PdfBoxPageRendererTests.pdfCom(1, false);
		mediaStore.put("uploads/media_pdf/file.pdf", documento);
		job = MediaRenderJob.pending("media_pdf", "user_1", RELOGIO.instant().minusSeconds(30));
		when(jobs.find("media_pdf")).thenReturn(Optional.of(job));

		renderizar(pdf("media_pdf", "user_1"));

		assertThat(pageAssets).hasSize(1);
		assertThat(job.status()).isEqualTo(MediaRenderJobStatus.RENDERED);
	}

	@Test
	void pdfIlegivelFicaComOPedidoFalhoESemPagina() {
		byte[] lixo = "%PDF-1.4 nada aqui".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		mediaStore.put("uploads/media_pdf/file.pdf", lixo);

		renderizar(pdf("media_pdf", "user_1"));

		assertThat(job.status()).isEqualTo(MediaRenderJobStatus.FAILED);
		assertThat(pageAssets).isEmpty();
		verifyNoInteractions(moderation);
	}

	@Test
	void imagemNaoTemPaginaParaRenderizar() {
		MediaAsset foto = MediaAsset.pending("media_foto", new UserId("user_1"), "foto.png", "image/png", 10, null,
			"uploads/media_foto/file.png", "https://cdn.exemplo.com/uploads/media_foto/file.png",
			RELOGIO.instant(), RELOGIO.instant().plusSeconds(3600), MediaStorageProvider.S3);

		service.render(foto);

		verify(jobs, never()).ensurePending(any());
		assertThat(pageAssets).isEmpty();
	}

	@Test
	void documentoJaRenderizadoNaoERedenhadoSoRefazAChecagem() {
		job = job.rendered(List.of("paginas/media_lote/pagina-1.png"), RELOGIO.instant());
		when(assets.findByObjectKey("paginas/media_lote/pagina-1.png"))
			.thenReturn(Optional.of(pagina("media_pagina", "paginas/media_lote/pagina-1.png")));

		renderizar(pdf("media_pdf", "user_1"));

		verify(jobs, never()).save(any());
		assertThat(pageAssets).isEmpty();
	}

	@Test
	void documentoQueFalhouNaoEReprocessado() {
		job = job.failed("pdf nao abriu", RELOGIO.instant());
		when(jobs.find("media_pdf")).thenReturn(Optional.of(job));

		renderizar(pdf("media_pdf", "user_1"));

		verify(jobs, never()).ensurePending(any());
	}

	@Test
	void semStorageOPedidoFicaPendenteParaAVarredura() {
		MediaPageRenderService semBucket = new MediaPageRenderService(assets, jobs, pages, deletions,
			provider(new PdfBoxPageRenderer()), provider((MediaObjectStore) null), urlFactory(), moderation,
			new MediaPageRenderExecutor(Runnable::run), RELOGIO, new TransacaoSemBanco(), 20);

		semBucket.render(pdf("media_pdf", "user_1"));

		assertThat(job.status()).isEqualTo(MediaRenderJobStatus.PENDING);
		verify(jobs, never()).save(any());
		assertThat(pageAssets).isEmpty();
		// Ainda sem página: o documento também não fica servível.
		verify(deletions).request(anyString(), eq("media_pdf"), any(), any(), any());
	}

	@Test
	void aVarreduraRecolheOPedidoInterrompidoEDesfazAsFolhasAntigas() throws Exception {
		// Processo caiu depois da primeira folha: pedido PENDING, sem registro final.
		byte[] upload = PdfBoxPageRendererTests.pdfCom(2, false);
		mediaStore.put("uploads/media_pdf/file.pdf", upload);
		MediaAsset primeira = pagina("media_primeira", "paginas/media_antigo/pagina-1.png");
		when(assets.findByObjectKey(primeira.objectKey())).thenReturn(Optional.of(primeira));
		Instant ontem = RELOGIO.instant().minusSeconds(3600);
		job = new MediaRenderJob("media_pdf", "user_1", MediaRenderJobStatus.PENDING, 0, 1, primeira.objectKey(),
			null, ontem, ontem);
		when(jobs.findPending(anyInt(), any())).thenReturn(List.of(job));
		when(jobs.find("media_pdf")).thenReturn(Optional.of(job));
		when(assets.findById("media_pdf")).thenReturn(Optional.of(pdf("media_pdf", "user_1")));

		int tratados = service.sweep(10, java.time.Duration.ofMinutes(5));

		assertThat(tratados).isEqualTo(1);
		assertThat(job.status()).isEqualTo(MediaRenderJobStatus.RENDERED);
		assertThat(job.pages()).isEqualTo(2);
		// A página do pedido pendente não entra no documento: o registro final conta só as novas.
		assertThat(job.pageKeyList()).noneMatch("paginas/media_antigo/pagina-1.png"::equals);
		// A folha que ficou no ar da tentativa anterior sai antes de servir de novo.
		verify(deletions).request(anyString(), eq("media_primeira"), any(), any(), any());
	}

	@Test
	void aVarreduraRetomaOPedidoSemPaginaQueASubidaNaoConcluiu() throws Exception {
		byte[] upload = PdfBoxPageRendererTests.pdfCom(1, false);
		mediaStore.put("uploads/media_pdf/file.pdf", upload);
		job = MediaRenderJob.pending("media_pdf", "user_1", RELOGIO.instant().minusSeconds(600));
		when(jobs.findPending(anyInt(), any())).thenReturn(List.of(job));
		when(jobs.find("media_pdf")).thenReturn(Optional.of(job));
		when(assets.findById("media_pdf")).thenReturn(Optional.of(pdf("media_pdf", "user_1")));

		int tratados = service.sweep(10, java.time.Duration.ofMinutes(5));

		assertThat(tratados).isEqualTo(1);
		assertThat(pageAssets).hasSize(1);
		assertThat(job.status()).isEqualTo(MediaRenderJobStatus.RENDERED);
	}

	@Test
	void aVarreduraDeixaEmPazORenderQueOutraThreadAcabouDeCarimbar() {
		Instant carimbo = RELOGIO.instant().minusSeconds(10);
		job = new MediaRenderJob("media_pdf", "user_1", MediaRenderJobStatus.PENDING, 0, 0, null, null,
			RELOGIO.instant().minusSeconds(600), carimbo);
		when(jobs.findPending(anyInt(), any())).thenReturn(List.of(job));
		when(jobs.find("media_pdf")).thenReturn(Optional.of(job));

		int tratados = service.sweep(10, java.time.Duration.ofMinutes(5));

		assertThat(tratados).isZero();
		assertThat(pageAssets).isEmpty();
	}

	@Test
	void aVarreduraNaoRecolheOPedidoQueOutraThreadAcabouDePegar() {
		Instant carimbo = RELOGIO.instant().minusSeconds(10);
		job = new MediaRenderJob("media_pdf", "user_1", MediaRenderJobStatus.PENDING, 0, 0, null, null,
			RELOGIO.instant().minusSeconds(600), carimbo);
		when(jobs.findPending(anyInt(), any())).thenReturn(List.of(job));
		when(jobs.find("media_pdf")).thenReturn(Optional.of(job));

		int tratados = service.sweep(10, java.time.Duration.ofSeconds(30));

		assertThat(tratados).isZero();
		verify(assets, never()).findById("media_pdf");
	}

	@Test
	void documentoRenderizadoComFolhaRetiradaNaoVoltaAAnalise() {
		job = job.rendered(List.of("paginas/media_lote/pagina-1.png"), RELOGIO.instant());
		MediaAsset retirada = pagina("media_pagina", "paginas/media_lote/pagina-1.png");
		retirada.requestDeletion(RELOGIO.instant());
		when(assets.findByObjectKey("paginas/media_lote/pagina-1.png")).thenReturn(Optional.of(retirada));

		renderizar(pdf("media_pdf", "user_1"));

		// Reanalisar as folhas restantes devolveria ao ar o documento que já perdeu uma delas.
		assertThat(pageAssets).isEmpty();
		// Só o pedido da subida: o render não manda analisar folhas que não estão no ar.
		verify(pages, org.mockito.Mockito.times(1)).checkSoon("media_pdf");
	}

	private static MediaAsset pagina(String id, String key) {
		return MediaAsset.pending(id, new UserId("user_1"), "pagina", "image/png", 10, null, key,
			"https://cdn.exemplo.com/" + key, RELOGIO.instant(), RELOGIO.instant().plusSeconds(3600),
			MediaStorageProvider.S3);
	}

	private static MediaAsset pdf(String id, String owner) {
		return MediaAsset.pending(id, new UserId(owner), "curriculo.pdf", PDF_MIME, 1024, null,
			"uploads/" + id + "/file.pdf", "https://cdn.exemplo.com/uploads/" + id + "/file.pdf", RELOGIO.instant(),
			RELOGIO.instant().plusSeconds(3600), MediaStorageProvider.S3);
	}

	/** Bucket de memória: guarda o que foi escrito e devolve o objeto que alguém colocou no lugar. */
	static final class MapStore implements MediaObjectStore {

		private final Map<String, byte[]> objects = new java.util.concurrent.ConcurrentHashMap<>();
		private final List<String> written = new ArrayList<>();

		void put(String key, byte[] content) {
			objects.put(key, content);
		}

		List<String> written() {
			return written;
		}

		@Override
		public StoredObject read(String key, String range) {
			byte[] content = objects.get(key);
			if (content == null) {
				throw new IllegalStateException("objeto ausente: " + key);
			}
			return new StoredObject(new ByteArrayInputStream(content), content.length, "application/octet-stream", null,
				null);
		}

		@Override
		public void write(String key, String contentType, long length, InputStream body) {
			try {
				objects.put(key, body.readAllBytes());
				written.add(key);
			}
			catch (java.io.IOException failure) {
				throw new IllegalStateException(failure);
			}
		}
	}
}
