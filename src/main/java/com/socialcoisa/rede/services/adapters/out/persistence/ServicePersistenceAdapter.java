package com.socialcoisa.rede.services.adapters.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.services.application.port.out.ServiceRepository;
import com.socialcoisa.rede.services.domain.ServiceModels.ServiceContract;
import com.socialcoisa.rede.services.domain.ServiceModels.ServiceListing;
import com.socialcoisa.rede.services.domain.ServiceModels.ServiceProposal;
import com.socialcoisa.rede.services.domain.ServiceModels.ServiceRequest;

interface SpringDataServiceListingRepository
		extends org.springframework.data.repository.Repository<ServiceListingJpaEntity, String> {

	ServiceListingJpaEntity save(ServiceListingJpaEntity entity);

	Optional<ServiceListingJpaEntity> findById(String id);

	List<ServiceListingJpaEntity> findByStatusOrderByCreatedAtDesc(String status, Pageable pageable);

	List<ServiceListingJpaEntity> findBySellerIdOrderByCreatedAtDesc(String sellerId);

	@Query("select count(l) from ServiceListingJpaEntity l where l.sellerId = :sellerId and l.status = 'ACTIVE'")
	long countActiveBySeller(String sellerId);
}

interface SpringDataServiceRequestRepository
		extends org.springframework.data.repository.Repository<ServiceRequestJpaEntity, String> {

	ServiceRequestJpaEntity save(ServiceRequestJpaEntity entity);

	Optional<ServiceRequestJpaEntity> findById(String id);

	List<ServiceRequestJpaEntity> findByStatusOrderByCreatedAtDesc(String status, Pageable pageable);

	List<ServiceRequestJpaEntity> findByRequesterIdOrderByCreatedAtDesc(String requesterId);
}

interface SpringDataServiceProposalRepository
		extends org.springframework.data.repository.Repository<ServiceProposalJpaEntity, String> {

	ServiceProposalJpaEntity save(ServiceProposalJpaEntity entity);

	Optional<ServiceProposalJpaEntity> findById(String id);

	List<ServiceProposalJpaEntity> findByRequestIdOrderByCreatedAtAsc(String requestId);
}

interface SpringDataServiceContractRepository
		extends org.springframework.data.repository.Repository<ServiceContractJpaEntity, String> {

	ServiceContractJpaEntity save(ServiceContractJpaEntity entity);

	Optional<ServiceContractJpaEntity> findById(String id);

	List<ServiceContractJpaEntity> findByBuyerIdOrderByCreatedAtDesc(String buyerId);

	List<ServiceContractJpaEntity> findBySellerIdOrderByCreatedAtDesc(String sellerId);

	@Query("select count(c) from ServiceContractJpaEntity c where c.listingId = :listingId and c.escrowState = 'HELD'")
	long countHeldByListing(String listingId);
}

@Repository
@Transactional(propagation = Propagation.REQUIRED)
class ServicePersistenceAdapter implements ServiceRepository {

	private static final int PAGE = 200;

	private final SpringDataServiceListingRepository listings;
	private final SpringDataServiceRequestRepository requests;
	private final SpringDataServiceProposalRepository proposals;
	private final SpringDataServiceContractRepository contracts;

	ServicePersistenceAdapter(SpringDataServiceListingRepository listings,
			SpringDataServiceRequestRepository requests, SpringDataServiceProposalRepository proposals,
			SpringDataServiceContractRepository contracts) {
		this.listings = listings;
		this.requests = requests;
		this.proposals = proposals;
		this.contracts = contracts;
	}

	@Override
	public ServiceListing saveListing(ServiceListing listing) {
		return listings.save(ServiceListingJpaEntity.from(listing)).toDomain();
	}

	@Override
	public Optional<ServiceListing> findListingById(String id) {
		return listings.findById(id).map(ServiceListingJpaEntity::toDomain);
	}

	@Override
	public List<ServiceListing> findActiveListings(int limit) {
		return listings.findByStatusOrderByCreatedAtDesc(ServiceListing.Status.ACTIVE.name(),
			Pageable.ofSize(Math.min(limit, PAGE))).stream().map(ServiceListingJpaEntity::toDomain).toList();
	}

	@Override
	public List<ServiceListing> findListingsBySeller(String sellerId) {
		return listings.findBySellerIdOrderByCreatedAtDesc(sellerId).stream()
			.map(ServiceListingJpaEntity::toDomain).toList();
	}

	@Override
	public long countOpenListingsBySeller(String sellerId) {
		return listings.countActiveBySeller(sellerId);
	}

	@Override
	public ServiceRequest saveRequest(ServiceRequest request) {
		return requests.save(ServiceRequestJpaEntity.from(request)).toDomain();
	}

	@Override
	public Optional<ServiceRequest> findRequestById(String id) {
		return requests.findById(id).map(ServiceRequestJpaEntity::toDomain);
	}

	@Override
	public List<ServiceRequest> findOpenRequests(int limit) {
		return requests.findByStatusOrderByCreatedAtDesc(ServiceRequest.Status.OPEN.name(),
			Pageable.ofSize(Math.min(limit, PAGE))).stream().map(ServiceRequestJpaEntity::toDomain).toList();
	}

	@Override
	public List<ServiceRequest> findRequestsByRequester(String requesterId) {
		return requests.findByRequesterIdOrderByCreatedAtDesc(requesterId).stream()
			.map(ServiceRequestJpaEntity::toDomain).toList();
	}

	@Override
	public ServiceProposal saveProposal(ServiceProposal proposal) {
		return proposals.save(ServiceProposalJpaEntity.from(proposal)).toDomain();
	}

	@Override
	public Optional<ServiceProposal> findProposalById(String id) {
		return proposals.findById(id).map(ServiceProposalJpaEntity::toDomain);
	}

	@Override
	public List<ServiceProposal> findProposalsByRequest(String requestId) {
		return proposals.findByRequestIdOrderByCreatedAtAsc(requestId).stream()
			.map(ServiceProposalJpaEntity::toDomain).toList();
	}

	@Override
	public ServiceContract saveContract(ServiceContract contract) {
		return contracts.save(ServiceContractJpaEntity.from(contract)).toDomain();
	}

	@Override
	public Optional<ServiceContract> findContractById(String id) {
		return contracts.findById(id).map(ServiceContractJpaEntity::toDomain);
	}

	@Override
	public List<ServiceContract> findContractsByBuyer(String buyerId) {
		return contracts.findByBuyerIdOrderByCreatedAtDesc(buyerId).stream()
			.map(ServiceContractJpaEntity::toDomain).toList();
	}

	@Override
	public List<ServiceContract> findContractsBySeller(String sellerId) {
		return contracts.findBySellerIdOrderByCreatedAtDesc(sellerId).stream()
			.map(ServiceContractJpaEntity::toDomain).toList();
	}

	@Override
	public boolean hasOpenContractForListing(String listingId) {
		return contracts.countHeldByListing(listingId) > 0;
	}
}
