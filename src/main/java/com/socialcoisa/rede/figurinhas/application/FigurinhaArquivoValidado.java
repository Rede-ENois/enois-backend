package com.socialcoisa.rede.figurinhas.application;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import com.socialcoisa.rede.figurinhas.domain.FigurinhaImagem;
import com.socialcoisa.rede.media.application.ImageDecoding;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Arquivo recebido, conferido até o fim: tipo real, dimensões reais e nenhum formato disfarçado.
 *
 * <p>Um upload "PNG" pode ser HTML com `Content-Type: image/png`, um poliglota (PNG válido com uma
 * página HTML colada no fim) ou uma imagem de 5000x5000 comprimida em poucos KB. Nada disso passa:</p>
 * <ul>
 * <li>o tipo declarado só pode ser PNG ou JPG;</li>
 * <li>o começo do arquivo tem de ser exatamente a assinatura desse tipo;</li>
 * <li>nenhum formato ativo (HTML, SVG, XML, PDF, ZIP, ELF, GIF, RIFF) pode aparecer em lugar algum
 *   do arquivo — o arquivo inteiro é pequeno (teto de 512KB), então varrer tudo é barato;</li>
 * <li>o arquivo inteiro tem de decodificar como UMA imagem completa, dentro de 400x400;</li>
 * <li>o que não faz parte da imagem (bytes depois do fim, dados ativos em metadado) NÃO vai ao
 *   storage: quando há sobra, o arquivo é reescrito a partir da imagem decodificada.</li>
 * </ul>
 */
public final class FigurinhaArquivoValidado {

