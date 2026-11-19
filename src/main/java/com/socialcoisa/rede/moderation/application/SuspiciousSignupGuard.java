package com.socialcoisa.rede.moderation.application;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.shared.security.SignupAttackGuard;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Cadastro com o padrão de assinatura do ataque não nasce, e repetições do mesmo aparelho numa rede
 * pausam cadastros por um período curto e com prazo.
 *
 * Depois de 20/10 e 22/10 (contas "probe-x3mm", "probe-p914", sempre do mesmo provedor de
 * hospedagem), esperar a triagem automática é esperar o atacante criar o próximo lote. Aqui a
 * contenção é imediata: o nome/username derivado bate no padrão configurado
 * ({@code rede.security.suspicious.name-patterns}, o mesmo da triagem) → 403 genérico, sem
 * ensinar a regra.
 *
 * <p>A pausa nunca é de um IP por causa de <b>uma</b> conta: o que identifica quem repete é o par
 * IP + impressão do aparelho (o mesmo navegador atrás de proxies diferentes), e esse par pausa
 * depois de {@value #PAIR_STRIKES} ocorrências. A pausa do IP inteiro só vem depois de
 * {@value #IP_STRIKES} ocorrências e vale por {@value #IP_COOLDOWN_MINUTES} minutos — um IP é
 * compartilhado (NAT, operadora, escola), então puni-lo com uma conta seria punir a vizinhança.</p>
 *
 * <p>Nada é gravado em {@code blocked_ips} e o login não é afetado: bloqueio permanente é decisão
 * de moderador no painel.</p>
 *
 * <p>ponytail: memória por pod; um armazenamento compartilhado se a pausa precisar valer entre
 * réplicas.</p>
 */
@Service
public class SuspiciousSignupGuard implements SignupAttackGuard {

	private static final Logger LOG = LoggerFactory.getLogger(SuspiciousSignupGuard.class);

	private final boolean enabled;
	private final List<String> patterns;
	/** Um único nome suspeito não pode derrubar o IP de uma rede inteira (N-08). */
	private final java.util.Map<String, java.util.Deque<Long>> recentBySubject =
		new java.util.concurrent.ConcurrentHashMap<>();
	static final int PAIR_STRIKES = 3;
	static final int IP_STRIKES = 6;
	static final long STRIKE_WINDOW_MS = 10 * 60 * 1000;
	static final long PAIR_COOLDOWN_MS = 30 * 60 * 1000;
	static final long IP_COOLDOWN_MS = 30 * 60 * 1000;
	private static final int MAX_KEYS = 20_000;
	private final java.util.Map<String, Long> cooldownUntil = new java.util.concurrent.ConcurrentHashMap<>();
	/** Ceiling for both maps, so a flood of distinct IPs cannot grow memory without bound. */
	private final int maxKeys;

	@org.springframework.beans.factory.annotation.Autowired
	public SuspiciousSignupGuard(
			@Value("${rede.security.signup-guard-enabled:true}") boolean enabled,
			@Value("${rede.security.suspicious.name-patterns:probe}") String patterns) {
		this(enabled, patterns, MAX_KEYS);
	}

	SuspiciousSignupGuard(boolean enabled, String patterns, int maxKeys) {
		this.maxKeys = maxKeys;
		this.enabled = enabled;
		this.patterns = Arrays.stream(patterns.split(","))
			.map(String::trim)
			.filter(pattern -> !pattern.isEmpty())
			.map(pattern -> pattern.toLowerCase(Locale.ROOT))
			.toList();
	}

	/** Sem impressão do aparelho: a única chave possível é o IP, e só ele acumula as ocorrências. */
	@Override
	public void checkSignup(String name, String email, String ip) {
		checkSignup(name, email, ip, null);
	}

	@Override
	public void checkSignup(String name, String email, String ip, String deviceFingerprint) {
		if (!enabled || patterns.isEmpty()) {
			return;
		}
		long now = System.currentTimeMillis();
		String address = StringUtils.hasText(ip) ? ip.trim() : "";
		for (String subject : cooldownKeys(address, deviceFingerprint)) {
			Long until = cooldownUntil.get(subject);
			if (until != null && until > now) {
				throw refused();
			}
		}
		if (!StringUtils.hasText(name)) {
			return;
		}
		String typed = name.trim().toLowerCase(Locale.ROOT);
		if (patterns.stream().noneMatch(typed::contains)) {
			return;
		}
		registerStrike("pair:" + address + "|" + fingerprintKey(deviceFingerprint), PAIR_STRIKES,
			PAIR_COOLDOWN_MS, now);
		registerStrike("ip:" + address, IP_STRIKES, IP_COOLDOWN_MS, now);
		LOG.warn("Cadastro recusado por padrão de ataque (ip={}, email={})", address, mask(email));
		throw refused();
	}

	/** Mensagem genérica: o atacante não descobre qual palavra disparou o bloqueio. */
	private static ApiException refused() {
		return new ApiException(403, "SIGNUP_REFUSED",
			"Não foi possível criar a conta agora. Tente novamente mais tarde.");
	}

	/**
	 * Chaves que podem estar em pausa para este pedido. A do aparelho sempre pode; a do IP só entra
	 * quando o pedido traz aparelho identificado — sem isso não há como distinguir a conta isolada
	 * da repetição, e uma conta sozinha não cala uma rede inteira.
	 */
	private static List<String> cooldownKeys(String address, String deviceFingerprint) {
		if (!StringUtils.hasText(address)) {
			return List.of();
		}
		if (!StringUtils.hasText(deviceFingerprint)) {
			return List.of();
		}
		return List.of("pair:" + address + "|" + fingerprintKey(deviceFingerprint), "ip:" + address);
	}

	/** A impressão do aparelho é do cliente: só a forma reduzida entra na chave, nunca em log. */
	private static String fingerprintKey(String deviceFingerprint) {
		if (!StringUtils.hasText(deviceFingerprint)) {
			return "-";
		}
		return com.socialcoisa.rede.shared.security.Hashes.sha256Hex(deviceFingerprint.trim()).substring(0, 16);
	}

	private void registerStrike(String key, int strikesToBlock, long cooldownMs, long now) {
		if (key.endsWith("|") || key.equals("ip:")) {
			return;
		}
		makeRoom(now);
		java.util.Deque<Long> strikes = recentBySubject.computeIfAbsent(key, ignored -> new java.util.ArrayDeque<>());
		synchronized (strikes) {
			while (!strikes.isEmpty() && now - strikes.peekFirst() > STRIKE_WINDOW_MS) {
				strikes.pollFirst();
			}
			strikes.addLast(now);
			if (strikes.size() < strikesToBlock) {
				return;
			}
		}
		recentBySubject.remove(key);
		cooldownUntil.put(key, now + cooldownMs);
		LOG.warn("Cadastros pausados após indícios repetidos (chave={})",
			key.startsWith("ip:") ? "rede" : "aparelho-na-rede");
	}

	/**
	 * O teto vale para o par de mapas: janelas vencidas saem, e se ainda faltar espaço as pausas
	 * mais antigas cedem lugar — a memória não pode crescer sem limite sob varredura.
	 */
	private void makeRoom(long now) {
		if (recentBySubject.size() + cooldownUntil.size() < maxKeys) {
			return;
		}
		recentBySubject.values().removeIf(strikes -> strikes.isEmpty());
		cooldownUntil.values().removeIf(until -> until <= now);
		if (recentBySubject.size() + cooldownUntil.size() >= maxKeys) {
			cooldownUntil.clear();
		}
		if (recentBySubject.size() >= maxKeys) {
			// Pausa de aparelho é o que sobra quando a varredura enche a memória: as janelas de
			// ocorrência vencidas ou mais antigas cedem espaço antes de qualquer contador vivo sair.
			recentBySubject.clear();
		}
	}

	int trackedKeys() {
		return recentBySubject.size() + cooldownUntil.size();
	}

	/** O e-mail não vai inteiro para o log: só o bastante para o time reconhecer a conta. */
	private String mask(String email) {
		if (!StringUtils.hasText(email) || !email.contains("@")) {
			return "?";
		}
		int at = email.indexOf('@');
		return email.charAt(0) + "***" + email.substring(at);
	}
}