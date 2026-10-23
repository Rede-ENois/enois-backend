package com.socialcoisa.rede.shared.security;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * A resposta à varredura: quem erra demais em pouco tempo é barrado na hora.
 *
 * <p>O limitador global conta REQUISIÇÕES (300/min por IP) e por isso um scanner consegue
 * martelar dezenas de rotas antes de bater no teto — e, pior, todas as tentativas "erradas"
 * (404 de rota que não existe, 401/403 de tentativa de acesso, 400 de payload inválido, 405/415
 * de método e tipo errados) passavam sem custo nenhum. Uma pessoa que usa o app quase nunca
 * produz esse padrão; um scanner produz só isso.
 *
 * <p>Aqui a conta é por RESPOSTA DE ERRO: passou do teto numa janela curta, o IP entra numa
 * negação com prazo, e tudo o que vem dele responde 429 com <code>Retry-After</code> até o prazo
 * acabar. A negação escala: o primeiro episódio é curto, a reincidência é mais longa — o
 * scanner que insiste acaba fora, e quem legitima de novo tem o caminho de volta sozinho.
 *
 * <p><b>Cuidado com rede compartilhada:</b> o plano mestre manda não bloquear gente honesta por
 * usar VPN ou rede compartilhada. Por isso o teto é de ERROS (não de requisições), a janela é
 * curta e a negação é temporária: só quem está ativamente varrendo chega lá, e quem divide o
 * mesmo endereço volta sozinho quando o prazo acaba.
 */
@Component
@Order(0)
public class AbuseGuardFilter extends OncePerRequestFilter {

	private static final Logger LOG = LoggerFactory.getLogger(AbuseGuardFilter.class);

	private static final int MAX_KEYS = 100_000;

	private final Map<String, Deque<Long>> erros = new ConcurrentHashMap<>();
	private final Map<String, Block> bloqueios = new ConcurrentHashMap<>();
	private final java.time.Clock clock;
	private final ClientIpResolver ipResolver;

	private final int limiteDeErros;
	private final long janelaMs;
	private final long primeiroBloqueioMs;
	private final long bloqueioMaximoMs;

	public AbuseGuardFilter(java.time.Clock clock, ClientIpResolver ipResolver,
			@Value("${rede.security.abuse.max-errors:20}") int limiteDeErros,
			@Value("${rede.security.abuse.window-seconds:180}") long janelaSegundos,
			@Value("${rede.security.abuse.first-block-minutes:15}") long primeiroBloqueioMinutos,
			@Value("${rede.security.abuse.max-block-minutes:360}") long bloqueioMaximoMinutos) {
		this.clock = clock;
		this.ipResolver = ipResolver;
		this.limiteDeErros = Math.max(0, limiteDeErros);
		this.janelaMs = Math.max(1, janelaSegundos) * 1000L;
		this.primeiroBloqueioMs = Math.max(1, primeiroBloqueioMinutos) * 60_000L;
		this.bloqueioMaximoMs = Math.max(primeiroBloqueioMs, bloqueioMaximoMinutos * 60_000L);
	}

	/** Um bloqueio com prazo e o episódio (para a duração escalar na reincidência). */
	private record Block(long until, int episode) {
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String uri = request.getRequestURI();
		// Sonda do cluster não entra: derrubar o health check derruba o pod.
		return uri.contains("/actuator") || uri.contains("/health");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (limiteDeErros <= 0) {
			chain.doFilter(request, response);
			return;
		}
		String ip = ipResolver.resolve(request);
		long now = clock.millis();
		Block bloqueio = bloqueios.get(ip);
		if (bloqueio != null && bloqueio.until() > now) {
			negar(request, response, bloqueio.until() - now);
			return;
		}

		chain.doFilter(request, response);

		int status = response.getStatus();
		if (!contabiliza(status)) {
			return;
		}
		if (erros.size() > MAX_KEYS) {
			erros.clear();
		}
		Deque<Long> marcas = erros.computeIfAbsent(ip, k -> new ArrayDeque<>());
		boolean estourou;
		synchronized (marcas) {
			while (!marcas.isEmpty() && now - marcas.peekFirst() >= janelaMs) {
				marcas.pollFirst();
			}
			marcas.addLast(now);
			estourou = marcas.size() >= limiteDeErros;
			if (estourou) {
				marcas.clear();
			}
		}
		if (estourou) {
			negarDeVez(ip, now, status);
		}
	}

	/**
	 * O que conta como "erro de varredura": rota que não existe (404), acesso negado (401/403),
	 * payload/método/tipo inválidos (400/405/415) e o 429 do próprio teto. O 409 (conflito de
	 * estado, como e-mail já cadastrado) NÃO conta: é resposta normal de gente usando o app.
	 */
	private static boolean contabiliza(int status) {
		return status == 404 || status == 401 || status == 403 || status == 400 || status == 405
			|| status == 415 || status == 429;
	}

	private void negarDeVez(String ip, long now, int statusQueEstourou) {
		if (bloqueios.size() > MAX_KEYS) {
			long agora = now;
			bloqueios.entrySet().removeIf(entrada -> entrada.getValue().until() < agora - bloqueioMaximoMs);
		}
		// O episodio NAO some quando o bloqueio expira: e ele que faz a reincidencia durar mais.
		Block anterior = bloqueios.get(ip);
		int episodio = anterior == null ? 1 : anterior.episode() + 1;
		long duracao = Math.min(bloqueioMaximoMs, primeiroBloqueioMs * (1L << Math.min(10, episodio - 1)));
		bloqueios.put(ip, new Block(now + duracao, episodio));
		erros.remove(ip);
		LOG.warn("abuse_guard ip={} bloqueado por {} min (episodio {}, ultimo status {})", ip,
			duracao / 60_000L, episodio, statusQueEstourou);
	}

	/** 429 com o tempo de espera: o cliente honesto sabe quando voltar, o scanner não ganha nada. */
	private void negar(HttpServletRequest request, HttpServletResponse response, long restanteMs)
			throws IOException {
		long segundos = Math.max(1, (restanteMs + 999) / 1000);
		response.setStatus(429);
		response.setHeader("Retry-After", Long.toString(segundos));
		// Sem o cabecalho de CORS o navegador transforma este 429 em `NetworkError` e a pessoa ve
		// "erro de rede" em vez de "muitas requisicoes invalidas". Devolve a origem que pediu.
		String origem = request.getHeader("Origin");
		if (origem != null && !origem.isBlank()) {
			response.setHeader("Access-Control-Allow-Origin", origem);
			response.setHeader("Vary", "Origin");
		}
		response.setContentType("application/json");
		response.setCharacterEncoding("UTF-8");
		response.getWriter().write("{\"code\":\"RATE_LIMITED\",\"message\":\"Muitas requisicoes invalidas. "
			+ "Tente novamente mais tarde.\"}");
	}

}
