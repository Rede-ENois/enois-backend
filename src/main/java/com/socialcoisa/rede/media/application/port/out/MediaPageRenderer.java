package com.socialcoisa.rede.media.application.port.out;

/**
 * Transforma as páginas de um PDF em imagens PNG, no servidor.
 *
 * <p>Existe porque o classificador de conteúdo explícito não lê PDF: ele olha imagem. Sem esta
 * peça, um currículo ou portfólio em PDF era o único tipo de mídia que chegava ao público sem
 * que nenhuma barreira tivesse visto o que está desenhado nas páginas.</p>
 *
 * <p>Quem chama trata o resultado como incerto em qualquer caso: vazio para documento sem página,
 * exceção para arquivo ilegível ou protegido por senha. Nunca é "nada para ver" — é "não deu
 * para ver".</p>
 */
public interface MediaPageRenderer {

	/** Teto de páginas renderizadas por documento; o resto fica sem página visível de propósito. */
	int MAX_PAGES = 20;

	/**
	 * Renderiza até {@link #MAX_PAGES} páginas em PNG. Cada imagem tem no máximo 1000px no lado
	 * maior: é o que o classificador precisa para ver o conteúdo, e não a resolução de impressão.
	 *
	 * @throws PageRenderException quando o documento não abre ou não desenha
	 */
	java.util.List<byte[]> renderPages(byte[] pdf) throws PageRenderException;

	/** O documento não foi lido. A mensagem vai para log e para a coluna de erro, sem sair ao usuário. */
	class PageRenderException extends Exception {

		public PageRenderException(String message, Throwable cause) {
			super(message, cause);
		}
	}
}
