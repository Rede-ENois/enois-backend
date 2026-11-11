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
	}

	NetworkNode toDomain() {
		return new NetworkNode(id, displayName, ownerUserId, NodeStatus.valueOf(status),
			NodeProfilesCsv.parse(profiles), region, country, runtimeVersion, lastSeenAt, declaredCpuCores,
			declaredMemoryMb, observedCpuPercent, observedMemoryMb, observedRunningTasks);
	}

	String status() {
		return status;
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
