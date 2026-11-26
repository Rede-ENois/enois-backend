package com.socialcoisa.rede.figurinhas.application;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import com.socialcoisa.rede.figurinhas.domain.FigurinhaImagem;
import com.socialcoisa.rede.media.application.ImageDecoding;
import com.socialcoisa.rede.shared.html.SafeHtml;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Arquivo recebido, conferido até o fim: tipo real, dimensões reais e nenhum formato disfarçado.
 *
 * <p>Um upload "PNG" pode ser HTML com `Content-Type: image/png`, um poliglota (PNG válido com uma
 * página HTML colada no fim) ou uma imagem de 5000x5000 comprimida em poucos KB. Nada disso passa:</p>
 * <ul>
 * <li>o tipo declarado só pode ser PNG, JPG, GIF ou SVG;</li>
 * <li>o começo do arquivo tem de ser exatamente a assinatura desse tipo — no GIF, o cabeçalho
 *   completo ({@code GIF87a} ou {@code GIF89a}), não só o começo;</li>
 * <li>nenhum formato ativo (HTML, XML, PDF, ZIP, ELF, RIFF, script inline) pode aparecer em lugar
 *   algum do arquivo — o arquivo inteiro é pequeno (teto de 512KB), então varrer tudo é barato. O
 *   SVG é markup de propósito: ali quem confere é o sanitizador, que só deixa passar o desenho;</li>
 * <li>a imagem inteira tem de decodificar (no GIF animado, o primeiro quadro) dentro de 400x400;</li>
 * <li>o que não faz parte da imagem NÃO vai ao storage: o bitmap com sobra é reescrito a partir do
 *   que foi decodificado (o GIF vai como veio — os quadros são o conteúdo).</li>
 * </ul>
 */
public final class FigurinhaArquivoValidado {

