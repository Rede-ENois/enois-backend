package com.socialcoisa.rede.companies.domain;

import java.time.Instant;

/**
 * Pagina empresarial: identidade propria, NAO e um usuario com outro nome. Dono unico
 * (transferencia segue o padrao de mentoria/comunidade); equipe com papeis vive em
 * {@link CompanyMember}.
 */
public class Company {

	private final String id;
	private final String slug;
	private final String name;
	private final String description;
	private final String sector;
	private final String size;
	private final String location;
	private final String website;
	private final String logo;
	private final String cover;
	private final String ownerUserId;
	private final CompanyStatus status;
	private final Instant createdAt;
	private final Instant updatedAt;

	private Company(String id, String slug, String name, String description, String sector, String size,
			String location, String website, String logo, String cover, String ownerUserId, CompanyStatus status,
			Instant createdAt, Instant updatedAt) {
		this.id = id;
		this.slug = slug;
		this.name = name;
		this.description = description;
		this.sector = sector;
		this.size = size;
		this.location = location;
		this.website = website;
		this.logo = logo;
		this.cover = cover;
		this.ownerUserId = ownerUserId;
		this.status = status;
		this.createdAt = createdAt;
		this.updatedAt = updatedAt;
	}

	public static Company create(String id, String slug, String name, String description, String sector, String size,
			String location, String website, String ownerUserId, Instant now) {
		return new Company(id, slug, name, description, sector, size, location, website, null, null, ownerUserId,
			CompanyStatus.ACTIVE, now, now);
	}

	public static Company restore(String id, String slug, String name, String description, String sector, String size,
			String location, String website, String logo, String cover, String ownerUserId, CompanyStatus status,
			Instant createdAt, Instant updatedAt) {
		return new Company(id, slug, name, description, sector, size, location, website, logo, cover, ownerUserId,
			status, createdAt, updatedAt);
	}

	/** Campos da pagina; campos nulos mantem o valor atual. Logo/capa por upload entram por aqui. */
	public Company update(String name, String description, String sector, String size, String location, String website,
			String logo, String cover, Instant now) {
		return new Company(id, slug,
			org.springframework.util.StringUtils.hasText(name) ? name.trim() : this.name,
			description != null ? description : this.description,
			sector != null ? sector : this.sector,
			size != null ? size : this.size,
			location != null ? location : this.location,
			website != null ? website : this.website,
			logo != null ? logo : this.logo,
			cover != null ? cover : this.cover,
			ownerUserId, status, createdAt, now);
	}

	/** Pagina fechada: sai do diretorio e do perfil; o historico (vagas, projetos) fica ligado. */
	public Company close(Instant now) {
		return new Company(id, slug, name, description, sector, size, location, website, logo, cover,
			ownerUserId, CompanyStatus.CLOSED, createdAt, now);
	}

	public boolean active() {
		return status == CompanyStatus.ACTIVE;
	}

	public Company transferOwnership(String newOwnerId, Instant now) {
		return new Company(id, slug, name, description, sector, size, location, website, logo, cover, newOwnerId,
			status, createdAt, now);
	}

	public boolean ownedBy(String userId) {
		return ownerUserId.equals(userId);
	}

	public String id() { return id; }
	public String slug() { return slug; }
	public String name() { return name; }
	public String description() { return description; }
	public String sector() { return sector; }
	public String size() { return size; }
	public String location() { return location; }
	public String website() { return website; }
	public String logo() { return logo; }
	public String cover() { return cover; }
	public String ownerUserId() { return ownerUserId; }
	public CompanyStatus status() { return status; }
	public Instant createdAt() { return createdAt; }
	public Instant updatedAt() { return updatedAt; }
}
