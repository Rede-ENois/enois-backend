package com.socialcoisa.rede.services.application;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.coin.application.CoinService;
import com.socialcoisa.rede.services.application.port.out.ServiceRepository;
import com.socialcoisa.rede.services.domain.ServiceModels.ServiceContract;
import com.socialcoisa.rede.services.domain.ServiceModels.ServiceListing;
import com.socialcoisa.rede.services.domain.ServiceModels.ServiceProposal;
import com.socialcoisa.rede.services.domain.ServiceModels.ServiceRequest;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Serviços freelance (Fase 5): ofertar (Fiverr) e solicitar (demanda), pagos em
 * faíscas com ESCROW — o contrato nasce HELD (as faíscas retidas), o vendedor entrega
 * e o comprador aceita (libera −10% de taxa) ou recusa (devolve 100%). Antirruído:
 * entregáveis obrigatórios, teto de 5 ofertas abertas por conta.
 */
@Service
public class ServicesService {

	private static final int MAX_OPEN_LISTINGS = 5;

	private final ServiceRepository services;
	private final CoinService coins;
	private final com.socialcoisa.rede.profile.application.port.out.UserProfileRepository profiles;
	private final com.socialcoisa.rede.messaging.application.MessagingService messaging;
	private final com.socialcoisa.rede.shared.security.SuspiciousText suspiciousText;
	private final Clock clock;

	public ServicesService(ServiceRepository services, CoinService coins,
			com.socialcoisa.rede.profile.application.port.out.UserProfileRepository profiles,
			com.socialcoisa.rede.messaging.application.MessagingService messaging,
			com.socialcoisa.rede.shared.security.SuspiciousText suspiciousText, Clock clock) {
		this.services = services;
		this.coins = coins;
		this.profiles = profiles;
		this.messaging = messaging;
		this.suspiciousText = suspiciousText;		this.clock = clock;
	}

	/**
	 * Quem oferece os servicos (nome e foto), uma vez por prestador.
	 *
	 * <p>Uma consulta para a pagina inteira, nao uma por prestador: o catalogo mostra dezenas de
	 * ofertas e cada {@code findById} carregava o perfil completo com as tres colecoes EAGER —
	 * quatro idas ao banco por prestador. A conta que nao existe mais devolve null (a oferta orfa
	 * e removida pela migracao de limpeza).
	 */
	public Map<String, Seller> sellersOf(List<ServiceListing> listings) {
		java.util.Set<String> ids = new java.util.LinkedHashSet<>();
		for (ServiceListing listing : listings) {
			ids.add(listing.sellerId());
		}
		Map<String, com.socialcoisa.rede.profile.application.port.out.SellerIdentity> encontrados =
			profiles.sellersOf(ids).stream()
				.collect(java.util.stream.Collectors.toMap(
					com.socialcoisa.rede.profile.application.port.out.SellerIdentity::id, s -> s));
		Map<String, Seller> sellers = new java.util.LinkedHashMap<>();
		for (String id : ids) {
			var seller = encontrados.get(id);
			sellers.put(id, seller == null
				? new Seller(null, null, null)
				: new Seller(seller.name(), seller.avatar(),
					com.socialcoisa.rede.profile.application.NameStyleView.from(seller)));
		}
		return sellers;
	}

	/** Quem presta o servico: nome e foto (null quando a conta nao existe mais). */
	public record Seller(String name, String avatar,
			com.socialcoisa.rede.profile.application.NameStyleView nameStyle) {
	}

