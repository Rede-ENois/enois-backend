package com.socialcoisa.rede;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Prova de boot do incidente de 2026-11-26 em producao (imagem main-7d63d80).
 *
 * <p>Com os repositorios em `deferred`, o Tomcat abria a porta ANTES de eles serem criados. O health
 * check que segue o deploy batia na porta nesse intervalo e a inicializacao do DispatcherServlet
 * ficava presa no lock do `main` para sempre: `Started RedeApplication` nunca era impresso e o
 * processo servia nada. Todo restart caindo no mesmo ponto.
 *
 * <p>Aqui o boot e o de producao (Tomcat em porta fixa livre) e a sonda e de fora, em thread
 * propria: enquanto o Tomcat sobe ela tenta conectar na porta e GET /health, exatamente como o
 * workflow de deploy. A assercao e de ordem, nao de tempo: o evento `ApplicationStarted` (o mesmo
 * que imprime "Started RedeApplication") tem que ter acontecido antes de a sonda receber resposta.
 * Se o boot travasse, o `run()` estouraria o tempo do teste em vez de passar.
 */
@Tag("critico")
class HealthDuringBootTests {

	@Test
	void sondaNaPortaDuranteOBootNaoTravaOSubirEDispatcher() throws Exception {
		int porta = portaLivre();
		CountDownLatch started = new CountDownLatch(1);
		Sonda sonda = Sonda.em(porta, started);
		Thread thread = Thread.ofPlatform().name("sonda-health").daemon(true).start(sonda);
		try (ConfigurableApplicationContext contexto = new SpringApplicationBuilder(RedeApplication.class)
				.listeners((org.springframework.context.ApplicationListener<org.springframework.context.ApplicationEvent>) evento -> {
					if (evento instanceof ApplicationStartedEvent) {
						started.countDown();
					}
				})
				.run("--server.port=" + porta, "--spring.profiles.active=dev",
						"--spring.task.scheduling.enabled=false", "--rede.emotes.refresh-enabled=false",
						"--rede.kof-agent.enabled=false")) {

			assertThat(started.await(1, TimeUnit.MINUTES)).as("ApplicationStarted (Started RedeApplication)").isTrue();
			assertThat(sonda.resposta.get(30, TimeUnit.SECONDS)).as("/health respondeu depois do boot").isNotNull();
			// Ordem: a primeira resposta so pode existir depois do Started. Hoje isso e garantido
			// porque os repositorios nascem antes do Tomcat subir; com `deferred` a sonda era atendida
			// no meio do refresh e derrubava o boot.
			assertThat(sonda.primeiraRespostaAntesDoStarted).isFalse();
			assertThat(contexto.isActive()).isTrue();
		}
		finally {
			sonda.parar = true;
			thread.interrupt();
		}
	}

	private static int portaLivre() throws IOException {
		try (ServerSocket socket = new ServerSocket(0)) {
			socket.setReuseAddress(true);
			return socket.getLocalPort();
		}
	}

	/** Bate na porta ate o app responder; registra se a resposta chegou antes do `Started`. */
	private static final class Sonda implements Runnable {

		private final int porta;
		private final CountDownLatch started;
		private final java.util.concurrent.CompletableFuture<String> resposta = new java.util.concurrent.CompletableFuture<>();
		private volatile boolean primeiraRespostaAntesDoStarted;
		private volatile boolean parar;

		private Sonda(int porta, CountDownLatch started) {
			this.porta = porta;
			this.started = started;
		}

		static Sonda em(int porta, CountDownLatch started) {
			return new Sonda(porta, started);
		}

		@Override
		public void run() {
			HttpClient cliente = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(300)).build();
			HttpRequest pedido = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + porta + "/health"))
					.timeout(Duration.ofSeconds(2))
					.GET()
					.build();
			IOException ultima = null;
			while (!parar) {
				try (Socket conexao = new Socket()) {
					// Conexao crua primeiro: e o que o health check faz. Se a porta aceita, o Tomcat
					// ja esta no ar e a requisicao HTTP segue.
					conexao.connect(new InetSocketAddress("127.0.0.1", porta), 300);
					HttpResponse<String> r = cliente.send(pedido, HttpResponse.BodyHandlers.ofString());
					primeiraRespostaAntesDoStarted = started.getCount() > 0;
					resposta.complete(r.body());
					return;
				}
				catch (IOException falha) {
					ultima = falha;
				}
				catch (InterruptedException interrompida) {
					Thread.currentThread().interrupt();
					return;
				}
				try {
					Thread.sleep(50);
				}
				catch (InterruptedException interrompida) {
					Thread.currentThread().interrupt();
					return;
				}
			}
			resposta.completeExceptionally(ultima);
		}
	}
}
