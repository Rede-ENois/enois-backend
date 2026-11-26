package com.socialcoisa.rede.media.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.media.domain.MediaRenderJob;

/** Pedidos de render de páginas de PDF. */
public interface MediaRenderJobRepository {

	Optional<MediaRenderJob> find(String mediaId);

	/** Cria o pedido; se já existe um para o documento, devolve o que está gravado. */
	MediaRenderJob ensurePending(MediaRenderJob job);

	MediaRenderJob save(MediaRenderJob job);

	/** Pedidos ainda não executados, dos mais antigos para os mais novos (recuperação de falha). */
	List<MediaRenderJob> findPending(int limit, Instant updatedBefore);

	/**
	 * Documentos cujo render terminou com folha gravada (RENDERED, ou PENDING que caiu entre a
	 * primeira folha e o registro). A varredura de segurança confere por eles o que depende de página.
	 */
	List<MediaRenderJob> findWithPages(int limit);

	/**
	 * Documento dono desta folha, achado pela chave do objeto (`paginas/<lote>/pagina-<n>.png`). A folha
	 * é asset irmão do documento, não mídia de ninguém: a varredura só chega ao documento pelo pedido.
	 */
	Optional<MediaRenderJob> documentOfPageKey(String objectKey);
}