	/**
	 * Abre (ou reaproveita) a conversa de servico entre quem contrata e quem presta.
	 *
	 * <p>É o caminho do serviço cobrado em REAIS: o dinheiro nao entra no ledger de faíscas, então
	 * o jeito de combinar é falar — e a conversa fica com assunto (o tipo do servico e o pedido),
	 * numa aba propria da caixa de entrada do prestador. O serviço pago em faíscas usa o mesmo
	 * caminho (o escrow cuida do dinheiro, a conversa cuida do combinado).
	 *
	 * @return o id da conversa e o assunto
	 */
	@Transactional
	public ServiceChat openChat(String listingId, String hirerId, String pedido) {
		ServiceListing listing = listing(listingId);
		if (listing.status() != ServiceListing.Status.ACTIVE) {
			throw new ApiException(409, "SERVICE_NOT_ACTIVE", "Este serviço não está mais aberto.");
		}
		if (listing.ownedBy(hirerId)) {
			throw new ApiException(400, "OWN_SERVICE", "Não dá para contratar o seu próprio serviço.");
		}
		String assunto = listing.category() + " · " + listing.title();
		String conversationId = messaging.serviceConversationBetween(hirerId, listing.sellerId(), assunto);
		String texto = pedido == null || pedido.isBlank()
			? "Olá! Quero contratar o serviço " + listing.title() + " (" + listing.category() + ")."
			: pedido.strip();
		// A primeira mensagem e o pedido da contratacao: e ela que da o assunto a conversa.
		messaging.sendMessage(conversationId, hirerId, texto);
		return new ServiceChat(conversationId, assunto, listing.id(), listing.sellerId());
	}

	/** A conversa de servico que foi aberta. */
	public record ServiceChat(String conversationId, String assunto, String listingId, String sellerId) {
	}

	// --- D2.2: ofertar (o profissional publica) ---

	@Transactional
	public ServiceListing publishListing(String sellerId, String category, String title, String description,
			String deliverables, long price, String currency, int deliveryDays) {
		if (services.countOpenListingsBySeller(sellerId) >= MAX_OPEN_LISTINGS) {
			throw new ApiException(403, "TOO_MANY_OPEN_SERVICES",
				"Você já tem " + MAX_OPEN_LISTINGS + " serviços abertos — conclua ou remova um antes.");
		}
		Instant now = clock.instant();
		return services.saveListing(ServiceListing.create(sellerId, category, title, description, deliverables, price,
			currency, deliveryDays, now));
	}

	/**
	 * Edita o serviço do próprio prestador. Recusa a mudança de preço quando há contrato aberto:
	 * o comprador contratou o que estava publicado, e as faíscas em escrow são as daquele preço.
	 */
	@Transactional
	public ServiceListing editListing(String listingId, String sellerId, String category, String title,
			String description, String deliverables, long price, String currency, int deliveryDays) {
		ServiceListing listing = ownedListing(listingId, sellerId);
		if (listing.status() != ServiceListing.Status.ACTIVE) {
			throw new ApiException(409, "SERVICE_NOT_ACTIVE", "Este serviço não está mais aberto.");
		}
		if (price != listing.price() && services.hasOpenContractForListing(listingId)) {
			throw new ApiException(409, "SERVICE_HAS_OPEN_CONTRACTS",
				"Há um contrato em andamento com este preço — finalize antes de mudar o serviço.");
		}
		return services.saveListing(listing.withContent(category, title, description, deliverables, price, currency,
			deliveryDays, clock.instant()));
	}

	/**
	 * Remove (despublica) o serviço do próprio prestador. O contrato que já existe continua: ele
	 * guarda a cópia do que foi contratado.
	 */
	@Transactional
	public ServiceListing removeListing(String listingId, String sellerId) {
		ServiceListing listing = ownedListing(listingId, sellerId);
		if (listing.status() == ServiceListing.Status.REMOVED) {
			return listing;
		}
		return services.saveListing(listing.remove(clock.instant()));
	}

	@Transactional
	public ServiceContract buyListing(String listingId, String buyerId) {
		ServiceListing listing = listing(listingId);
		if (listing.status() != ServiceListing.Status.ACTIVE) {
			throw new ApiException(409, "SERVICE_NOT_ACTIVE", "Este serviço não está mais aberto.");
		}
		if (listing.ownedBy(buyerId)) {
			throw new ApiException(400, "OWN_SERVICE", "Não dá para contratar o seu próprio serviço.");
		}
		if ("BRL".equalsIgnoreCase(listing.currency())) {
			// Reais não entram no ledger de faíscas: o pagamento é combinado direto com o profissional.
			throw new ApiException(409, "DIRECT_PAYMENT_REQUIRED",
				"Este serviço é cobrado em reais. Fale com o profissional para combinar o pagamento.");
		}
		Instant now = clock.instant();
		ServiceContract contract = services.saveContract(ServiceContract.fromListing(listing, buyerId, now));
		coins.escrowHold(buyerId, listing.price(), escrowRef(contract), "Contratação de serviço: " + listing.title());
		return contract;
	}

