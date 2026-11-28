package com.socialcoisa.rede.moderation.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.socialcoisa.rede.media.application.port.out.MediaPageCatalog.PageRef;
import com.socialcoisa.rede.moderation.domain.MediaSafetyCheck;

/**
 * Pontas da checagem de mídia.
 *
 * <p>A checagem fala em PEÇAS, nunca em tipo de arquivo: para imagem a peça é o próprio arquivo;
 * para PDF são as páginas renderizadas. Estes ports deixam o aparelho de segurança olhar a mídia e
 * mandar apagar o que foi barrado sem saber que existe PDF no produto — e sem depender do cadastro
 * de mídia diretamente.</p>
 */
public final class SafetyPorts {

	private SafetyPorts() {
	}

	/** A mídia como o classificador a enxerga. */
	public interface AnalysedPieces {

		/** Imagem: a própria mídia. PDF: cada página renderizada. Vazio = nada para olhar. */
		List<PageRef> piecesOf(String mediaId);

		Map<String, MediaSafetyCheck> resultsOf(List<String> pieceIds);

		/** O que a peça é HOJE: aberta para análise (e depois de quando), já retirada ou inexistente. */
		PieceStatus statusOf(String pieceId);

		/**
		 * Documento dono desta peça, quando a peça é folha de PDF; vazio quando ela é a própria mídia.
		 * Folha é asset irmão do documento, não mídia de ninguém: quem chega pela folha precisa saber
		 * de que documento ela é para olhar o documento, não a folha solta.
		 */
		java.util.Optional<String> documentOf(String pieceId);

	}

	/** Situação de uma peça numa varredura; {@code createdAt} é quando ela passou a existir. */
	public record PieceStatus(PieceState state, Instant createdAt) {

		public PieceStatus {
			createdAt = createdAt == null ? Instant.EPOCH : createdAt;
		}

		public static PieceStatus missing() {
			return new PieceStatus(PieceState.MISSING, Instant.EPOCH);
		}
	}

	/** O que a peça é hoje. */
	public enum PieceState {
		OPEN, WITHDRAWN, MISSING
	}

	/** Quem apaga objeto: o cadastro de mídia e o worker de remoção que já existem. */
	public interface RetirablePieces {

		/** Pede a remoção das peças. Idempotente por peça. */
		void retire(List<String> pieceIds);
	}

	/**
	 * O documento inteiro. Separado de {@link AnalysedPieces} porque quem pede a checagem de uma
	 * página é o próprio serviço de segurança, e a retirada também: com os dois no mesmo componente,
	 * o render (que depende da checagem) e a checagem (que depende do render) fechariam um ciclo.
	 */
	public interface DocumentPieces {

		/** Pede a remoção do documento e das páginas dele. Idempotente. */
		void retire(String mediaId);

		/** Quando a peça passou a existir, pelo cadastro dela. */
		Instant createdAtOf(String pieceId);

		/**
		 * Documentos que a varredura precisa olhar: as mídias recentes do dono em que ou falta veredito
		 * de peça, ou uma folha já gravou EXPLICIT/SEXUAL_MINOR e a barreira ainda não agiu. Um id por
		 * documento — a resposta é do documento inteiro. Vazio quando o dono não tem nada a rever.
		 */
		List<String> documentsNeedingCheck(String ownerId, Instant since, int limit);

	}

	/** Ledger de resultados: mantém a checagem fora do banco. */
	public interface SafetyLedger {

		Map<String, MediaSafetyCheck> resultsOf(List<String> pieceIds);

		/** Dono da mídia pelo cadastro, mesmo sem resultado gravado ainda. */
		String ownerOf(String mediaId);

		void save(MediaSafetyCheck check);

		/** Peças entregues ao classificador que não gravaram resultado (falha antes de decidir). */
		List<String> findUncheckedPieces(int limit);
	}
}
