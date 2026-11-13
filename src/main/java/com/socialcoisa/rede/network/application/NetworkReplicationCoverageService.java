package com.socialcoisa.rede.network.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.network.application.port.out.ContentVersionRepository;
import com.socialcoisa.rede.network.application.port.out.NetworkRegionRepository;
import com.socialcoisa.rede.network.domain.ContentKind;

/**
 * Cobertura geográfica da replicação (fase 6): quantas regiões distintas têm, agora, uma cópia
 * utilizável de cada tipo de conteúdo público.
 *
 * <p>Até esta rodada a replicação era medida <b>por nó</b>: cada nó tinha versão e hash, e o painel
 * listava divergências. Isso respondia "o nó X está em dia?", nunca "a rede sobrevive à queda de uma
 * região?". Doze nós de borda no mesmo rack são doze cópias e uma região só — a conta por nó chama
 * isso de redundância total. A pergunta da contingência ({@code 06-contingencia.md} §3) é a de
 * região, e ela não existia em lugar nenhum.</p>
 *
 * <p>A conta olha apenas nó <b>operável</b>: {@code ACTIVE}, com heartbeat recente e de região que
 * não foi declarada fora de operação. Cópia de nó parado, de nó em quarentena de heartbeat ou de nó
 * que a rede tirou de operação não cobre região nenhuma — contá-la faria o painel prometer
 * contingência que não existe. O universo ({@code totalRegions}) usa o mesmo corte, para que
 * "3 de 4 regiões" seja comparável: região só de nós mortos não é região atendida nem região
 * disponível.</p>
 *
 * <p>É leitura de painel, não decisão de despacho: nada aqui entra em {@code choose()} nem na conta
 * de capacidade. Assim como o dashboard, não muda comportamento — só responde.</p>
 */
@Service
public class NetworkReplicationCoverageService {

	/** Mesmo corte de heartbeat do scheduler e do painel: "vivo" é o mesmo vivo que recebe tarefa. */
	private static final long ALIVE_SECONDS = 180;

	private final ContentVersionRepository versions;
	private final NetworkRegionRepository regions;
	private final Clock clock;

	NetworkReplicationCoverageService(ContentVersionRepository versions, NetworkRegionRepository regions,
			Clock clock) {
		this.versions = versions;
		this.regions = regions;
		this.clock = clock;
	}

	/**
	 * Uma linha por tipo de conteúdo. O tipo sem cópia nenhuma aparece com zero — é justamente o que
	 * o painel precisa mostrar, e omitir a linha esconderia o buraco.
	 */
	@Transactional(readOnly = true)
	public List<ContentVersionRepository.RegionCoverage> coverage() {
		Instant aliveSince = clock.instant().minus(Duration.ofSeconds(ALIVE_SECONDS));
		return versions.coverage(regions.outOfService(), aliveSince);
	}

	/** A cobertura de um tipo, para o painel perguntar por um só. */
	@Transactional(readOnly = true)
	public ContentVersionRepository.RegionCoverage coverage(ContentKind kind) {
		return coverage().stream()
			.filter(row -> row.kind() == kind)
			.findFirst()
			.orElseGet(() -> new ContentVersionRepository.RegionCoverage(kind, 0, 0, 0));
	}
}