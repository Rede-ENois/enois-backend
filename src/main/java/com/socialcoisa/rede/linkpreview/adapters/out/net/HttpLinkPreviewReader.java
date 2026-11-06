package com.socialcoisa.rede.linkpreview.adapters.out.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.socialcoisa.rede.linkpreview.application.port.out.LinkPreviewReader;
import com.socialcoisa.rede.linkpreview.domain.ExternalLinkPreview;
import com.socialcoisa.rede.shared.security.OutboundUrlGuard;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;

/**
 * Le a miniatura de um site de fora a partir do que ele publica para compartilhamento.
 *
 * <p>O endereco vem de quem escreveu o post, entao e dado NAO CONFIAVEL. Antes de cada salto (o
 * primeiro e cada redirecionamento) o destino passa pelo {@link OutboundUrlGuard}: rede interna,
 * loopback, link-local, faixa de container e o endereco de metadados da nuvem ficam de fora, e
 * porta fora de 80/443 tambem. Redirecionamento e seguido na mao, com a mesma checagem em cada
 * salto, e o teto e curto.</p>
 *
 * <p>A conexao vai para o IP que a guarda aprovou, nunca para o nome: um cliente que resolve o
 * nome de novo deixa a janela para o registro do DNS mudar entre a checagem e o connect. Em
 * https, o certificado e conferido contra o NOME (SNI e verificacao de hostname), entao conectar
 * pelo IP nao afrouxa o TLS.</p>
 *
 * <p>A leitura para no primeiro trecho da pagina e no teto de bytes: interessa o cabecalho, nao o
 * site inteiro. Nada do corpo e guardado — so os campos que o site declara sobre si.</p>
 */
@Component
public class HttpLinkPreviewReader implements LinkPreviewReader {

	private static final Logger LOG = LoggerFactory.getLogger(HttpLinkPreviewReader.class);

	private static final int MAX_REDIRECTS = 3;

	private static final int MAX_BYTES = 96 * 1024;

	private static final Duration TIMEOUT = Duration.ofSeconds(6);

	private static final int MAX_HEADER_BYTES = 32 * 1024;

	private static final Pattern OG_TITLE = metaContent("og:title");

	private static final Pattern OG_DESCRIPTION = metaContent("og:description");

	private static final Pattern OG_IMAGE = metaContent("og:image");

	private static final Pattern OG_IMAGE_SECURE = metaContent("og:image:secure_url");

	private static final Pattern OG_SITE = metaContent("og:site_name");

	private static final Pattern TWITTER_IMAGE = metaContent("twitter:image");

	private static final Pattern DESCRIPTION = metaContent("description");

	// Quantificadores com teto: `[^>]*` e `.*?` sem limite voltam atras em pagina cheia de
	// `<title` sem fechar, e o texto vem de fora. O teto e maior que qualquer titulo real.
	private static final Pattern TITLE = Pattern.compile("<title[^>]{0,200}>(.{0,2000}?)</title>",
			Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

	private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x?)([0-9a-fA-F]{1,8});?");

	private final OutboundUrlGuard guard;

	public HttpLinkPreviewReader(OutboundUrlGuard guard) {
		this.guard = guard;
	}

	/**
	 * Uma leitura e UMA conexao de saida. O bulkhead segura a rajada de enderecos nunca vistos: sem
	 * ele, quem fura o cache escolhe quantas conexoes o servidor abre ao mesmo tempo.
	 */
	@Override
	@Bulkhead(name = "linkPreviewOutbound")
	public Optional<ExternalLinkPreview> read(String url) {
		if (url == null || url.isBlank()) {
			return Optional.empty();
		}
		String atual = url.trim();
		try {
			URI.create(atual);
		}
		catch (RuntimeException invalido) {
			return Optional.empty();
		}
		for (int salto = 0; salto <= MAX_REDIRECTS && atual != null; salto++) {
			Optional<OutboundUrlGuard.CheckedUrl> checked = checked(atual);
			if (checked.isEmpty()) {
				LOG.debug("preview_destino_recusado");
				return Optional.empty();
			}
			OutboundUrlGuard.CheckedUrl destino = checked.get();
			HttpAnswer resposta;
			try {
				resposta = fetch(destino);
			}
			catch (IOException falha) {
				LOG.debug("preview_leitura_falhou motivo={}", falha.getClass().getSimpleName());
				return Optional.empty();
			}
			if (resposta.status() / 100 == 3) {
				atual = redirectTarget(destino.uri(), resposta.header("location"));
				continue;
			}
			if (resposta.status() != 200) {
				return Optional.empty();
			}
			if (!resposta.header("content-type").toLowerCase(Locale.ROOT).startsWith("text/html")) {
				return Optional.empty();
			}
			ExternalLinkPreview preview = parse(destino.uri(), resposta.html(charsetOf(resposta.header("content-type"))));
			return preview.hasAnything() ? Optional.of(preview) : Optional.empty();
		}
		return Optional.empty();
	}