	private static final byte[] PNG_ASSINATURA = { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A };
	private static final byte[] JPEG_ASSINATURA = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF };
	/** Fim próprio de imagem: bytes depois dele são sobra. */
	private static final byte[] PNG_FIM = { 0, 0, 0, 0, 'I', 'E', 'N', 'D', (byte) 0xAE, 0x42, 0x60, (byte) 0x82 };
	private static final byte[] JPEG_FIM = { (byte) 0xFF, (byte) 0xD9 };

	/** Recortes de identificação de formatos que NUNCA podem passar por figurinha. */
	private static final byte[][] ASSINATURAS_PROIBIDAS = {
		"%PDF".getBytes(StandardCharsets.US_ASCII),
		{ 'P', 'K', 3, 4 },
		{ 0x7F, 'E', 'L', 'F' },
		"<?xml".getBytes(StandardCharsets.US_ASCII),
		"<svg".getBytes(StandardCharsets.US_ASCII),
		"<!DOCTYPE".getBytes(StandardCharsets.US_ASCII),
		"<html".getBytes(StandardCharsets.US_ASCII),
		"<script".getBytes(StandardCharsets.US_ASCII),
		"GIF8".getBytes(StandardCharsets.US_ASCII),
		"RIFF".getBytes(StandardCharsets.US_ASCII),
	};

	private FigurinhaArquivoValidado() {
	}

	/** Resultado da conferência: o tipo a gravar, as dimensões reais e o arquivo reescrito. */
	public record Validado(String mimeType, int largura, int altura, byte[] bytes) {
	}

	/** Confere o arquivo inteiro; lança 400 com código próprio quando algo não bate. */
	public static Validado conferir(byte[] bytes, String mimeDeclarado) {
		String mime = FigurinhaImagem.normalizarMime(mimeDeclarado);
		if (bytes == null || bytes.length == 0) {
			throw invalida();
		}
		if (bytes.length > FigurinhaImagem.MAX_BYTES) {
			throw FigurinhaImagem.grande();
		}
		for (byte[] proibida : ASSINATURAS_PROIBIDAS) {
			if (contemEmTodoOArquivo(bytes, proibida)) {
				throw invalida();
			}
		}
		String real;
		if (FigurinhaImagem.PNG.equals(mime) && comecaCom(bytes, PNG_ASSINATURA)) {
			real = FigurinhaImagem.PNG;
		}
		else if (FigurinhaImagem.JPEG.equals(mime) && comecaCom(bytes, JPEG_ASSINATURA)) {
			real = FigurinhaImagem.JPEG;
		}
		else {
			// Tipo declarado sem a assinatura correspondente: arquivo disfarçado.
			throw invalida();
		}
		// Lê a imagem INTEIRA (não só o cabeçalho): arquivo truncado, imagem que o leitor não
		// completa ou acima do teto de pixels falha aqui — o que vai ao storage é uma imagem só.
		ImageDecoding.Dimensions dimensoes = medir(bytes);
		if (dimensoes == null) {
			throw invalida();
		}
		FigurinhaImagem.exigirDimensoes(dimensoes.width(), dimensoes.height());
		byte[] limpo = reescrever(bytes, real, dimensoes);
		if (limpo.length > FigurinhaImagem.MAX_BYTES) {
			throw FigurinhaImagem.grande();
		}
		return new Validado(real, dimensoes.width(), dimensoes.height(), limpo);
	}

	/**
	 * Reescreve a imagem a partir do que foi decodificado quando o arquivo tem sobra em volta. O
	 * desenho é o mesmo; o arquivo gravado é um PNG/JPG novo, sem "o resto" e sem metadado.
	 */
	private static byte[] reescrever(byte[] bytes, String mime, ImageDecoding.Dimensions dimensoes) {
		if (!imagemTemSobra(bytes, mime)) {
			return bytes;
		}
		try {
			java.awt.image.BufferedImage imagem = ImageDecoding.read(new java.io.ByteArrayInputStream(bytes));
			if (imagem == null) {
				throw invalida();
			}
			int tipo = FigurinhaImagem.PNG.equals(mime)
				? (imagem.getColorModel().hasAlpha() ? java.awt.image.BufferedImage.TYPE_INT_ARGB
					: java.awt.image.BufferedImage.TYPE_INT_RGB)
				: java.awt.image.BufferedImage.TYPE_INT_RGB;
			java.awt.image.BufferedImage saida = new java.awt.image.BufferedImage(dimensoes.width(),
				dimensoes.height(), tipo);
			java.awt.Graphics2D pincel = saida.createGraphics();
			try {
				pincel.drawImage(imagem, 0, 0, null);
			}
			finally {
				pincel.dispose();
			}
			java.io.ByteArrayOutputStream arquivo = new java.io.ByteArrayOutputStream();
			if (!javax.imageio.ImageIO.write(saida, FigurinhaImagem.PNG.equals(mime) ? "png" : "jpg", arquivo)) {
				throw invalida();
			}
			return arquivo.toByteArray();
		}
		catch (java.io.IOException falha) {
			throw invalida();
		}
		catch (RuntimeException falha) {
			if (falha instanceof ApiException recusa) {
				throw recusa;
			}
			throw invalida();
		}
	}

	/** Só é "sem sobra" o arquivo cujo fim é exatamente o fim declarado do formato. */
	private static boolean imagemTemSobra(byte[] bytes, String mime) {
		return !terminaCom(bytes, FigurinhaImagem.PNG.equals(mime) ? PNG_FIM : JPEG_FIM);
	}

	private static boolean terminaCom(byte[] bytes, byte[] sufixo) {
		if (bytes.length < sufixo.length) {
			return false;
		}
		int inicio = bytes.length - sufixo.length;
		for (int i = 0; i < sufixo.length; i++) {
			if (bytes[inicio + i] != sufixo[i]) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Assinatura de formato ativo em QUALQUER lugar do arquivo: um PNG válido com uma página HTML
	 * colada no fim é um poliglota — o navegador mostra a imagem, um "abrir o arquivo" mostra a
	 * página. Texto ativo em metadado de imagem também cai aqui.
	 */
	private static boolean contemEmTodoOArquivo(byte[] bytes, byte[] assinatura) {
		int limite = bytes.length - assinatura.length;
		for (int inicio = 0; inicio <= limite; inicio++) {
			boolean casa = true;
			for (int i = 0; i < assinatura.length; i++) {
				if (bytes[inicio + i] != assinatura[i]) {
					casa = false;
					break;
				}
			}
			if (casa) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Lê a imagem inteira (não só o cabeçalho): arquivo truncado ou imagem que o leitor não
	 * completa devolve null. O teto de pixels do decodificador vale aqui também.
	 */
	private static ImageDecoding.Dimensions medir(byte[] bytes) {
		try {
			java.awt.image.BufferedImage imagem = ImageDecoding.read(new java.io.ByteArrayInputStream(bytes));
			return imagem == null ? null : new ImageDecoding.Dimensions(imagem.getWidth(), imagem.getHeight());
		}
		catch (java.io.IOException | RuntimeException falha) {
			return null;
		}
	}

	private static boolean comecaCom(byte[] bytes, byte[] prefixo) {
		if (bytes.length < prefixo.length) {
			return false;
		}
		for (int i = 0; i < prefixo.length; i++) {
			if (bytes[i] != prefixo[i]) {
				return false;
			}
		}
		return true;
	}

	private static ApiException invalida() {
		return new ApiException(400, "FIGURINHA_IMAGEM_INVALIDA",
			"O arquivo não é uma imagem PNG ou JPG válida.");
	}
}
