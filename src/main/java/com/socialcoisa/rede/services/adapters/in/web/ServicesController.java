package com.socialcoisa.rede.services.adapters.in.web;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.services.application.ServicesService;
import com.socialcoisa.rede.services.domain.ServiceModels.ServiceContract;
import com.socialcoisa.rede.services.domain.ServiceModels.ServiceListing;
import com.socialcoisa.rede.services.domain.ServiceModels.ServiceProposal;
import com.socialcoisa.rede.services.domain.ServiceModels.ServiceRequest;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * Serviços freelance (Fase 5): /servicos (catálogo de ofertas e pedidos abertos),
 * /servicos/ofertas (publicar/contratar), /servicos/pedidos (publicar, propostas,
 * escolher) e /servicos/contratos (o painel do escrow: entregar, aceitar, reembolso).
 */
@RestController
class ServicesController {

	private final ServicesService services;
	private final CurrentUserResolver currentUserResolver;

	ServicesController(ServicesService services, CurrentUserResolver currentUserResolver) {
		this.services = services;
		this.currentUserResolver = currentUserResolver;
	}

	@GetMapping("/servicos")
	Map<String, Object> catalog(@RequestParam(defaultValue = "40") int listings,
			@RequestParam(defaultValue = "40") int requests,
			@RequestParam(required = false) String q,
			@RequestParam(required = false) String category) {
		var ofertas = services.listingCatalog(listings, q, category);
		var prestadores = services.sellersOf(ofertas);
		return Map.of(
			"ofertas", ofertas.stream()
				.map(listing -> ListingView.from(listing, prestadores.get(listing.sellerId())))
				.toList(),
			"pedidos", services.requestCatalog(requests, q, category).stream().map(RequestView::from).toList());
	}

	@GetMapping("/servicos/minhas")
	Map<String, Object> mine(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = user(sessionToken);
		var ofertas = services.listingsBySeller(user.id().value());
		var prestadores = services.sellersOf(ofertas);
		return Map.of(
			"ofertas", ofertas.stream()
				.map(listing -> ListingView.from(listing, prestadores.get(listing.sellerId())))
				.toList(),
			"contratosComoComprador", services.contractsByBuyer(user.id().value()).stream().map(ContractView::from).toList(),
			"contratosComoVendedor", services.contractsBySeller(user.id().value()).stream().map(ContractView::from).toList());
	}

	@PostMapping("/servicos/ofertas")
	Map<String, Object> publishListing(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestBody PublishListingRequest request) {
		CurrentUser user = user(sessionToken);
		ServiceListing listing = services.publishListing(user.id().value(), request.category(), request.title(),
			request.description(), request.deliverables(), request.price(), request.currency(),
			request.deliveryDays());
		return Map.of("id", listing.id(), "status", listing.status().name());
	}

	/** O prestador edita o próprio serviço (o preço trava com contrato aberto). */
	@PutMapping("/servicos/ofertas/{listingId}")
	Map<String, Object> editListing(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String listingId, @RequestBody PublishListingRequest request) {
		CurrentUser user = user(sessionToken);
		ServiceListing listing = services.editListing(listingId, user.id().value(), request.category(),
			request.title(), request.description(), request.deliverables(), request.price(), request.currency(),
			request.deliveryDays());
		return Map.of("id", listing.id(), "status", listing.status().name());
	}

	/** O prestador despublica o próprio serviço; os contratos já abertos continuam valendo. */
	@DeleteMapping("/servicos/ofertas/{listingId}")
	Map<String, Object> removeListing(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String listingId) {
		CurrentUser user = user(sessionToken);
		ServiceListing listing = services.removeListing(listingId, user.id().value());
		return Map.of("id", listing.id(), "status", listing.status().name());
	}

	@PostMapping("/servicos/ofertas/{listingId}/contratar")
	Map<String, Object> buyListing(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String listingId) {
		CurrentUser user = user(sessionToken);
		ServiceContract contract = services.buyListing(listingId, user.id().value());
		return Map.of("contractId", contract.id(), "state", contract.escrowState().name());
	}

	/**
	 * Abre a conversa de servico com quem presta (o botao do card).
	 *
	 * <p>É o caminho do servico em REAIS: o pagamento e combinado direto com o prestador, e a
	 * conversa fica com assunto (o tipo do servico e o pedido) na aba "Servicos" da caixa de
	 * entrada dele. Em FAISCA o mesmo caminho existe — o escrow cuida do dinheiro.
	 */
	@PostMapping("/servicos/ofertas/{listingId}/chat")
	Map<String, Object> openChat(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String listingId,
			@RequestBody(required = false) OpenChatRequest request) {
		CurrentUser user = user(sessionToken);
		var chat = services.openChat(listingId, user.id().value(), request == null ? null : request.message());
		return Map.of("conversationId", chat.conversationId(), "assunto", chat.assunto(),
			"listingId", chat.listingId(), "sellerId", chat.sellerId());
	}

