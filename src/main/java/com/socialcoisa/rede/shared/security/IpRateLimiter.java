package com.socialcoisa.rede.shared.security;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Limite de requisições por IP e por ação — a camada determinística do antifraude.
 *
 * Por que existe, se já há `@RateLimiter` (resilience4j) nos endpoints: aquele limite é **global**
 * (uma instância para o app inteiro). Isso tem dois defeitos opostos e igualmente ruins: um atacante
 * sozinho consome a cota e **derruba o acesso de todo mundo**, e mil contas criadas de mil IPs passam
 * sem esforço. O limite por origem resolve os dois.
 *
 * Como funciona: janela deslizante em memória, por chave `ação|ip`. É deliberadamente simples —
 * sem Redis, sem dependência nova. A consequência aceita e documentada: **o limite vale por pod**
 * (com N réplicas, o teto efetivo é N × o configurado). Isso ainda corta o ataque barato, que é o
 * objetivo da fase; limite distribuído exato fica para quando houver armazenamento compartilhado.
 *
 * Não guarda o IP em log nem em resposta: a chave vive só na memória deste processo.
 */
@Component
public class IpRateLimiter {

	/** Ações com limites próprios (o nome é o que o filtro usa). */
	public static final String SIGNUP = "signup";
	public static final String LOGIN = "login";
	public static final String PASSWORD = "password";
	public static final String EMAIL = "email";
	public static final String TWO_FACTOR = "2fa";
	public static final String CONTRIBUTION = "contribution";
	public static final String REFRESH = "refresh";
	/**
	 * Emissão do desafio de cadastro. Teto próprio porque emitir é o passo anterior ao cadastro:
	 * sem ele, um cliente dentro do teto de cadastro podia pedir desafios sem limite.
	 */
	public static final String ISSUE_CHALLENGE = "signup-challenge";
	/** Inscrição para servir um nó (formulário público com RG/CPF) e a abertura do link de chave. */
	public static final String NODE_JOIN = "node-join";
	/**
	 * Abertura do link de chave de nó. Cota própria: o formulário aceita poucas inscrições por hora,
	 * e o link é aberto por quem já foi aprovado — dividir a cota faria a pessoa que acabou de
	 * receber o e-mail ser barrada pelo próprio formulário que ela já preencheu.
	 */
	public static final String NODE_REVEAL = "node-reveal";

	/** Teto de chaves guardadas: passou disso, a limpeza roda na hora (evita crescer sem limite). */
	private static final int MAX_KEYS = 20_000;

	private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();
	private final Clock clock;

	private final int signupLimit;
	private final int loginLimit;
	private final int passwordLimit;
	private final int emailLimit;
	private final int twoFactorLimit;
	private final int contributionLimit;
	private final int refreshLimit;
	private final int issueChallengeLimit;
	private final int nodeJoinLimit;
	private final int nodeRevealLimit;
	private final long signupWindowMs;
	private final long loginWindowMs;
	private final long passwordWindowMs;
	private final long emailWindowMs;
	private final long twoFactorWindowMs;
	private final long contributionWindowMs;
	private final long refreshWindowMs;
	private final long issueChallengeWindowMs;
	private final long nodeJoinWindowMs;
	private final long nodeRevealWindowMs;

