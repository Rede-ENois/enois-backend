package com.socialcoisa.rede.figurinhas.domain;

import java.util.Locale;

import com.socialcoisa.rede.shared.web.ApiException;

/**
 * O que uma imagem de figurinha pode ser: PNG, JPEG, GIF animado ou SVG desenhado, no máximo
 * 400x400 (o SVG pelo viewBox) e 512KB.
 *
 * <p>O SVG não fica como arquivo: o servidor sanitiza o vetor e o serve como {@code image/svg+xml}
 * (o pacote oficial de figurinhas já é SVG, e animação SMIL é figura animada). No catálogo, todo
 * envio é PNG, JPEG, GIF ou SVG.</p>
 *
 * <p>As recusas têm código próprio para o cliente mostrar o motivo certo (tipo, tamanho, dimensão)
 * — a mensagem nunca carrega detalhe de infraestrutura.</p>
 */
public final class FigurinhaImagem {

	public static final String PNG = "image/png";
	public static final String JPEG = "image/jpeg";
	public static final String GIF = "image/gif";
	public static final String SVG = "image/svg+xml";

	/** Lado máximo em pixels: a figurinha aparece no texto, não é uma foto. */
	public static final int MAX_LADO_PX = 400;
	/** Abaixo disso a imagem vira borrão na tela; recusa cedo em vez de publicar lixo. */
	public static final int MAX_LADO_MINIMO_PX = 16;
	public static final int MAX_BYTES = 512 * 1024;

	private FigurinhaImagem() {
	}

	public static String normalizarMime(String mime) {
		String valor = mime == null ? "" : mime.trim().toLowerCase(Locale.ROOT);
		return "image/jpg".equals(valor) ? JPEG : valor;
	}

	public static boolean mimeAceito(String mime) {
		String normalizado = normalizarMime(mime);
		return PNG.equals(normalizado) || JPEG.equals(normalizado) || GIF.equals(normalizado)
				|| SVG.equals(normalizado);
	}

	/** Nome do arquivo para o asset: a extensão acompanha o formato que a pessoa escolheu. */
	public static String nomeDoArquivo(String mime) {
		String normalizado = normalizarMime(mime);
		if (JPEG.equals(normalizado)) {
			return "figurinha.jpg";
		}
		if (GIF.equals(normalizado)) {
			return "figurinha.gif";
		}
		if (SVG.equals(normalizado)) {
			return "figurinha.svg";
		}
		return "figurinha.png";
	}

	/** Confere a declaração do passo 1 (tipo e tamanho) e devolve o tipo normalizado. */
	public static String exigirDeclaracaoValida(String mimeType, long tamanho) {
		String mime = normalizarMime(mimeType);
		if (!mimeAceito(mime)) {
			throw new ApiException(400, "FIGURINHA_TIPO_NAO_PERMITIDO",
				"Envie a figurinha em PNG, JPG, GIF ou SVG.");
		}
		if (tamanho <= 0) {
			throw new ApiException(400, "FIGURINHA_TAMANHO_INVALIDO", "A imagem enviada está vazia.");
		}
		if (tamanho > MAX_BYTES) {
			throw grande();
		}
		return mime;
	}

	public static void exigirDimensoes(int largura, int altura) {
		if (largura <= 0 || altura <= 0) {
			throw new ApiException(400, "FIGURINHA_IMAGEM_INVALIDA",
				"O arquivo não é uma imagem PNG, JPG, GIF ou SVG válida.");
		}
		if (largura < MAX_LADO_MINIMO_PX || altura < MAX_LADO_MINIMO_PX) {
			throw new ApiException(400, "FIGURINHA_MUITO_PEQUENA",
				"A imagem precisa ter pelo menos " + MAX_LADO_MINIMO_PX + "x" + MAX_LADO_MINIMO_PX + " pixels.");
		}
		if (largura > MAX_LADO_PX || altura > MAX_LADO_PX) {
			throw new ApiException(400, "FIGURINHA_MUITO_GRANDE",
				"A imagem pode ter no máximo " + MAX_LADO_PX + "x" + MAX_LADO_PX + " pixels.");
		}
	}

	public static ApiException grande() {
		return new ApiException(400, "FIGURINHA_ARQUIVO_GRANDE",
			"A imagem deve ter no máximo " + (MAX_BYTES / 1024L) + "KB.");
	}
}