	@Transactional
	public ServiceContract deliver(String contractId, String sellerId, String note) {
		ServiceContract contract = contract(contractId);
		if (!contract.ownedBySeller(sellerId)) {
			throw new ApiException(403, "NOT_THE_SELLER", "Só quem executa entrega o serviço.");
		}
		return services.saveContract(contract.deliver(note, clock.instant()));
	}

	/** O comprador aceita a entrega: libera o escrow para o vendedor (−10% de taxa). */
	@Transactional
	public ServiceContract accept(String contractId, String buyerId) {
		ServiceContract contract = contract(contractId);
		if (!contract.ownedByBuyer(buyerId)) {
			throw new ApiException(403, "NOT_THE_BUYER", "Só quem contratou aceita a entrega.");
		}
		Instant now = clock.instant();
		coins.escrowRelease(contract.sellerId(), contract.price(), escrowRef(contract),
			"Serviço concluído (escrow liberado)");
		return services.saveContract(contract.release(now));
	}

	/** O comprador recusa: devolve 100%. */
	@Transactional
	public ServiceContract refund(String contractId, String buyerId) {
		ServiceContract contract = contract(contractId);
		if (!contract.ownedByBuyer(buyerId)) {
			throw new ApiException(403, "NOT_THE_BUYER", "Só quem contratou recusa a entrega.");
		}
		Instant now = clock.instant();
		coins.escrowRefund(contract.buyerId(), contract.price(), escrowRef(contract),
			"Reembolso de serviço");
		return services.saveContract(contract.refund(now));
	}

	// --- D2.1: solicitar (a demanda publica) ---

	@Transactional
	public ServiceRequest createRequest(String requesterId, String category, String title, String description,
			String deliverables, long budgetMin, long budgetMax, Instant deadline) {
		Instant now = clock.instant();
		return services.saveRequest(ServiceRequest.create(requesterId, category, title, description, deliverables,
			budgetMin, budgetMax, deadline, now));
	}

	@Transactional
	public ServiceProposal propose(String requestId, String userId, String message, long price) {
		ServiceRequest request = request(requestId);
		if (request.status() != ServiceRequest.Status.OPEN) {
			throw new ApiException(409, "REQUEST_NOT_OPEN", "Este pedido não está mais aberto a propostas.");
		}
		if (request.ownedBy(userId)) {
			throw new ApiException(400, "OWN_REQUEST", "Não dá para propor no seu próprio pedido.");
		}
		if (price < request.budgetMin() || price > request.budgetMax()) {
			throw new ApiException(400, "PRICE_OUT_OF_BUDGET",
				"O preço da proposta precisa estar dentro da faixa (" + request.budgetMin() + "–"
					+ request.budgetMax() + ").");
		}
		return services.saveProposal(ServiceProposal.create(requestId, userId, message, price, clock.instant()));
	}

	/** O requester escolhe uma proposta: o escrow nasce retido e o pedido fecha. */
	@Transactional
	public ServiceContract choose(String requestId, String requesterId, String proposalId) {
		ServiceRequest request = request(requestId);
		if (!request.ownedBy(requesterId)) {
			throw new ApiException(403, "NOT_THE_REQUESTER", "Só quem pediu escolhe a proposta.");
		}
		if (request.status() != ServiceRequest.Status.OPEN) {
			throw new ApiException(409, "REQUEST_NOT_OPEN", "Este pedido já fechou.");
		}
		ServiceProposal proposal = services.findProposalById(proposalId)
			.orElseThrow(() -> new ApiException(404, "PROPOSAL_NOT_FOUND", "Proposta não encontrada."));
		if (!proposal.requestId().equals(requestId)) {
			throw new ApiException(400, "PROPOSAL_MISMATCH", "Essa proposta não é deste pedido.");
		}
		Instant now = clock.instant();
		ServiceContract contract = services.saveContract(ServiceContract.fromRequest(request, proposal, requesterId, now));
		coins.escrowHold(requesterId, proposal.price(), escrowRef(contract), "Serviço contratado: " + request.title());
		services.saveProposal(new ServiceProposal(proposal.id(), proposal.requestId(), proposal.userId(),
			proposal.message(), proposal.price(), ServiceProposal.Status.CHOSEN, proposal.createdAt()));
		services.saveRequest(new ServiceRequest(request.id(), request.requesterId(), request.category(), request.title(),
			request.description(), request.deliverables(), request.budgetMin(), request.budgetMax(), request.deadline(),
			ServiceRequest.Status.CHOSEN, request.createdAt(), now));
		return contract;
	}

