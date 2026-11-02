package com.socialcoisa.rede.services.domain;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import org.springframework.util.StringUtils;

import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.TextFieldLimit;

/**
 * Os pedaços do marketplace de serviços (Fase 5): a OFERTA (o profissional publica o
 * que faz, modelo Fiverr), o PEDIDO (a demanda publica o que precisa, com orçamento em
 * faixa) e o CONTRATO (o escrow — as faíscas ficam retidas até o aceite). Entregáveis
 * são obrigatórios: o contrato de escopo existe antes de qualquer faísca mover.
 */
public final class ServiceModels {

	private ServiceModels() {
	}

	public record ServiceListing(String id, String sellerId, String category, String title, String description,
			String deliverables, long price, String currency, int deliveryDays, Status status, Instant createdAt,
			Instant updatedAt) {

		public enum Status {
			ACTIVE, REMOVED
		}

		public static ServiceListing create(String sellerId, String category, String title, String description,
				String deliverables, long price, String currency, int deliveryDays, Instant now) {
			validateCommon(category, title, description, deliverables, price);
			String moeda = currency == null ? "FAISCA" : currency.trim().toUpperCase(Locale.ROOT);
			if (!moeda.equals("FAISCA") && !moeda.equals("BRL")) {
				throw new ApiException(400, "INVALID_CURRENCY", "Moeda precisa ser FAISCA ou BRL.");
			}
			if (deliveryDays <= 0 || deliveryDays > 365) {
				throw new ApiException(400, "INVALID_DELIVERY", "O prazo de entrega precisa de 1 a 365 dias.");
			}
			return new ServiceListing("sl_" + UUID.randomUUID().toString().replace("-", ""), sellerId, category.trim(),
				title.trim(), description.trim(), deliverables.trim(), price, moeda, deliveryDays, Status.ACTIVE,
				now, now);
		}

		public boolean ownedBy(String accountId) {
			return sellerId != null && sellerId.equals(accountId);
		}

		public ServiceListing remove(Instant now) {
			return new ServiceListing(id, sellerId, category, title, description, deliverables, price, currency,
				deliveryDays, Status.REMOVED, createdAt, now);
		}

		/**
		 * O mesmo serviço com o conteúdo novo. Reaproveita a validação da criação: sem ela, a
		 * edição era o caminho para gravar texto acima do teto da coluna ou moeda inválida.
		 */
		public ServiceListing withContent(String category, String title, String description, String deliverables,
				long price, String currency, int deliveryDays, Instant now) {
			ServiceListing edited = create(sellerId, category, title, description, deliverables, price, currency,
				deliveryDays, now);
			return new ServiceListing(id, sellerId, edited.category(), edited.title(), edited.description(),
				edited.deliverables(), edited.price(), edited.currency(), edited.deliveryDays(), status, createdAt, now);
		}
	}

	public record ServiceRequest(String id, String requesterId, String category, String title, String description,
			String deliverables, long budgetMin, long budgetMax, Instant deadline, Status status, Instant createdAt,
			Instant updatedAt) {

		public enum Status {
			OPEN, CHOSEN, CLOSED
		}

		public static ServiceRequest create(String requesterId, String category, String title, String description,
				String deliverables, long budgetMin, long budgetMax, Instant deadline, Instant now) {
			validateCommon(category, title, description, deliverables, Math.max(budgetMin, budgetMax));
			if (budgetMin <= 0 || budgetMax < budgetMin) {
				throw new ApiException(400, "INVALID_BUDGET",
					"O orçamento precisa ser uma faixa válida (mínimo ≤ máximo, ambos positivos).");
			}
			return new ServiceRequest("sr_" + UUID.randomUUID().toString().replace("-", ""), requesterId, category.trim(),
				title.trim(), description.trim(), deliverables.trim(), budgetMin, budgetMax, deadline, Status.OPEN,
				now, now);
		}

		public boolean ownedBy(String accountId) {
			return requesterId != null && requesterId.equals(accountId);
		}
	}

	public record ServiceProposal(String id, String requestId, String userId, String message, long price,
			Status status, Instant createdAt) {

		public enum Status {
			SENT, CHOSEN, DECLINED
		}

		public static ServiceProposal create(String requestId, String userId, String message, long price, Instant now) {
			if (!StringUtils.hasText(message) || message.trim().length() < 10) {
				throw new ApiException(400, "PROPOSAL_MESSAGE_REQUIRED",
					"A proposta precisa de uma mensagem (mínimo 10 caracteres).");
			}
			if (price <= 0) {
				throw new ApiException(400, "INVALID_PRICE", "O preço da proposta precisa ser positivo.");
			}
			return new ServiceProposal("sp_" + UUID.randomUUID().toString().replace("-", ""), requestId, userId,
				message.trim(), price, Status.SENT, now);
		}
	}

