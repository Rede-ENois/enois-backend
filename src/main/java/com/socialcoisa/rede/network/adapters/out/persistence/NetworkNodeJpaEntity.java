package com.socialcoisa.rede.network.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.network.domain.NodeProfile;
import com.socialcoisa.rede.network.domain.NodeStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "network_nodes")
class NetworkNodeJpaEntity {

	@Id
	@Column(length = 40)
	private String id;

	@Column(name = "display_name", nullable = false, length = 120)
	private String displayName;

	@Column(name = "owner_user_id", nullable = false, length = 40)
	private String ownerUserId;

	@Column(nullable = false, length = 24)
	private String status;

	@Column(nullable = false, length = 255)
	private String profiles;

	@Column(name = "declared_cpu_cores", nullable = false)
	private int declaredCpuCores;

	@Column(name = "declared_memory_mb", nullable = false)
	private int declaredMemoryMb;

	@Column(name = "declared_disk_mb", nullable = false)
	private int declaredDiskMb;

	@Column(name = "declared_bandwidth_mbps", nullable = false)
	private int declaredBandwidthMbps;

	@Column(name = "observed_cpu_percent")
	private Double observedCpuPercent;

	@Column(name = "observed_memory_mb")
	private Integer observedMemoryMb;

	@Column(name = "observed_running_tasks")
	private Integer observedRunningTasks;

	@Column(name = "observed_latency_ms")
	private Integer observedLatencyMs;

	@Column(name = "consecutive_failures", nullable = false)
	private int consecutiveFailures;

	@Column(name = "quarantined_until")
	private Instant quarantinedUntil;

	@Column(name = "quarantine_reason", length = 40)
	private String quarantineReason;

	@Column(length = 64)
	private String region;

	@Column(length = 2)
	private String country;

	@Column(name = "runtime_version", length = 32)
	private String runtimeVersion;

	@Column(name = "approved_by", length = 40)
	private String approvedBy;

	@Column(name = "approved_at")
	private Instant approvedAt;

	@Column(name = "revoked_at")
	private Instant revokedAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "last_seen_at")
	private Instant lastSeenAt;

	/** Versão da permissão: 1 no enroll, +1 em cada transição administrativa (V284). */
	@Column(name = "config_version", nullable = false)
	private int configVersion = 1;

	/** Instante em que a permissão vigente passou a valer; null em linha anterior à V284. */
	@Column(name = "config_issued_at")
	private Instant configIssuedAt;

	protected NetworkNodeJpaEntity() {
	}

	NetworkNodeJpaEntity(String id, String displayName, String ownerUserId, String status, String profiles,
			int declaredCpuCores, int declaredMemoryMb, int declaredDiskMb, int declaredBandwidthMbps, String region,
			String country, Instant createdAt) {
		this.id = id;
		this.displayName = displayName;
		this.ownerUserId = ownerUserId;
		this.status = status;
		this.profiles = profiles;
		this.declaredCpuCores = declaredCpuCores;
		this.declaredMemoryMb = declaredMemoryMb;
		this.declaredDiskMb = declaredDiskMb;
		this.declaredBandwidthMbps = declaredBandwidthMbps;
		this.region = region;
		this.country = country;
		this.createdAt = createdAt;
		// O enroll é a primeira permissão do nó: versão 1 já nasce carimbada com o instante em que
		// ele entrou. Sem isso a leitura seguinte teria de inventar um instante, e inventar é o que
		// faz a config parecer nova sem ter mudado.
		this.configVersion = 1;
		this.configIssuedAt = createdAt;
	}

	NetworkNode toDomain() {
		return new NetworkNode(id, displayName, ownerUserId, NodeStatus.valueOf(status),
			NodeProfilesCsv.parse(profiles), region, country, runtimeVersion, lastSeenAt, declaredCpuCores,
			declaredMemoryMb, declaredDiskMb, declaredBandwidthMbps, observedCpuPercent, observedMemoryMb,
			observedRunningTasks, observedLatencyMs, consecutiveFailures, quarantinedUntil, quarantineReason);
	}

	String status() {
		return status;
	}

	int configVersion() {
		return configVersion;
	}

	Instant configIssuedAt() {
		return configIssuedAt;
	}

	Instant createdAt() {
		return createdAt;
	}

	/** CSV de perfis: o domínio recebe o conjunto, o banco guarda texto. */
	static final class NodeProfilesCsv {

		private NodeProfilesCsv() {
		}

		static String join(java.util.Collection<NodeProfile> profiles) {
			return profiles.stream().map(Enum::name).sorted().collect(java.util.stream.Collectors.joining(","));
		}

		static java.util.Set<NodeProfile> parse(String csv) {
			if (csv == null || csv.isBlank()) {
				return java.util.Set.of();
			}
			return java.util.Arrays.stream(csv.split(","))
				.map(String::trim)
				.filter(item -> !item.isEmpty())
				.map(NodeProfile::valueOf)
				.collect(java.util.stream.Collectors.toUnmodifiableSet());
		}
	}
}
