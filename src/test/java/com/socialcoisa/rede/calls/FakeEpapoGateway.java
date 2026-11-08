package com.socialcoisa.rede.calls;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Epapo falso para a suíte: um gateway HTTP de verdade numa porta local, que registra o que
 * recebeu e responde o que o teste mandar.
 *
 * <p>A autorização de entrada devolve o mesmo formato do backend real ({@code callId, room, state,
 * displayName, moderator}) e o token é uma string opaca — quem confere a assinatura do JWT é a
 * sonda de ponta a ponta com o servidor de reunião de verdade, não a suíte. Aqui o que importa é o
 * contrato: laço completo (autoriza → pede token) e nenhum segredo viajando para o navegador.
 */
final class FakeEpapoGateway {

	record Received(String path, Map<String, String> headers, String body) {
		String header(String name) {
			return headers.entrySet().stream()
				.filter(entry -> entry.getKey().equalsIgnoreCase(name))
				.map(Map.Entry::getValue)
				.findFirst()
				.orElse(null);
		}
	}

	private final HttpServer server;
	private final Map<String, Received> received = new ConcurrentHashMap<>();
	private final Map<String, String> responses = new ConcurrentHashMap<>();
	private final AtomicInteger tokens = new AtomicInteger();
	private final AtomicReference<Runnable> tokenWork = new AtomicReference<>();

	FakeEpapoGateway(int port) throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
		server.createContext("/", this::handle);
		server.start();
	}

	void stop() {
		server.stop(0);
	}

	String url() {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	/** O que chegou na última requisição para este caminho, ou {@code null} se nada chegou. */
	Received last(String path) {
		return received.get(path);
	}

	/** Quantos tokens foram pedidos ({@code /auth/token}). */
	int tokenRequests() {
		return tokens.get();
	}

	/** Faz o gateway responder este caminho ao contrário do padrão. */
	void respond(String path, int status, String body) {
		responses.put(path, status + " " + body);
	}

	/** Volta ao comportamento padrão deste caminho. */
	void reset(String path) {
		responses.remove(path);
	}

	/**
	 * Guarda um trabalho para rodar quando um pedido de token chegar — e o pedido só é respondido
	 * depois que o trabalho termina. O gateway real pergunta ao backend se a pessoa pode entrar
	 * antes de assinar; os testes que recriam esse vaivém (a resposta do backend escreve no banco
	 * enquanto outra transação está aberta) usam isto para fazer o vaivém acontecer na hora certa.
	 */
	void onToken(Runnable work) {
		tokenWork.set(work);
	}

	void clearTokenWork() {
		tokenWork.set(null);
	}

	private void handle(HttpExchange exchange) throws IOException {
		String path = exchange.getRequestURI().getPath();
		String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		Map<String, String> headers = new ConcurrentHashMap<>();
		exchange.getRequestHeaders().forEach((name, values) -> headers.put(name, values.get(0)));
		received.put(path, new Received(path, headers, body));

		if (path.equals("/auth/token")) {
			tokens.incrementAndGet();
			Runnable work = tokenWork.getAndSet(null);
			if (work != null) {
				work.run();
			}
		}

		String forced = responses.get(path);
		if (forced != null) {
			reply(exchange, Integer.parseInt(forced.substring(0, 3)), forced.substring(4));
			return;
		}
		if (path.startsWith("/calls/") && path.contains("/gateway/entry/")) {
			// Sala derivada do id da chamada: é o que o backend real devolveria para uma chamada
			// autorizada deste teste.
			String callId = path.substring("/calls/".length(), path.indexOf("/gateway/entry/"));
			reply(exchange, 200, "{\"callId\":\"" + callId + "\",\"room\":\""
				+ callId.replace("-", "").repeat(32).substring(0, 32)
				+ "\",\"state\":\"ACTIVE\",\"displayName\":\"Fulano\",\"moderator\":false}");
			return;
		}
		if (path.equals("/auth/token")) {
			reply(exchange, 200, "{\"jwt\":\"epapo-token-" + tokens.get() + "\"}");
			return;
		}
		reply(exchange, 404, "{\"error\":\"not_found\"}");
	}

	private static void reply(HttpExchange exchange, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}
}
