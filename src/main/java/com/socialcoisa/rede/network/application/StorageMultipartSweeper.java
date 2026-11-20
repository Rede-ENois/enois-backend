package com.socialcoisa.rede.network.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.socialcoisa.rede.shared.audit.AuditTrail;

/**
 * O upload multipart que ninguém concluiu nem abortou (brief §3.2, "garbage collection"; §17,
 * "recuperações pendentes").
 *
 * <h2>O buraco</h2>
 * <p>O doc 18 §4.2 diz que <i>"o prazo é gravado no início ({@code rede.storage.multipart.ttl-hours},
 * padrão 24 h) e {@code sweep()} descarta o vencido"</i> — mas o {@code sweep()} não existia. O que
 * o código fazia com o prazo é recusar a próxima parte ({@code NoSuchUpload} em {@code receivePart} e
 * na conclusão) e só então apagar. Um cliente que morre no meio do upload nunca manda a parte
 * seguinte: a linha fica em {@code network_storage_multipart_uploads}, as partes ficam em
 * {@code network_storage_multipart_parts}, os bytes ficam em {@code storageRoot/multipart/<id>/}, e
 * nada disso tem prazo de validade — ocupa o disco de quem contribui para sempre.</p>
 *
 * <h2>Quem vence é o prazo da linha, não o da instalação</h2>
 * <p>O cliente pode pedir um prazo menor no {@code CreateMultipartUpload}, e o prazo dele é o que
 * está gravado. O varredor lê {@code expires_at} de cada upload e não recalcula nada: um upload
 * começado com 1 h de prazo vence em 1 h, não em 24 h, e um de 24 h não é apagado por um varredor
 * com configuração menor. O prazo da instalação continua sendo só o padrão do início.</p>
 *
 * <h2>Apagar só o que já venceu</h2>
 * <p>Vencido significa {@code expires_at <= agora}. O cliente que recupera o relógio e manda a parte
 * às 23h59 de um upload de 24 h ainda entra pelo caminho normal (o relógio dele é o do Control
 * Plane); o que já passou do prazo é lixo declarado pelo próprio cliente no início.</p>
 *
 * <h2>Como é a varredura</h2>
 * <p>Primeiro as linhas (uma transação, só as vencidas, com teto), depois o diretório de cada id
 * apagado. A ordem importa: um {@code abort} concorrente do cliente apaga o diretório da mesma
 * forma, e {@code deleteIfExists} é idempotente nos dois lados. Se o processo cair entre as duas,
 * sobra um diretório sem linha — que o varredor de órfãos recolhe na passada seguinte (aqui mesmo:
 * diretório sem upload correspondente é apagado).</p>
 *
 * <p><b>Não é um healing de objeto:</b> nada aqui toca o catálogo. Um upload multipart que vence
 * nunca virou objeto — é o desenho (doc 18 §4.2: "nada vira objeto antes da conclusão") —, então
 * não há cópia, durabilidade nem réplica a repor.</p>
 */
@Service
public class StorageMultipartSweeper {

	static final Logger LOG = LoggerFactory.getLogger(StorageMultipartSweeper.class);

	/** Teto por passada: cada upload apagado é I/O de disco, e o lote não pode segurar o banco. */
	static final int MAX_PER_RUN = 200;

	/** Atores da automação na trilha de auditoria (não há sessão). */
	static final String ATOR = "storage-multipart-sweeper";

	private final StorageMultipartService multipart;
	private final AuditTrail audit;
	private final Clock clock;
	private final boolean enabled;

	StorageMultipartSweeper(StorageMultipartService multipart, AuditTrail audit, Clock clock,
			@Value("${rede.storage.multipart.sweep-enabled:true}") boolean enabled) {
		this.multipart = multipart;
		this.audit = audit;
		this.clock = clock;
		this.enabled = enabled;
	}

	/** O que a leitura mostra, sem apagar nada. */
	public record Pending(String uploadId, String bucket, String key, String ownerUserId, Instant createdAt,
			Instant expiresAt, long ageMinutes, boolean expired) {
	}

