package com.socialcoisa.rede.network.adapters.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataNetworkStateJpaRepository extends JpaRepository<NetworkStateJpaEntity, String> {
}