	public IpRateLimiter(Clock clock,
			@Value("${rede.security.rate-limit.signup.limit:5}") int signupLimit,
			@Value("${rede.security.rate-limit.signup.window-minutes:60}") long signupWindowMinutes,
			@Value("${rede.security.rate-limit.login.limit:20}") int loginLimit,
			@Value("${rede.security.rate-limit.login.window-minutes:15}") long loginWindowMinutes,
			@Value("${rede.security.rate-limit.password.limit:5}") int passwordLimit,
			@Value("${rede.security.rate-limit.password.window-minutes:15}") long passwordWindowMinutes,
			@Value("${rede.security.rate-limit.email.limit:5}") int emailLimit,
			@Value("${rede.security.rate-limit.email.window-minutes:15}") long emailWindowMinutes,
			@Value("${rede.security.rate-limit.2fa.limit:20}") int twoFactorLimit,
			@Value("${rede.security.rate-limit.2fa.window-minutes:15}") long twoFactorWindowMinutes,
			@Value("${rede.security.rate-limit.contribution.limit:5}") int contributionLimit,
			@Value("${rede.security.rate-limit.contribution.window-minutes:60}") long contributionWindowMinutes,
			@Value("${rede.security.rate-limit.refresh.limit:120}") int refreshLimit,
			@Value("${rede.security.rate-limit.refresh.window-minutes:15}") long refreshWindowMinutes,
			@Value("${rede.security.rate-limit.signup-challenge.limit:30}") int issueChallengeLimit,
			@Value("${rede.security.rate-limit.signup-challenge.window-minutes:60}") long issueChallengeWindowMinutes,
			@Value("${rede.security.rate-limit.node-join.limit:3}") int nodeJoinLimit,
			@Value("${rede.security.rate-limit.node-join.window-minutes:60}") long nodeJoinWindowMinutes,
			@Value("${rede.security.rate-limit.node-reveal.limit:10}") int nodeRevealLimit,
			@Value("${rede.security.rate-limit.node-reveal.window-minutes:15}") long nodeRevealWindowMinutes) {
		this.clock = clock;
		this.signupLimit = signupLimit;
		this.loginLimit = loginLimit;
		this.passwordLimit = passwordLimit;
		this.emailLimit = emailLimit;
		this.twoFactorLimit = twoFactorLimit;
		this.contributionLimit = contributionLimit;
		this.signupWindowMs = signupWindowMinutes * 60_000L;
		this.loginWindowMs = loginWindowMinutes * 60_000L;
		this.passwordWindowMs = passwordWindowMinutes * 60_000L;
		this.emailWindowMs = emailWindowMinutes * 60_000L;
		this.twoFactorWindowMs = twoFactorWindowMinutes * 60_000L;
		this.contributionWindowMs = contributionWindowMinutes * 60_000L;
		this.refreshLimit = refreshLimit;
		this.refreshWindowMs = refreshWindowMinutes * 60_000L;
		this.issueChallengeLimit = issueChallengeLimit;
		this.issueChallengeWindowMs = issueChallengeWindowMinutes * 60_000L;
		this.nodeJoinLimit = nodeJoinLimit;
		this.nodeJoinWindowMs = nodeJoinWindowMinutes * 60_000L;
		this.nodeRevealLimit = nodeRevealLimit;
		this.nodeRevealWindowMs = nodeRevealWindowMinutes * 60_000L;
	}

	/**
	 * Registra a tentativa e diz se ela passa. Quando devolve `false`, a tentativa **não** é contada
	 * (senão quem bateu no teto ficaria preso para sempre, alimentando a própria janela).
	 */
	public boolean tryAcquire(String action, String ip) {
		long now = clock.millis();
		long window = windowFor(action);
		int limit = limitFor(action);
		if (limit <= 0 || window <= 0) {
			return true;
		}
		if (hits.size() > MAX_KEYS) {
			sweep(now);
		}

		Deque<Long> timestamps = hits.computeIfAbsent(action + "|" + ip, key -> new ArrayDeque<>());
		synchronized (timestamps) {
			while (!timestamps.isEmpty() && now - timestamps.peekFirst() >= window) {
				timestamps.pollFirst();
			}
			if (timestamps.size() >= limit) {
				return false;
			}
			timestamps.addLast(now);
			return true;
		}
	}

	private long windowFor(String action) {
		return switch (action) {
			case SIGNUP -> signupWindowMs;
			case LOGIN -> loginWindowMs;
			case PASSWORD -> passwordWindowMs;
			case EMAIL -> emailWindowMs;
			case TWO_FACTOR -> twoFactorWindowMs;
			case CONTRIBUTION -> contributionWindowMs;
			case REFRESH -> refreshWindowMs;
			case ISSUE_CHALLENGE -> issueChallengeWindowMs;
			case NODE_JOIN -> nodeJoinWindowMs;
			case NODE_REVEAL -> nodeRevealWindowMs;
			default -> 0L;
		};
	}

	private int limitFor(String action) {
		return switch (action) {
			case SIGNUP -> signupLimit;
			case LOGIN -> loginLimit;
			case PASSWORD -> passwordLimit;
			case EMAIL -> emailLimit;
			case TWO_FACTOR -> twoFactorLimit;
			case CONTRIBUTION -> contributionLimit;
			case REFRESH -> refreshLimit;
			case ISSUE_CHALLENGE -> issueChallengeLimit;
			case NODE_JOIN -> nodeJoinLimit;
			case NODE_REVEAL -> nodeRevealLimit;
			default -> 0;
		};
	}

	/** Tira as chaves que já venceram (roda só quando o mapa cresce demais). */
	private void sweep(long now) {
		hits.entrySet().removeIf(entry -> {
			Deque<Long> timestamps = entry.getValue();
			synchronized (timestamps) {
				String action = entry.getKey().substring(0, entry.getKey().indexOf('|'));
				long window = windowFor(action);
				while (!timestamps.isEmpty() && now - timestamps.peekFirst() >= window) {
					timestamps.pollFirst();
				}
				return timestamps.isEmpty();
			}
		});
	}
}