	/**
	 * O destino aprovado. Um ponto so para trocar a politica (o teste de rede aprova o loopback
	 * para exercitar o resto do caminho).
	 */
	Optional<OutboundUrlGuard.CheckedUrl> checked(String url) {
		return guard.checked(url);
	}

	/**
	 * Conecta em cada IP aprovado, na ordem do DNS, ate um responder. O nome nunca e resolvido
	 * aqui: o socket abre direto no endereco que a guarda viu.
	 */
	HttpAnswer fetch(OutboundUrlGuard.CheckedUrl destino) throws IOException {
		IOException ultima = null;
		for (InetAddress address : destino.addresses()) {
			try (Socket socket = connect(address, destino.port())) {
				Socket canal = destino.secure() ? tls(socket, destino.host()) : socket;
				try (OutputStream out = canal.getOutputStream(); InputStream in = canal.getInputStream()) {
					out.write(request(destino));
					out.flush();
					return HttpAnswer.read(in);
				}
			}
			catch (IOException falha) {
				ultima = falha;
			}
		}
		throw ultima == null ? new IOException("sem endereco para conectar") : ultima;
	}

	private Socket connect(InetAddress address, int porta) throws IOException {
		Socket socket = new Socket();
		try {
			socket.connect(new InetSocketAddress(address, porta), (int) TIMEOUT.toMillis());
			socket.setSoTimeout((int) TIMEOUT.toMillis());
			return socket;
		}
		catch (IOException falha) {
			socket.close();
			throw falha;
		}
	}

	/**
	 * TLS sobre o socket ja aberto, conferindo o certificado contra o NOME do destino — o mesmo
	 * que o navegador faria. O SNI tambem vai com o nome, senao o site responde o certificado errado.
	 */
	private static Socket tls(Socket socket, String host) throws IOException {
		SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
		SSLSocket tls = (SSLSocket) factory.createSocket(socket, host, socket.getPort(), true);
		SSLParameters parameters = tls.getSSLParameters();
		parameters.setEndpointIdentificationAlgorithm("HTTPS");
		parameters.setServerNames(List.of(new SNIHostName(host)));
		tls.setSSLParameters(parameters);
		tls.startHandshake();
		return tls;
	}

	private static byte[] request(OutboundUrlGuard.CheckedUrl destino) {
		String head = "GET " + destino.requestTarget() + " HTTP/1.1\r\n"
			+ "Host: " + destino.hostHeader() + "\r\n"
			+ "User-Agent: enois-preview/1.0 (+miniatura de link)\r\n"
			+ "Accept: text/html,application/xhtml+xml\r\n"
			+ "Accept-Language: pt-BR,pt;q=0.9\r\n"
			+ "Accept-Encoding: identity\r\n"
			+ "Connection: close\r\n"
			+ "\r\n";
		return head.getBytes(StandardCharsets.US_ASCII);
	}

	/** O destino do 302/301, resolvido contra o endereco que respondeu; nulo sem `Location`. */
	static String redirectTarget(URI base, String location) {
		if (location == null || location.isBlank()) {
			return null;
		}
		try {
			return base.resolve(location.trim()).toString();
		}
		catch (RuntimeException invalido) {
			return null;
		}
	}

	/**
	 * A resposta HTTP/1.1, lida ate o teto.
	 *
	 * <p>O corpo para no teto mesmo quando o servidor declara um tamanho maior: a leitura nunca
	 * espera o fim de um corpo grande. `Transfer-Encoding: chunked` e desmontado; tamanho declarado
	 * so limita a leitura.</p>
	 */
	record HttpAnswer(int status, Map<String, String> headers, byte[] head, byte[] body) {

