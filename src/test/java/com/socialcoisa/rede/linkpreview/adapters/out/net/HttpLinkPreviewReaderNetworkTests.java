package com.socialcoisa.rede.linkpreview.adapters.out.net;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.linkpreview.domain.ExternalLinkPreview;
import com.socialcoisa.rede.shared.security.OutboundUrlGuard;

/**
 * O caminho de rede do leitor, com um servidor HTTP de verdade no loopback.
 *
 * <p>O guarda é dublado para aprovar o destino local (o guarda real recusa loopback, e é isso que
 * ele deve fazer); o que este teste exercita é o que vem DEPOIS da aprovação: a conexão vai para o
 * IP aprovado, o redirecionamento é seguido à mão, o corpo para no teto e o `chunked` é desmontado.
 * É o trecho onde os bugs moram e que os testes de `parse` não tocam.</p>
 */
class HttpLinkPreviewReaderNetworkTests {

	private ServerSocket server;

	private Thread acceptor;

	private final AtomicReference<String> lastRequest = new AtomicReference<>();

	private final AtomicInteger connections = new AtomicInteger();

	private volatile Responder responder;

	/** O que o servidor responde a cada conexão. */
	interface Responder {

		void respond(OutputStream out) throws IOException;
	}

	@BeforeEach
	void setUp() throws IOException {
		server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
		responder = out -> write(out, "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: 0\r\n\r\n");
		acceptor = new Thread(() -> {
			while (!server.isClosed()) {
				try (Socket socket = server.accept()) {
					connections.incrementAndGet();
					lastRequest.set(readRequest(socket.getInputStream()));
					responder.respond(socket.getOutputStream());
				}
				catch (IOException encerrado) {
					return;
				}
			}
		}, "preview-test-server");
		acceptor.setDaemon(true);
		acceptor.start();
	}

	@AfterEach
	void tearDown() throws IOException {
		server.close();
	}

	@Test
	void leAPaginaDoServidorEConectaNoIpAprovado() {
		responder = out -> write(out, "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n"
			+ "Content-Length: 74\r\n\r\n"
			+ "<html><head><meta property=\"og:title\" content=\"Do servidor\"></head></html>");

		Optional<ExternalLinkPreview> preview = reader().read(url("/artigo"));

		assertThat(preview).isPresent();
		assertThat(preview.get().title()).isEqualTo("Do servidor");
		assertThat(lastRequest.get()).startsWith("GET /artigo HTTP/1.1");
		assertThat(lastRequest.get()).contains("Host: 127.0.0.1:" + server.getLocalPort());
	}

	@Test
	void segueORedirecionamentoNaMaoEValidaODestinoNovo() {
		responder = out -> {
			if (lastRequest.get() != null && lastRequest.get().startsWith("GET /destino")) {
				write(out, "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: 60\r\n\r\n"
					+ "<html><head><title>Depois do salto</title></head></html>");
				return;
			}
			write(out, "HTTP/1.1 302 Found\r\nLocation: /destino\r\nContent-Length: 0\r\n\r\n");
		};

		Optional<ExternalLinkPreview> preview = reader().read(url("/comeco"));

		assertThat(preview).isPresent();
		assertThat(preview.get().title()).isEqualTo("Depois do salto");
		assertThat(connections.get()).isEqualTo(2);
	}

	@Test
	void redirecionamentoParaDestinoRecusadoNaoEVisitado() {
		responder = out -> write(out, "HTTP/1.1 302 Found\r\nLocation: http://169.254.169.254/latest\r\n"
			+ "Content-Length: 0\r\n\r\n");

		// O primeiro destino e aprovado (o teste aprova o loopback); o segundo passa pelo guarda
		// de verdade, que recusa o endereco de metadados da nuvem. A segunda conexao nao acontece.
		HttpLinkPreviewReader leitor = new HttpLinkPreviewReader(new OutboundUrlGuard()) {
			@Override
			Optional<OutboundUrlGuard.CheckedUrl> checked(String url) {
				if (url.contains("169.254.169.254")) {
					return super.checked(url);
				}
				return Optional.of(new OutboundUrlGuard.CheckedUrl(URI.create(url), "127.0.0.1",
					new InetAddress[] { InetAddress.getLoopbackAddress() }));
			}
		};

		assertThat(leitor.read(url("/comeco"))).isEmpty();
		assertThat(connections.get()).isEqualTo(1);
	}

	@Test
	void corpoGrandeParaNoTetoENaoEsperaOFim() {
		byte[] grande = new byte[1024 * 1024];
		java.util.Arrays.fill(grande, (byte) 'a');
		responder = out -> {
			write(out, "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: " + grande.length + "\r\n\r\n");
			out.write(grande);
			out.flush();
		};

		Optional<ExternalLinkPreview> preview = reader().read(url("/grande"));

		// Sem cabecalho de compartilhamento o cartao e vazio, mas a leitura terminou (nao travou).
		assertThat(preview).isEmpty();
	}

	@Test
	void desmontaCorpoChunked() {
		responder = out -> write(out, "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nTransfer-Encoding: chunked\r\n\r\n"
			+ "2f\r\n<meta property=\"og:title\" content=\"Em pedacos\">\r\n0\r\n\r\n");

		Optional<ExternalLinkPreview> preview = reader().read(url("/pedacos"));

		assertThat(preview).isPresent();
		assertThat(preview.get().title()).isEqualTo("Em pedacos");
	}

	@Test
	void tipoDeConteudoQueNaoEHtmlNaoViraCartao() {
		responder = out -> write(out, "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\n{}");

		assertThat(reader().read(url("/dados"))).isEmpty();
	}

	@Test
	void statusDeErroNaoViraCartao() {
		responder = out -> write(out, "HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\n\r\n");

		assertThat(reader().read(url("/quebrado"))).isEmpty();
	}

	@Test
	void entidadesNumericasSaoDecodificadas() {
		responder = out -> write(out, "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: 78\r\n\r\n"
			+ "<html><head><meta property=\"og:title\" content=\"It&#8217;s &#x27;ok&#x27;\"></head></html>");

		Optional<ExternalLinkPreview> preview = reader().read(url("/entidades"));

		assertThat(preview).isPresent();
		assertThat(preview.get().title()).isEqualTo("It’s 'ok'");
	}

	/** O leitor com o guarda dublado: aprova o loopback e devolve o IP local como destino. */
	private HttpLinkPreviewReader reader() {
		return new HttpLinkPreviewReader(new OutboundUrlGuard()) {
			@Override
			Optional<OutboundUrlGuard.CheckedUrl> checked(String url) {
				return Optional.of(new OutboundUrlGuard.CheckedUrl(URI.create(url), "127.0.0.1",
					new InetAddress[] { InetAddress.getLoopbackAddress() }));
			}
		};
	}

	private String url(String path) {
		return "http://127.0.0.1:" + server.getLocalPort() + path;
	}

	private static void write(OutputStream out, String text) throws IOException {
		out.write(text.getBytes(StandardCharsets.UTF_8));
		out.flush();
	}

	private static String readRequest(InputStream in) throws IOException {
		StringBuilder texto = new StringBuilder();
		int atual;
		while ((atual = in.read()) != -1) {
			texto.append((char) atual);
			if (texto.length() >= 4 && texto.substring(texto.length() - 4).equals("\r\n\r\n")) {
				break;
			}
		}
		return texto.toString();
	}
}