	public record ServiceContract(String id, String listingId, String requestId, String buyerId, String sellerId,
			long price, EscrowState escrowState, String deliverableNote, Instant createdAt, Instant acceptedAt) {

		public enum EscrowState {
			HELD, RELEASED, REFUNDED
		}

		public static ServiceContract fromListing(ServiceListing listing, String buyerId, Instant now) {
			return new ServiceContract("sc_" + UUID.randomUUID().toString().replace("-", ""), listing.id(), null,
				buyerId, listing.sellerId(), listing.price(), EscrowState.HELD, null, now, null);
		}

		public static ServiceContract fromRequest(ServiceRequest request, ServiceProposal proposal, String buyerId,
				Instant now) {
			return new ServiceContract("sc_" + UUID.randomUUID().toString().replace("-", ""), null, request.id(),
				buyerId, proposal.userId(), proposal.price(), EscrowState.HELD, null, now, null);
		}

		public ServiceContract deliver(String note, Instant now) {
			if (escrowState != EscrowState.HELD) {
				throw new ApiException(409, "CONTRACT_NOT_HELD", "Este contrato já foi decidido.");
			}
			if (!StringUtils.hasText(note)) {
				throw new ApiException(400, "DELIVERABLE_REQUIRED", "A entrega precisa do que foi feito.");
			}
			return new ServiceContract(id, listingId, requestId, buyerId, sellerId, price, EscrowState.HELD, note.trim(),
				createdAt, acceptedAt);
		}

		public ServiceContract release(Instant now) {
			if (escrowState != EscrowState.HELD) {
				throw new ApiException(409, "CONTRACT_NOT_HELD", "Este contrato já foi decidido.");
			}
			return new ServiceContract(id, listingId, requestId, buyerId, sellerId, price, EscrowState.RELEASED,
				deliverableNote, createdAt, now);
		}

		public ServiceContract refund(Instant now) {
			if (escrowState != EscrowState.HELD) {
				throw new ApiException(409, "CONTRACT_NOT_HELD", "Este contrato já foi decidido.");
			}
			return new ServiceContract(id, listingId, requestId, buyerId, sellerId, price, EscrowState.REFUNDED,
				deliverableNote, createdAt, now);
		}

		public boolean ownedByBuyer(String accountId) {
			return buyerId != null && buyerId.equals(accountId);
		}

		public boolean ownedBySeller(String accountId) {
			return sellerId != null && sellerId.equals(accountId);
		}
	}

	private static void validateCommon(String category, String title, String description, String deliverables,
			long price) {
		if (!StringUtils.hasText(category)) {
			throw new ApiException(400, "CATEGORY_REQUIRED", "A categoria é obrigatória.");
		}
		if (!StringUtils.hasText(title) || title.trim().length() < 5) {
			throw new ApiException(400, "TITLE_REQUIRED", "O título precisa de pelo menos 5 caracteres.");
		}
		if (!StringUtils.hasText(description) || description.trim().length() < 20) {
			throw new ApiException(400, "DESCRIPTION_REQUIRED",
				"A descrição precisa de pelo menos 20 caracteres.");
		}
		if (!StringUtils.hasText(deliverables)) {
			throw new ApiException(400, "DELIVERABLES_REQUIRED",
				"Os entregáveis são obrigatórios — o contrato de escopo existe antes de começar.");
		}
		if (price <= 0) {
			throw new ApiException(400, "INVALID_PRICE", "O preço precisa ser positivo.");
		}
		// Os tetos sao os das COLUNAS (`service_listings.title varchar(160)`, `description
		// varchar(2000)`, `deliverables varchar(1000)`, `category varchar(40)`): sem esta checagem o
		// banco recusava a gravacao com "Data too long" e a pessoa levava 409 "conflita com o estado
		// atual dos dados", sem saber qual campo era.
		TextFieldLimit.check("SERVICE_FIELD_TOO_LONG", "categoria", category, 40);
		TextFieldLimit.check("SERVICE_FIELD_TOO_LONG", "titulo", title, 160);
		TextFieldLimit.check("SERVICE_FIELD_TOO_LONG", "descricao", description, 2000);
		TextFieldLimit.check("SERVICE_FIELD_TOO_LONG", "entregaveis", deliverables, 1000);
	}
}