		static HttpAnswer read(InputStream in) throws IOException {
			byte[] head = readHead(in);
			if (head == null) {
				throw new IOException("resposta sem cabecalho");
			}
			String text = new String(head, StandardCharsets.ISO_8859_1);
			String[] lines = text.split("\r\n");
			String[] first = lines.length == 0 ? new String[0] : lines[0].split(" ", 3);
			if (first.length < 2) {
				throw new IOException("resposta sem status");
			}
			int status;
			try {
				status = Integer.parseInt(first[1]);
			}
			catch (NumberFormatException invalido) {
				throw new IOException("status ilegivel");
			}
			Map<String, String> headers = new HashMap<>();
			for (int i = 1; i < lines.length; i++) {
				int colon = lines[i].indexOf(':');
				if (colon > 0) {
					headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT),
						lines[i].substring(colon + 1).trim());
				}
			}
			// 204 e 304 nunca tem corpo, e o resto dos 3xx so interessa pelo Location.
			boolean bodyExpected = status != 204 && status != 304 && status / 100 != 3;
			byte[] body = new byte[0];
			if (bodyExpected) {
				if (headers.getOrDefault("transfer-encoding", "").toLowerCase(Locale.ROOT).contains("chunked")) {
					body = readChunked(in);
				}
				else {
					body = readAtMost(in, MAX_BYTES);
				}
			}
			return new HttpAnswer(status, headers, head, body);
		}

		String header(String name) {
			return headers.getOrDefault(name.toLowerCase(Locale.ROOT), "");
		}

		String html(Charset charset) {
			return new String(body, charset);
		}

		private static byte[] readHead(InputStream in) throws IOException {
			ByteArrayOutputStream lido = new ByteArrayOutputStream();
			// O fim do cabecalho e a linha vazia: CRLF CRLF. Contar so `\n\n` nunca casa, porque
			// entre os dois ha o `\r`. Estados: 0 nada, 1 `\r`, 2 `\r\n`, 3 `\r\n\r`, 4 fim.
			int estado = 0;
			int atual;
			while ((atual = in.read()) != -1) {
				lido.write(atual);
				if (lido.size() > MAX_HEADER_BYTES) {
					throw new IOException("cabecalho grande demais");
				}
				estado = switch (atual) {
					case '\r' -> estado == 2 ? 3 : 1;
					case '\n' -> estado == 1 ? 2 : (estado == 3 ? 4 : 0);
					default -> 0;
				};
				if (estado == 4) {
					return lido.toByteArray();
				}
			}
			return lido.size() == 0 ? null : lido.toByteArray();
		}

		private static byte[] readChunked(InputStream in) throws IOException {
			ByteArrayOutputStream lido = new ByteArrayOutputStream();
			while (lido.size() < MAX_BYTES) {
				String linha = readLine(in);
				if (linha == null) {
					break;
				}
				int ponto = linha.indexOf(';');
				String tamanho = (ponto >= 0 ? linha.substring(0, ponto) : linha).trim();
				int bytes;
				try {
					bytes = Integer.parseInt(tamanho, 16);
				}
				catch (NumberFormatException invalido) {
					throw new IOException("pedaco ilegivel");
				}
				if (bytes <= 0) {
					break;
				}
				int restante = Math.min(bytes, MAX_BYTES - lido.size());
				byte[] pedaco = in.readNBytes(restante);
				lido.write(pedaco, 0, pedaco.length);
				// Consome o resto do pedaco e o CRLF, mesmo quando ja parou no teto: e so o fim
				// do chunk que o servidor mandou, nao o corpo inteiro.
				if (pedaco.length < bytes) {
					break;
				}
				readLine(in);
				if (restante < bytes) {
					break;
				}
			}
			return lido.toByteArray();
		}

		private static String readLine(InputStream in) throws IOException {
			StringBuilder linha = new StringBuilder();
			int atual;
			while ((atual = in.read()) != -1) {
				if (atual == '\n') {
					int fim = linha.length() - 1;
					if (fim >= 0 && linha.charAt(fim) == '\r') {
						linha.setLength(fim);
					}
					return linha.toString();
				}
				linha.append((char) atual);
				if (linha.length() > 1024) {
					throw new IOException("linha grande demais");
				}
			}
			return linha.length() == 0 ? null : linha.toString();
		}
	}

	/**
	 * Extrai os campos do cabecalho. A imagem e resolvida contra o endereco final (o site pode
	 * publicar caminho relativo) e so sobrevive se for http(s) — o navegador de quem le e que a
	 * busca, entao um `javascript:` ou `data:` aqui viraria execucao na pagina.
	 */
	static ExternalLinkPreview parse(URI page, String html) {
		String title = first(OG_TITLE, html);
		if (title == null) {
			title = first(TITLE, html);
		}
		String description = first(OG_DESCRIPTION, html);
		if (description == null) {
			description = first(DESCRIPTION, html);
		}
		String image = first(OG_IMAGE_SECURE, html);
		if (image == null) {
			image = first(OG_IMAGE, html);
		}
		if (image == null) {
			image = first(TWITTER_IMAGE, html);
		}
		return new ExternalLinkPreview(page.toString(), decode(title), decode(description),
			absoluteImage(page, decode(image)), decode(first(OG_SITE, html)));
	}

	private static String absoluteImage(URI page, String image) {
		if (image == null || image.isBlank()) {
			return null;
		}
		try {
			URI resolved = page.resolve(image.trim());
			String scheme = resolved.getScheme() == null ? "" : resolved.getScheme().toLowerCase(Locale.ROOT);
			if (!scheme.equals("http") && !scheme.equals("https")) {
				return null;
			}
			return resolved.toString();
		}
		catch (RuntimeException invalido) {
			return null;
		}
	}

	private static Pattern metaContent(String chave) {
		return Pattern.compile("<meta[^>]{0,500}(?:property|name)\\s{0,10}=\\s{0,10}[\"']"
				+ Pattern.quote(chave) + "[\"'][^>]{0,500}content\\s{0,10}=\\s{0,10}[\"']([^\"']{0,2000})[\"']",
				Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
	}

	private static String first(Pattern pattern, String html) {
		Matcher matcher = pattern.matcher(html);
		return matcher.find() ? matcher.group(1) : null;
	}

	/**
	 * Entidades comuns e as numericas (`&#39;`, `&#x27;`, `&#8217;`). Titulo de artigo com
	 * apostrofo ou aspas curvas chega assim no cabecalho; mostrar o codigo literal e feio.
	 */
	static String decode(String value) {
		if (value == null) {
			return null;
		}
		String texto = value.replace("&amp;", "&")
			.replace("&lt;", "<")
			.replace("&gt;", ">")
			.replace("&quot;", "\"")
			.replace("&#39;", "'")
			.replace("&apos;", "'")
			.replace("&nbsp;", " ");
		Matcher matcher = NUMERIC_ENTITY.matcher(texto);
		StringBuilder saida = new StringBuilder();
		while (matcher.find()) {
			String replacement;
			try {
				int codigo = Integer.parseInt(matcher.group(2), matcher.group(1).isEmpty() ? 10 : 16);
				replacement = Character.isValidCodePoint(codigo) ? new String(Character.toChars(codigo))
					: matcher.group();
			}
			catch (NumberFormatException invalido) {
				replacement = matcher.group();
			}
			matcher.appendReplacement(saida, Matcher.quoteReplacement(replacement));
		}
		matcher.appendTail(saida);
		return saida.toString();
	}

	static byte[] readAtMost(InputStream body, int max) throws IOException {
		byte[] buffer = new byte[8192];
		ByteArrayOutputStream lido = new ByteArrayOutputStream();
		int total = 0;
		int n;
		while (total < max && (n = body.read(buffer)) > 0) {
			int escrever = Math.min(n, max - total);
			lido.write(buffer, 0, escrever);
			total += escrever;
		}
		return lido.toByteArray();
	}

	private static Charset charsetOf(String contentType) {
		for (String parte : contentType.split(";")) {
			String limpo = parte.trim().toLowerCase(Locale.ROOT);
			if (limpo.startsWith("charset=")) {
				try {
					return Charset.forName(limpo.substring("charset=".length()).replace("\"", "").trim());
				}
				catch (Exception desconhecido) {
					return StandardCharsets.UTF_8;
				}
			}
		}
		return StandardCharsets.UTF_8;
	}
}