	public List<ServiceListing> listingCatalog(int limit, String query, String category) {
		return services.findActiveListings(Math.min(200, Math.max(1, limit) * 4)).stream()
			// Conteúdo com o padrão do ataque não aparece no catálogo público.
			.filter(l -> !suspiciousText.matches(l.title(), l.description(), l.deliverables()))
			.filter(l -> matches(query, l.title(), l.description(), l.deliverables()))
			.filter(l -> matchesCategory(category, l.category()))
			.limit(Math.min(60, Math.max(1, limit))).toList();
	}

	public List<ServiceListing> listingsBySeller(String sellerId) {
		return services.findListingsBySeller(sellerId);
	}

	public List<ServiceRequest> requestCatalog(int limit, String query, String category) {
		return services.findOpenRequests(Math.min(200, Math.max(1, limit) * 4)).stream()
			.filter(r -> !suspiciousText.matches(r.title(), r.description(), r.deliverables()))
			.filter(r -> matches(query, r.title(), r.description(), r.deliverables()))
			.filter(r -> matchesCategory(category, r.category()))
			.limit(Math.min(60, Math.max(1, limit))).toList();
	}

	private static boolean matches(String query, String... fields) {
		if (query == null || query.isBlank()) {
			return true;
		}
		String term = query.trim().toLowerCase(java.util.Locale.ROOT);
		for (String field : fields) {
			if (field != null && field.toLowerCase(java.util.Locale.ROOT).contains(term)) {
				return true;
			}
		}
		return false;
	}

	private static boolean matchesCategory(String category, String value) {
		if (category == null || category.isBlank()) {
			return true;
		}
		return value != null && value.equalsIgnoreCase(category.trim());
	}

	public List<ServiceProposal> proposalsFor(String requestId, String requesterId) {
		ServiceRequest request = request(requestId);
		if (!request.ownedBy(requesterId)) {
			throw new ApiException(403, "NOT_THE_REQUESTER", "Só quem pediu vê as propostas.");
		}
		return services.findProposalsByRequest(requestId);
	}

	public List<ServiceContract> contractsByBuyer(String buyerId) {
		return services.findContractsByBuyer(buyerId);
	}

	public List<ServiceContract> contractsBySeller(String sellerId) {
		return services.findContractsBySeller(sellerId);
	}

	private ServiceListing listing(String id) {
		return services.findListingById(id)
			.orElseThrow(() -> new ApiException(404, "SERVICE_NOT_FOUND", "Serviço não encontrado."));
	}

	/** O serviço, exigindo que quem pede seja quem publica: editar e remover são do dono. */
	private ServiceListing ownedListing(String id, String sellerId) {
		ServiceListing listing = listing(id);
		if (!listing.ownedBy(sellerId)) {
			throw new ApiException(403, "NOT_THE_SELLER", "Só quem publica o serviço pode alterá-lo.");
		}
		return listing;
	}

	private ServiceRequest request(String id) {
		return services.findRequestById(id)
			.orElseThrow(() -> new ApiException(404, "REQUEST_NOT_FOUND", "Pedido não encontrado."));
	}

	private ServiceContract contract(String id) {
		return services.findContractById(id)
			.orElseThrow(() -> new ApiException(404, "CONTRACT_NOT_FOUND", "Contrato não encontrado."));
	}

	private String escrowRef(ServiceContract contract) {
		return "svc:" + contract.id();
	}

}