	private static final byte[] PNG_ASSINATURA = { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A };
	private static final byte[] JPEG_ASSINATURA = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF };
	/** O GIF traz a versão no cabeçalho: 87a ou 89a (a animação chegou no 89a). */
	private static final byte[][] GIF_CABECALHOS = { { 'G', 'I', 'F', '8', '7', 'a' },
			{ 'G', 'I', 'F', '8', '9', 'a' } };

	/** Fim próprio de imagem: bytes depois dele são sobra. */
	private static final byte[] PNG_FIM = { 0, 0, 0, 0, 'I', 'E', 'N', 'D', (byte) 0xAE, 0x42, 0x60, (byte) 0x82 };

	/** Recortes de identificação de formatos que NUNCA podem passar por figurinha. */
	private static final byte[][] ASSINATURAS_PROIBIDAS = {
		"%PDF".getBytes(StandardCharsets.US_ASCII),
		{ 'P', 'K', 3, 4 },
		{ 0x7F, 'E', 'L', 'F' },
		"<html".getBytes(StandardCharsets.US_ASCII),
		"<script".getBytes(StandardCharsets.US_ASCII),
		"RIFF".getBytes(StandardCharsets.US_ASCII),
	};

	/** O começo de todo SVG é marcação. */
	private static final byte[] SVG_MARCACAO = "<".getBytes(StandardCharsets.US_ASCII);

	/** {@code width="40"} ou {@code width='40px'}. */
	private static final Pattern SVG_LARGURA = Pattern.compile("\\swidth\\s*=\\s*[\"']\\s*([0-9.]+)",
		Pattern.CASE_INSENSITIVE);
	private static final Pattern SVG_ALTURA = Pattern.compile("\\sheight\\s*=\\s*[\"']\\s*([0-9.]+)",
		Pattern.CASE_INSENSITIVE);
	/** {@code viewBox="0 0 40 40"}: a medida quando não há width/height. */
	private static final Pattern SVG_VIEWBOX = Pattern.compile(
		"\\sviewBox\\s*=\\s*[\"']\\s*[-0-9.]+[\\s,]+[-0-9.]+[\\s,]+([0-9.]+)[\\s,]+([0-9.]+)",
		Pattern.CASE_INSENSITIVE);

	private FigurinhaArquivoValidado() {
	}

	/** Resultado da conferência: o tipo a gravar, as dimensões reais e o arquivo limpo. */
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
		if (FigurinhaImagem.SVG.equals(mime)) {
			return conferirSvg(bytes);
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
		else if (FigurinhaImagem.GIF.equals(mime) && comecaComAlgum(bytes, GIF_CABECALHOS)) {
			real = FigurinhaImagem.GIF;
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
	 * SVG: o vetor é desenho, não bytes de imagem — as medidas vêm de width/height ou do viewBox, e
	 * o sanitizador da rede tira script, evento, {@code foreignObject} e referência externa. O que
	 * fica gravado é o vetor limpo, servido como {@code image/svg+xml} (o pacote oficial de
	 * figurinhas já é SVG).
	 */
	private static Validado conferirSvg(byte[] bytes) {
		String original = new String(bytes, StandardCharsets.UTF_8);
		String inicio = original.stripLeading().toLowerCase(Locale.ROOT);
		if (!comecaCom(bytes, SVG_MARCACAO) || (!inicio.startsWith("<svg") && !inicio.startsWith("<?xml"))) {
			throw invalida();
		}
		// As medidas saem do documento ORIGINAL: o sanitizador reserializa o vetor, e um grupo ou uma
		// animação com width/height não muda o tamanho da figurinha na tela.
		ImageDecoding.Dimensions dimensoes = dimensoesDoSvg(original);
		if (dimensoes == null) {
			throw invalida();
		}
		FigurinhaImagem.exigirDimensoes(dimensoes.width(), dimensoes.height());
		byte[] limpo;
		try {
			limpo = SafeHtml.sanitizeSvg(bytes);
		}
		catch (IllegalArgumentException recusa) {
			// Sem elemento svg válido depois da limpeza: não é um vetor utilizável.
			throw invalida();
		}
		if (limpo.length > FigurinhaImagem.MAX_BYTES) {
			throw FigurinhaImagem.grande();
		}
		return new Validado(FigurinhaImagem.SVG, dimensoes.width(), dimensoes.height(), limpo);
	}

	/** width × height; na falta deles, o viewBox. Sem nenhuma das duas medidas, null. */
	private static ImageDecoding.Dimensions dimensoesDoSvg(String svg) {
		Matcher largura = SVG_LARGURA.matcher(svg);
		Matcher altura = SVG_ALTURA.matcher(svg);
		if (largura.find() && altura.find()) {
			return new ImageDecoding.Dimensions(arredondar(largura.group(1)), arredondar(altura.group(1)));
		}
		Matcher viewBox = SVG_VIEWBOX.matcher(svg);
		if (viewBox.find()) {
			return new ImageDecoding.Dimensions(arredondar(viewBox.group(1)), arredondar(viewBox.group(2)));
		}
		return null;
	}

	private static int arredondar(String valor) {
		try {
			return Math.round(Float.parseFloat(valor));
		}
		catch (NumberFormatException fora) {
			return 0;
		}
	}

	/**
	 * Reescreve o bitmap com sobra a partir do que foi decodificado: o desenho é o mesmo, e o
	 * arquivo gravado é um PNG/JPG novo, sem o resto. O GIF não é reescrito — os quadros são o
	 * conteúdo, e um PNG guardaria só o primeiro.
	 */
	private static byte[] reescrever(byte[] bytes, String mime, ImageDecoding.Dimensions dimensoes) {
		if (FigurinhaImagem.GIF.equals(mime) || !imagemTemSobra(bytes, mime)) {
			return bytes;
		}
		try {
			BufferedImage imagem = ImageDecoding.read(new ByteArrayInputStream(bytes));
			if (imagem == null) {
				throw invalida();
			}
			boolean png = FigurinhaImagem.PNG.equals(mime);
			int tipo = png && imagem.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB
					: BufferedImage.TYPE_INT_RGB;
			BufferedImage saida = new BufferedImage(dimensoes.width(), dimensoes.height(), tipo);
			Graphics2D pincel = saida.createGraphics();
			try {
				pincel.drawImage(imagem, 0, 0, null);
			}
			finally {
				pincel.dispose();
			}
			ByteArrayOutputStream arquivo = new ByteArrayOutputStream();
			if (!ImageIO.write(saida, png ? "png" : "jpg", arquivo)) {
				throw invalida();
			}
			return arquivo.toByteArray();
		}
		catch (IOException falha) {
			throw invalida();
		}
	}

	/**
	 * PNG: o fim é o chunk IEND, que é o próprio arquivo terminar. JPEG: os leitores aceitam mais de
	 * um marcador de fim, então quem garante a imagem inteira aqui é a decodificação.
	 */
	private static boolean imagemTemSobra(byte[] bytes, String mime) {
		return FigurinhaImagem.PNG.equals(mime) && !terminaCom(bytes, PNG_FIM);
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

	private static boolean comecaComAlgum(byte[] bytes, byte[][] prefixos) {
		for (byte[] prefixo : prefixos) {
			if (comecaCom(bytes, prefixo)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Lê a imagem inteira (não só o cabeçalho): arquivo truncado ou imagem que o leitor não completa
	 * devolve null. O teto de pixels do decodificador vale aqui também.
	 */
	private static ImageDecoding.Dimensions medir(byte[] bytes) {
		try {
			BufferedImage imagem = ImageDecoding.read(new ByteArrayInputStream(bytes));
			return imagem == null ? null : new ImageDecoding.Dimensions(imagem.getWidth(), imagem.getHeight());
		}
		catch (IOException | RuntimeException falha) {
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
			"O arquivo não é uma imagem PNG, JPG, GIF ou SVG válida.");
	}
}
