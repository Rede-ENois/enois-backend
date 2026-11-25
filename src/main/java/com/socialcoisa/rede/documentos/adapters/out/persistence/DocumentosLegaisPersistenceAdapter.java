package com.socialcoisa.rede.documentos.adapters.out.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.socialcoisa.rede.documentos.application.port.out.DocumentosLegaisRepository;
import com.socialcoisa.rede.documentos.domain.AceiteDeDocumento;
import com.socialcoisa.rede.documentos.domain.DocumentoLegal;
import com.socialcoisa.rede.documentos.domain.DocumentoTipo;

@Repository
class DocumentosLegaisPersistenceAdapter implements DocumentosLegaisRepository {

	private final SpringDataDocumentoLegalJpaRepository documentos;
	private final SpringDataAceiteDeDocumentoJpaRepository aceites;
	/**
	 * O aceite é gravado em transação própria ({@code REQUIRES_NEW}). Duas aceitações da mesma
	 * versão ao mesmo tempo disputam o índice único e a perdedora não pode arrastar a transação de
	 * fora: exceção capturada dentro de uma transação marca tudo para rollback, o commit falharia
	 * e a resposta seria erro em vez do aceite idempotente prometido.
	 */
	private final TransactionTemplate transacaoDoAceite;

	DocumentosLegaisPersistenceAdapter(SpringDataDocumentoLegalJpaRepository documentos,
			SpringDataAceiteDeDocumentoJpaRepository aceites, PlatformTransactionManager transacoes) {
		this.documentos = documentos;
		this.aceites = aceites;
		this.transacaoDoAceite = new TransactionTemplate(transacoes);
		this.transacaoDoAceite.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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
		try {
			return transacaoDoAceite.execute(status ->
				aceites.saveAndFlush(AceiteDeDocumentoJpaEntity.from(aceite)).toDomain());
		}
		catch (DataIntegrityViolationException duplicata) {
			// A corrida do aceite: duas aceitações da mesma versão ao mesmo tempo. O índice único
			// (user_id, documento_id) decide qual das duas grava; a que perdeu devolve o aceite que
			// a outra gravou, em vez de estourar o erro para quem aceitou.
			AceiteDeDocumento existente = transacaoDoAceite.execute(status ->
				aceites.findByUserIdAndDocumentoId(aceite.userId(), aceite.documentoId())
					.map(AceiteDeDocumentoJpaEntity::toDomain).orElse(null));
			if (existente == null) {
				throw duplicata;
			}
			return existente;
		}
	}

	@Transactional
	@Override
	public int encerrarVigencia(DocumentoTipo tipo, Instant quando) {
		return documentos.encerrarVigencia(tipo.name());
	}
}
