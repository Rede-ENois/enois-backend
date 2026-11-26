package com.socialcoisa.rede.documentos.adapters.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataAceiteDeDocumentoJpaRepository extends JpaRepository<AceiteDeDocumentoJpaEntity, String> {

	Optional<AceiteDeDocumentoJpaEntity> findByUserIdAndDocumentoId(String userId, String documentoId);

	List<AceiteDeDocumentoJpaEntity> findByUserIdOrderByAceitoEmDesc(String userId);
}
