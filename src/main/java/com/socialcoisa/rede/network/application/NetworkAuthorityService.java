package com.socialcoisa.rede.network.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.network.application.port.out.NetworkAuthorityRepository;
import com.socialcoisa.rede.shared.audit.AuditTrail;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Autoridade do Control Plane: o termo que diz qual instância vale, e o quórum que autoriza subi-lo.
 *
 * <p>Até aqui o Control Plane era um ponto único de direito e de fato: qualquer pod escrevia o modo
 * em {@code network_state} e nada distinguia quem escreveu. A fase 6 (docs/rede-distribuida/
 * 06-contingencia.md §4) exige promoção <b>controlada</b>: term monotônico, quórum explícito de
 * autoridades vivas e dois controladores com o mesmo termo impossíveis por construção. Cada regra
 * vira uma recusa aqui, com código próprio:</p>
 * <ul>
 * <li><b>sem eleitorado, ninguém promove</b> — a lista de quem pode votar é decisão humana
 * declarada; derivá-la de "quem está respondendo" faria a metade de uma partição se achar maioria e
 * se promover sozinha, que é exatamente o split-brain a evitar;</li>
 * <li><b>sem quórum, ninguém promove</b> — fail secure: melhor ficar sem controlador novo do que ter
 * dois. O Control Plane que está de pé continua valendo; a promoção é que não acontece;</li>
 * <li><b>a autoridade que sobe tem de estar no eleitorado e viva</b> — uma instância que o banco
 * enxerga (viva) mas que ninguém declarou votante não decide o termo;</li>
 * <li><b>o termo só sobe por compare-and-set</b> — a gravação é {@code where term = lido}; quem
 * perder a corrida atualiza zero linhas e é recusado. Dois com o mesmo termo não existem por
 * construção, não por checagem antes de escrever;</li>
 * <li><b>o termo nunca desce nem se repete</b> — config de term menor é lixo de uma autoridade
 * velha, e aceitar de volta o número de quem já caiu seria revalidar a autoridade deposta.</li>
 * </ul>
 *
 * <p>Quem conta como "viva" é o mesmo sinal que o painel já usa para dizer quantas instâncias estão
 * lendo de verdade: {@code network_instance_readers.last_seen_at} (V293). Não há segunda noção de
 * vivacidade para conciliar.</p>
 */
@Service
public class NetworkAuthorityService {

	private static final Logger LOG = LoggerFactory.getLogger(NetworkAuthorityService.class);

	private final NetworkAuthorityRepository authorities;
	private final AuditTrail audit;
	private final Clock clock;
	private final Duration livenessWindow;

	NetworkAuthorityService(NetworkAuthorityRepository authorities, AuditTrail audit, Clock clock,
			@Value("${rede.network.authority.liveness-seconds:30}") long livenessSeconds) {
		this.authorities = authorities;
		this.audit = audit;
		this.clock = clock;
		this.livenessWindow = Duration.ofSeconds(Math.max(1, livenessSeconds));
	}

	/** A autoridade vigente; termo 0 sem dono é a autoridade original, antes da primeira promoção. */
	@Transactional(readOnly = true)
	public NetworkAuthorityRepository.AuthorityRecord current() {
		return authorities.current().orElse(new NetworkAuthorityRepository.AuthorityRecord(0, null, null));
	}

	/** O eleitorado declarado, na ordem em que entrou. */
	@Transactional(readOnly = true)
	public java.util.List<String> declared() {
		return authorities.declared();
	}

	/**
	 * Declara uma instância do Control Plane como autoridade votante. A lista é decisão humana e é
	 * ela que torna o quórum correto: promover exige maioria DESTE conjunto, não de quem responde
	 * agora.
	 */
	@Transactional
	public void declare(String actorId, String instanceId) {
		if (instanceId == null || instanceId.isBlank()) {
			throw new ApiException(400, "NETWORK_AUTHORITY_INSTANCE_REQUIRED", "Informe a instância a declarar.");
		}
		authorities.declare(instanceId.trim(), actorId, clock.instant());
		audit.record(actorId, "NETWORK_AUTHORITY_DECLARED", instanceId.trim(), "{}");
		LOG.info("Autoridade declarada: {} (por {})", instanceId.trim(), actorId);
	}

