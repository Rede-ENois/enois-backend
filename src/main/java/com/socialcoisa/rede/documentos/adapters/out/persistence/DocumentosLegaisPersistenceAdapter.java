package com.socialcoisa.rede.documentos.adapters.out.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.documentos.application.port.out.DocumentosLegaisRepository;
import com.socialcoisa.rede.documentos.domain.AceiteDeDocumento;
import com.socialcoisa.rede.documentos.domain.DocumentoLegal;
import com.socialcoisa.rede.documentos.domain.DocumentoTipo;

@Repository
class DocumentosLegaisPersistenceAdapter implements DocumentosLegaisRepository {

	private final SpringDataDocumentoLegalJpaRepository documentos;
	private final SpringDataAceiteDeDocumentoJpaRepository aceites;

	DocumentosLegaisPersistenceAdapter(SpringDataDocumentoLegalJpaRepository documentos,
			SpringDataAceiteDeDocumentoJpaRepository aceites) {
		this.documentos = documentos;
		this.aceites = aceites;
	}

	@Override
	public List<DocumentoLegal> vigentes(Collection<DocumentoTipo> tipos) {
		return documentos.vigentes(tipos.stream().map(Enum::name).toList()).stream()
			.map(DocumentoLegalJpaEntity::toDomain).toList();
	}

	@Override
	public Optional<DocumentoLegal> vigente(DocumentoTipo tipo) {
		return vigentes(List.of(tipo)).stream().max(Comparator.comparingInt(DocumentoLegal::versao));
	}

	@Override
	public Optional<DocumentoLegal> porId(String id) {
		return documentos.findById(id).map(DocumentoLegalJpaEntity::toDomain);
	}

	@Override
	public List<DocumentoLegal> emRevisao(Collection<DocumentoTipo> tipos) {
		return documentos.emRevisao(tipos.stream().map(Enum::name).toList()).stream()
			.map(DocumentoLegalJpaEntity::toDomain).toList();
	}

	@Override
	public Optional<DocumentoLegal> versao(DocumentoTipo tipo, int versao) {
		return documentos.findByTipoAndVersao(tipo.name(), versao).map(DocumentoLegalJpaEntity::toDomain);
	}

	@Override
	public int proximaVersao(DocumentoTipo tipo) {
		return documentos.ultimaVersao(tipo.name()) + 1;
	}

	@Override
	public List<DocumentoLegal> historico(DocumentoTipo tipo) {
		return documentos.findByTipoOrderByVersaoDesc(tipo.name()).stream().map(DocumentoLegalJpaEntity::toDomain)
			.toList();
	}

	@Override
	public DocumentoLegal save(DocumentoLegal documento) {
		DocumentoLegalJpaEntity entity = documentos.findById(documento.id())
			.orElseGet(() -> DocumentoLegalJpaEntity.from(documento));
		entity.aplicar(documento);
		return documentos.save(entity).toDomain();
	}

	@Override
	public Optional<AceiteDeDocumento> aceite(String userId, String documentoId) {
		return aceites.findByUserIdAndDocumentoId(userId, documentoId).map(AceiteDeDocumentoJpaEntity::toDomain);
	}

	@Override
	public List<AceiteDeDocumento> aceitesDoUsuario(String userId) {
		return aceites.findByUserIdOrderByAceitoEmDesc(userId).stream().map(AceiteDeDocumentoJpaEntity::toDomain)
			.toList();
	}

	@Override
	public AceiteDeDocumento saveAceite(AceiteDeDocumento aceite) {
		return aceites.save(AceiteDeDocumentoJpaEntity.from(aceite)).toDomain();
	}

	@Transactional
	@Override
	public int encerrarVigencia(DocumentoTipo tipo, Instant quando) {
		return documentos.encerrarVigencia(tipo.name());
	}
}
