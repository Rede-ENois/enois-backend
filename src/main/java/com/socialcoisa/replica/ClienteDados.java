package com.socialcoisa.replica;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.beans.factory.annotation.Value;

final class ClienteDados implements AutoCloseable {

	private static final int MAX_CORPO = 1024 * 1024;
	private final ContextoWorkload contexto;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build();

	ClienteDados(@Value("${rede.replica.context-socket:}") String socket) {
		contexto = new ContextoWorkload(socket);
	}

	String versaoPronta() throws IOException, InterruptedException {
		var atribuicao = contexto.atual();
		var retorno = consultar(atribuicao, atribuicao.dados().resolve("/internal/data/v1/ready"), null, 650);
		if (retorno.statusCode() != 204 ||
				retorno.headers().firstValue("X-Enois-Data-Protocol").filter("1"::equals).isEmpty()) {
			throw new IOException("Serviço de dados indisponível");
		}
		return atribuicao.versao();
	}

	HttpResponse<byte[]> post(String id, String sessao) throws IOException, InterruptedException {
		var atribuicao = contexto.atual();
		URI rota = org.springframework.web.util.UriComponentsBuilder.fromUri(atribuicao.dados())
			.pathSegment("internal", "data", "v1", "posts", "{id}").encode().buildAndExpand(id).toUri();
		return consultar(atribuicao, rota, sessao, 2500);
	}

	HttpResponse<byte[]> sessao(String sessao) throws IOException, InterruptedException {
		var atribuicao = contexto.atual();
		return consultar(atribuicao, atribuicao.dados().resolve("/internal/data/v1/session"), sessao, 2500);
	}

	HttpResponse<byte[]> publicar(byte[] corpo, String sessao, String chave) throws IOException, InterruptedException {
		var atribuicao = contexto.atual();
		var pedido = HttpRequest.newBuilder(atribuicao.dados().resolve("/internal/data/v1/posts"))
			.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(corpo));
		if (chave != null && !chave.isBlank()) {
			pedido.header("Idempotency-Key", chave);
		}
		return consultar(atribuicao, pedido, sessao, 3500);
	}

	private HttpResponse<byte[]> consultar(ContextoWorkload.Atribuicao atribuicao, URI rota, String sessao, long limiteMs)
			throws IOException, InterruptedException {
		return consultar(atribuicao, HttpRequest.newBuilder(rota).GET(), sessao, limiteMs);
	}

	private HttpResponse<byte[]> consultar(ContextoWorkload.Atribuicao atribuicao, HttpRequest.Builder request,
			String sessao, long limiteMs)
			throws IOException, InterruptedException {
		long prazo = Math.min(limiteMs, atribuicao.expiraEm() - System.currentTimeMillis());
		if (prazo <= 0) {
			throw new IOException("Atribuição de workload expirada");
		}
		request.timeout(Duration.ofMillis(prazo)).header("Authorization", "Bearer " + atribuicao.credencial());
		if (sessao != null && !sessao.isBlank()) {
			request.header("X-Session-Token", sessao);
		}
		var pendente = http.sendAsync(request.build(), info -> new CorpoLimitado());
		try {
			return pendente.get(prazo, TimeUnit.MILLISECONDS);
		} catch (ExecutionException | TimeoutException failure) {
			throw new IOException("Serviço de dados indisponível");
		} finally {
			if (!pendente.isDone()) {
				pendente.cancel(true);
			}
		}
	}

	@Override
	public void close() {
		http.close();
	}

	private static final class CorpoLimitado implements HttpResponse.BodySubscriber<byte[]> {
		private final HttpResponse.BodySubscriber<byte[]> corpo = HttpResponse.BodySubscribers.ofByteArray();
		private Flow.Subscription assinatura;
		private int tamanho;

		public CompletionStage<byte[]> getBody() {
			return corpo.getBody();
		}

		public void onSubscribe(Flow.Subscription subscription) {
			assinatura = subscription;
			corpo.onSubscribe(subscription);
		}

		public void onNext(List<ByteBuffer> lotes) {
			for (var lote : lotes) {
				if (lote.remaining() > MAX_CORPO - tamanho) {
					assinatura.cancel();
					corpo.onError(new IOException("Resposta de dados excede o limite"));
					return;
				}
				tamanho += lote.remaining();
			}
			corpo.onNext(lotes);
		}

		public void onError(Throwable falha) {
			corpo.onError(falha);
		}

		public void onComplete() {
			corpo.onComplete();
		}
	}
}