	/** Tira uma instância do eleitorado. Revogada, ela deixa de contar viva e de poder receber termo. */
	@Transactional
	public boolean revoke(String actorId, String instanceId) {
		boolean removed = authorities.revoke(instanceId);
		if (removed) {
			audit.record(actorId, "NETWORK_AUTHORITY_REVOKED", instanceId, "{}");
			LOG.info("Autoridade revogada: {} (por {})", instanceId, actorId);
		}
		return removed;
	}

	/**
	 * Promove esta instância a autoridade com termo novo — se, e só se, o quórum de autoridades
	 * vivas autoriza.
	 *
	 * <p>A checagem de vivacidade usa o MESMO relógio da janela nas duas pontas: o limiar é calculado
	 * uma vez e passado ao port, para que uma promoção no limite da janela não dependa de dois
	 * {@code now()} discretamente diferentes.</p>
	 */
	@Transactional
	public NetworkAuthorityRepository.AuthorityRecord promote(String actorId, String claimedInstanceId,
			String reason) {
		if (reason == null || reason.isBlank()) {
			throw new ApiException(400, "NETWORK_AUTHORITY_REASON_REQUIRED",
				"Declare o motivo da promoção — promover muda quem manda na rede.");
		}
		Instant now = clock.instant();
		var current = current();
		// A instância pedinte tem de estar declarada E viva: um pod que o banco não vê como leitor
		// não prova que existe, e autoridade fantasma seria termo sem dono alcançável.
		Set<String> alive = authorities.aliveSince(now.minus(livenessWindow));
		if (!alive.contains(claimedInstanceId)) {
			throw new ApiException(400, "NETWORK_AUTHORITY_NOT_ALIVE",
				"A instância pedinte não está no eleitorado ou não deu sinal dentro da janela.");
		}
		// A maioria é dos DECLARADOS vivos sobre o eleitorado INTEIRO. Contar sobre os vivos faria a
		// metade isolada da partição ter 100% "dos vivos" e se promover sozinha.
		int electorate = authorities.declared().size();
		int aliveCount = alive.size();
		if (aliveCount * 2 <= electorate) {
			throw new ApiException(409, "NETWORK_AUTHORITY_NO_QUORUM",
				"Sem quórum de autoridades vivas: ninguém promove. Melhor ficar sem controlador novo do que ter dois.");
		}
		int nextTerm = current.term() + 1;
		// O compare-and-set é o juiz da corrida: duas promoções simultâneas leem o mesmo termo e só
		// a que gravar primeiro passa. Quem atualiza zero linhas perdeu e sai sem termo.
		if (!authorities.promote(claimedInstanceId, current.term(), nextTerm, now)) {
			throw new ApiException(409, "NETWORK_AUTHORITY_PROMOTION_LOST",
				"Outra promoção gravou primeiro: releia o termo vigente.");
		}
		String trimmedReason = reason.trim();
		audit.record(actorId, "NETWORK_AUTHORITY_PROMOTED", claimedInstanceId,
			"{\"term\":" + nextTerm + ",\"from\":" + current.term() + ",\"reason\":\""
				+ escape(trimmedReason) + "\"}");
		LOG.info("Autoridade promovida: {} termo {} -> {} (por {})", claimedInstanceId, current.term(), nextTerm,
			actorId);
		return new NetworkAuthorityRepository.AuthorityRecord(nextTerm, claimedInstanceId, now);
	}

	/** Motivo entra em metadata JSON: aspas e barra do texto humano não podem quebrar o JSON. */
	private static String escape(String text) {
		return text.replace("\\", "\\\\").replace("\"", "\\\"");
	}
}