package com.socialcoisa.rede.media.application;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.socialcoisa.rede.media.domain.MediaAsset;

/**
 * Dispara o render das páginas fora da requisição de subida.
 *
 * <p>Desenhar 20 folhas e chamar o classificador 20 vezes leva segundos: feito na requisição de
 * subida, seguraria o "enviar" de quem só queria mandar um currículo. Feito no executor e não numa
 * transação aberta, porque o trabalho é CPU (PDFBox) e chamada de modelo, não banco.</p>
 *
 * <p>Fila cheia ou executor ausente (teste que monta o serviço na mão) não perdem o pedido: o
 * trabalho é descartado aqui e o worker de recuperação recolhe o que ficou PENDING.</p>
 */
@Component
public class MediaPageRenderExecutor {

	private static final Logger LOG = LoggerFactory.getLogger(MediaPageRenderExecutor.class);

	private final Executor executor;

	MediaPageRenderExecutor(@Qualifier("mediaPageRenderPool") Executor executor) {
		this.executor = executor;
	}

	public void renderSoon(MediaAsset asset, Consumer<MediaAsset> work) {
		try {
			executor.execute(() -> {
				try {
					work.accept(asset);
				}
				catch (RuntimeException failure) {
					LOG.warn("media_pages_render_erro mediaId={} motivo={}", asset.id(),
						failure.getClass().getSimpleName());
				}
			});
		}
		catch (RuntimeException filaCheia) {
			LOG.warn("media_pages_render_descartado mediaId={}", asset.id());
		}
	}
}
