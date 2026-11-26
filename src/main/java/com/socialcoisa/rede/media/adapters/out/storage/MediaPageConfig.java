package com.socialcoisa.rede.media.adapters.out.storage;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.socialcoisa.rede.media.adapters.out.render.PdfBoxPageRenderer;
import com.socialcoisa.rede.media.application.port.out.MediaPageRenderer;

/**
 * Peças do render de páginas de PDF.
 *
 * <p>O renderizador não é condicional ao storage: o PDFBox é dependência do projeto. O executor é
 * separado do automod de posts de propósito — desenhar folhas é CPU e não pode fazer fila na frente
 * de uma denúncia de conteúdo.</p>
 */
@Configuration(proxyBeanMethods = false)
class MediaPageConfig {

	private static final Logger LOG = LoggerFactory.getLogger(MediaPageConfig.class);

	@Bean
	MediaPageRenderer mediaPageRenderer() {
		return new PdfBoxPageRenderer();
	}

	@Bean(name = "mediaPageRenderPool", destroyMethod = "shutdown")
	ExecutorService mediaPageRenderExecutor(
			@Value("${rede.media.pages.executor-threads:1}") int threads,
			@Value("${rede.media.pages.executor-queue:100}") int queue) {
		int size = Math.max(1, Math.min(threads, 4));
		return new ThreadPoolExecutor(size, size, 0L, TimeUnit.MILLISECONDS,
			new ArrayBlockingQueue<>(Math.max(1, queue)), runnable -> {
				Thread thread = new Thread(runnable, "midia-paginas-pdf");
				thread.setDaemon(true);
				return thread;
			}, (runnable, pool) -> LOG.warn("fila do render de paginas cheia; trabalho fica para a varredura"));
	}
}
