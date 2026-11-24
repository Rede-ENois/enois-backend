package com.socialcoisa.rede.talentbank.application;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.talentbank.application.port.out.TalentBankOwnerDirectory;
import com.socialcoisa.rede.talentbank.application.port.out.TalentBankRepository;
import com.socialcoisa.rede.talentbank.domain.TalentBank;

/**
 * As guardas de acesso do banco de talentos.
 *
 * <p>Três decisões que não podem se repetir erradas em rotas diferentes: quem administra a banca é
 * o dono com o modo recrutador ligado; a banca de outra pessoa responde 404 (a mesma de quem não
 * existe, para não revelar a existência por tentativa); e banca não publicada não existe para
 * ninguém fora do dono.</p>
 */
@Component
public class TalentBankAccess {

	private final TalentBankRepository banks;
	private final TalentBankOwnerDirectory owners;

	public TalentBankAccess(TalentBankRepository banks, TalentBankOwnerDirectory owners) {
		this.banks = banks;
		this.owners = owners;
	}

	/** Quem administra a banca precisa estar no modo recrutador. */
	public void requireRecruiter(String ownerId) {
		if (!owners.recruiter(ownerId)) {
			throw new ApiException(403, "RECRUITER_REQUIRED",
				"Ative \"Sou recrutador/a\" no perfil profissional para usar o banco de talentos.");
		}
	}

	/** A banca do dono. A de outra pessoa é 404 — a mesma resposta de quem não existe. */
	public TalentBank requireOwnedBank(String ownerId, String bankId) {
		TalentBank bank = banks.findById(bankId)
			.filter(candidate -> candidate.ownerId().equals(ownerId))
			.orElseThrow(() -> new ApiException(404, "TALENT_BANK_NOT_FOUND", "Banco de talentos não encontrado."));
		requireRecruiter(ownerId);
		return bank;
	}

	/** A banca na vitrine: não publicada é 404 para todo mundo. */
	public TalentBank requireDiscoverable(String bankId) {
		return banks.findById(bankId)
			.filter(TalentBank::discoverable)
			.orElseThrow(() -> new ApiException(404, "TALENT_BANK_NOT_FOUND", "Banco de talentos não encontrado."));
	}

	/** A banca do recrutador — uma por pessoa. */
	public Optional<TalentBank> ownedBank(String ownerId) {
		requireRecruiter(ownerId);
		return banks.findByOwner(ownerId).stream().findFirst();
	}

	/** Os donos de um conjunto de bancas, em uma leitura só. */
	public java.util.Map<String, TalentBankOwnerDirectory.Owner> owners(java.util.Collection<String> ownerIds) {
		return owners.owners(ownerIds);
	}

	/** Os dados de um dono, quando ele existe e ainda é recrutador. */
	public Optional<TalentBankOwnerDirectory.Owner> activeOwner(String ownerId) {
		return owners.owners(List.of(ownerId)).values().stream()
			.filter(TalentBankOwnerDirectory.Owner::recruiter)
			.findFirst();
	}
}
