package com.socialcoisa.rede.linkpreview.application.port.out;

import java.util.Optional;

import com.socialcoisa.rede.linkpreview.domain.ExternalLinkPreview;

/**
 * Le a miniatura que uma pagina externa publica sobre si mesma (Open Graph).
 *
 * <p>O endereco e escolhido por quem escreveu o post, entao e dado NAO CONFIAVEL: a leitura precisa
 * recusar destino fora da internet publica antes de buscar. Ausencia de resposta e um resultado
 * normal — a pagina simplesmente nao se descreve — e nunca uma excecao que derruba o post.</p>
 */
public interface LinkPreviewReader {

	Optional<ExternalLinkPreview> read(String url);
}