	@PostMapping("/servicos/pedidos")
	Map<String, Object> createRequest(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestBody CreateRequestRequest request) {
		CurrentUser user = user(sessionToken);
		ServiceRequest created = services.createRequest(user.id().value(), request.category(), request.title(),
			request.description(), request.deliverables(), request.budgetMin(), request.budgetMax(),
			request.deadline());
		return Map.of("id", created.id(), "status", created.status().name());
	}

	@PostMapping("/servicos/pedidos/{requestId}/propostas")
	Map<String, Object> propose(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String requestId, @RequestBody ProposeRequest request) {
		CurrentUser user = user(sessionToken);
		ServiceProposal proposal = services.propose(requestId, user.id().value(), request.message(), request.price());
		return Map.of("id", proposal.id(), "status", proposal.status().name());
	}

	@GetMapping("/servicos/pedidos/{requestId}/propostas")
	List<ProposalView> proposals(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String requestId) {
		CurrentUser user = user(sessionToken);
		return services.proposalsFor(requestId, user.id().value()).stream().map(ProposalView::from).toList();
	}

	@PostMapping("/servicos/pedidos/{requestId}/escolher")
	Map<String, Object> choose(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String requestId, @RequestBody ChooseRequest request) {
		CurrentUser user = user(sessionToken);
		ServiceContract contract = services.choose(requestId, user.id().value(), request.proposalId());
		return Map.of("contractId", contract.id(), "state", contract.escrowState().name());
	}

	@PostMapping("/servicos/contratos/{contractId}/entregar")
	Map<String, Object> deliver(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String contractId, @RequestBody DeliverRequest request) {
		CurrentUser user = user(sessionToken);
		ServiceContract contract = services.deliver(contractId, user.id().value(), request.note());
		return Map.of("contractId", contract.id(), "state", contract.escrowState().name());
	}

	@PostMapping("/servicos/contratos/{contractId}/aceitar")
	Map<String, Object> accept(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String contractId) {
		CurrentUser user = user(sessionToken);
		ServiceContract contract = services.accept(contractId, user.id().value());
		return Map.of("contractId", contract.id(), "state", contract.escrowState().name());
	}

	@PostMapping("/servicos/contratos/{contractId}/reembolso")
	Map<String, Object> refund(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String contractId) {
		CurrentUser user = user(sessionToken);
		ServiceContract contract = services.refund(contractId, user.id().value());
		return Map.of("contractId", contract.id(), "state", contract.escrowState().name());
	}

	private CurrentUser user(String sessionToken) {
		return currentUserResolver.resolve(sessionToken);
	}

	record PublishListingRequest(String category, String title, String description, String deliverables, long price,
			String currency,
			int deliveryDays) {
	}

	record CreateRequestRequest(String category, String title, String description, String deliverables,
			long budgetMin, long budgetMax, java.time.Instant deadline) {
	}

	record ProposeRequest(String message, long price) {
	}

	record ChooseRequest(String proposalId) {
	}

	record DeliverRequest(String note) {
	}

	/**
	 * A oferta com QUEM vai prestar o serviço (nome e foto): um serviço sem rosto nao diz para
	 * quem a pessoa esta contratando.
	 */
	record ListingView(String id, String sellerId, String sellerName, String sellerAvatar,
			com.socialcoisa.rede.profile.application.NameStyleView sellerStyle, String category, String title,
			String description, String deliverables, long price, String currency, int deliveryDays, String status,
			String createdAt) {
		static ListingView from(ServiceListing l, ServicesService.Seller seller) {
			return new ListingView(l.id(), l.sellerId(), seller == null ? null : seller.name(),
				seller == null ? null : seller.avatar(), seller == null ? null : seller.nameStyle(),
				l.category(), l.title(), l.description(),
				l.deliverables(), l.price(), l.currency(), l.deliveryDays(), l.status().name(),
				l.createdAt().toString());
		}
	}

	record RequestView(String id, String requesterId, String category, String title, String description,
			String deliverables, long budgetMin, long budgetMax, String deadline, String status, String createdAt) {
		static RequestView from(ServiceRequest r) {
			return new RequestView(r.id(), r.requesterId(), r.category(), r.title(), r.description(), r.deliverables(),
				r.budgetMin(), r.budgetMax(), r.deadline() == null ? null : r.deadline().toString(),
				r.status().name(), r.createdAt().toString());
		}
	}

	/** O pedido que vai na primeira mensagem da conversa de servico. */
	record OpenChatRequest(String message) {
	}

	record ProposalView(String id, String requestId, String userId, String message, long price, String status,
			String createdAt) {
		static ProposalView from(ServiceProposal p) {
			return new ProposalView(p.id(), p.requestId(), p.userId(), p.message(), p.price(), p.status().name(),
				p.createdAt().toString());
		}
	}

	record ContractView(String id, String listingId, String requestId, String buyerId, String sellerId, long price,
			String escrowState, String deliverableNote, String createdAt, String acceptedAt) {
		static ContractView from(ServiceContract c) {
			return new ContractView(c.id(), c.listingId(), c.requestId(), c.buyerId(), c.sellerId(), c.price(),
				c.escrowState().name(), c.deliverableNote(), c.createdAt().toString(),
				c.acceptedAt() == null ? null : c.acceptedAt().toString());
		}
	}
}