	/** O que uma passada fez. {@code orphansRemoved} são diretórios sem linha (sobra de passada
	 * interrompida ou de {@code abort} que não chegou ao disco). */
	public record Report(boolean dryRun, Instant now, int pendingCount, int expiredCount, long expiredParts,
			List<Pending> expired, List<String> removed, List<String> orphansRemoved) {
	}

	@Scheduled(initialDelayString = "${rede.storage.multipart.sweep-initial-delay-ms:600000}",
		fixedDelayString = "${rede.storage.multipart.sweep-delay-ms:3600000}")
	public void run() {
		if (!enabled) {
			return;
		}
		try {
			var relatorio = sweep(false, MAX_PER_RUN, true);
			if (!relatorio.removed().isEmpty() || !relatorio.orphansRemoved().isEmpty()) {
				LOG.info("multipart sweep: removidos={} orfaos={} partes={}", relatorio.removed().size(),
					relatorio.orphansRemoved().size(), relatorio.expiredParts());
			}
		}
		catch (RuntimeException falha) {
			// Uma passada ruim não pode desligar a coleta até o próximo restart.
			LOG.warn("multipart sweep: passada falhou: {}", falha.getMessage());
		}
	}

	/** O relatório da leitura: quantos abertos, quantos vencidos e quais — sem apagar nem recolher. */
	public Report report(int limit) {
		return sweep(true, limit, false);
	}

	/**
	 * O ato de coleta da rota e da varredura automática: lista as vencidas, apaga até o teto e recolhe
	 * diretório sem linha. A leitura ({@link #expired}) é o mesmo, sem apagar e sem recolher.
	 */
	public Report collect(int limit, boolean dryRun) {
		return sweep(dryRun, limit, !dryRun);
	}

	/** {@code collectOrphans} é da coleta: a leitura só conta e não recolhe nada. */
	private Report sweep(boolean dryRun, int limit, boolean collectOrphans) {
		Instant now = clock.instant();
		int teto = Math.max(1, Math.min(limit <= 0 ? MAX_PER_RUN : limit, MAX_PER_RUN));
		int abertos = multipart.uploadsOpen();
		List<Pending> vencidas = multipart.expiredUploads(now, teto).stream()
			.map(u -> new Pending(u.uploadId(), u.bucket(), u.key(), u.ownerUserId(), u.createdAt(),
				u.expiresAt(), java.time.Duration.between(u.createdAt(), now).toMinutes(), true))
			.toList();
		long partes = vencidas.stream().mapToLong(p -> multipart.partCount(p.uploadId())).sum();
		if (dryRun) {
			return new Report(true, now, abertos, multipart.expiredCount(now), partes, vencidas, List.of(),
				List.of());
		}
		List<String> removidos = new java.util.ArrayList<>();
		for (Pending pendente : vencidas) {
			// `discard` apaga partes, linha e diretório — o mesmo caminho do `abort` do cliente, para
			// que não haja duas formas de remover um upload.
			multipart.abort(pendente.uploadId());
			audit.record(ATOR, "STORAGE_MULTIPART_EXPIRED", pendente.uploadId(),
				"{\"bucket\":\"" + escape(pendente.bucket()) + "\",\"key\":\"" + escape(pendente.key())
					+ "\",\"owner\":\"" + escape(pendente.ownerUserId()) + "\",\"ageMinutes\":"
					+ pendente.ageMinutes() + "}");
			removidos.add(pendente.uploadId());
		}
		// Diretórios cujo upload já não existe: passada interrompida ou abort que falhou no disco.
		List<String> orfaos = collectOrphans
			? multipart.removeOrphanDirectories().stream().map(p -> p.getFileName().toString()).toList()
			: List.<String>of();
		if (!orfaos.isEmpty()) {
			LOG.warn("multipart sweep: {} diretorio(s) sem upload recolhidos", orfaos.size());
		}
		return new Report(false, now, abertos, multipart.expiredCount(now), partes, vencidas,
			List.copyOf(removidos), orfaos);
	}

	/** Nomes do bucket e da chave vêm do cliente: nunca entram no JSON de auditoria sem escape. */
	static String escape(String valor) {
		return valor == null ? "" : valor.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	/** O que o painel mostra: se a coleta está ligada (o padrão é ligada; ela só apaga o vencido). */
	public boolean enabled() {
		return enabled;
	}
}