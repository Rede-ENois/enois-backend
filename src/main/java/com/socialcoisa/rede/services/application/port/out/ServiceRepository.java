package com.socialcoisa.rede.services.application.port.out;

import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.services.domain.ServiceModels.ServiceContract;
import com.socialcoisa.rede.services.domain.ServiceModels.ServiceListing;
import com.socialcoisa.rede.services.domain.ServiceModels.ServiceProposal;
import com.socialcoisa.rede.services.domain.ServiceModels.ServiceRequest;

public interface ServiceRepository {

	ServiceListing saveListing(ServiceListing listing);

	Optional<ServiceListing> findListingById(String id);

	List<ServiceListing> findActiveListings(int limit);

	List<ServiceListing> findListingsBySeller(String sellerId);

	long countOpenListingsBySeller(String sellerId);

	ServiceRequest saveRequest(ServiceRequest request);

	Optional<ServiceRequest> findRequestById(String id);

	List<ServiceRequest> findOpenRequests(int limit);

	List<ServiceRequest> findRequestsByRequester(String requesterId);

	ServiceProposal saveProposal(ServiceProposal proposal);

	Optional<ServiceProposal> findProposalById(String id);

	List<ServiceProposal> findProposalsByRequest(String requestId);

	ServiceContract saveContract(ServiceContract contract);

	Optional<ServiceContract> findContractById(String id);

	List<ServiceContract> findContractsByBuyer(String buyerId);

	List<ServiceContract> findContractsBySeller(String sellerId);

	/** Há contrato com as faíscas ainda retidas para este serviço (o preço está em uso). */
	boolean hasOpenContractForListing(String listingId);
}
