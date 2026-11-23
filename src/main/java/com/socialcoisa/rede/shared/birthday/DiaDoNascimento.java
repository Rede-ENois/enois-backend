package com.socialcoisa.rede.shared.birthday;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;

/**
 * Quem faz aniversário HOJE (no horário de Brasília) — a tag "Aniversariante" que aparece junto do
 * nome, ao lado da tag de contribuinte.
 *
 * <p>A data vem de uma leitura própria e em lote, nunca do perfil que as telas já têm em mão: o
 * comando de escrita carrega o perfil <b>antes</b> de aplicar a data nova, e decidir por ele dava
 * a resposta de ontem logo depois de a pessoa declarar o nascimento. Uma consulta por página,
 * só devolvendo ids.</p>
 *
 * <p>Só entra quem tem como provar que é aniversário de verdade:</p>
 * <ul>
 *   <li>data de nascimento <b>declarada</b> (conta sem essa data nunca aparece — não há como saber);</li>
 *   <li><b>maior de idade</b> no dia: a tag é do perfil público, como a de contribuinte, e não pode
 *       expor menor sem supervisão;</li>
 *   <li>nascimento em dia/mês <b>diferente</b> do dia de cadastro — o cadastro antigo preenchia a
 *       data de nascimento com a do próprio cadastro, e sem esse descarte essas contas "fariam
 *       aniversário" todo ano na data em que se cadastraram. A prova de cópia é a baixa idade no
 *       cadastro (menos de 13 anos): quem nasceu décadas antes de se cadastrar pode ter nascido no
 *       mesmo dia/mês da data de cadastro e continua aniversariando.</li>
 * </ul>
 *
 * <p>O resto (identidade social, não banido, não bloqueado para quem lê) é decidido por quem chama,
 * porque depende da leitura — o feed já sabe a identidade que assinou e quem está oculto.</p>
 */
public class DiaDoNascimento {

	/** O "hoje" da rede é o de Brasília, como nos jogos diários e na maioridade. */
	public static final ZoneId ZONA = ZoneId.of("America/Sao_Paulo");

	/** Até esta idade no cadastro, a data de nascimento igual à do cadastro é cópia do cadastro. */
	private static final int IDADE_MAXIMA_DA_COPIA = 13;

	private final UserProfileRepository profiles;
	private final Clock clock;

	public DiaDoNascimento(UserProfileRepository profiles, Clock clock) {
		this.profiles = profiles;
		this.clock = clock;
	}

	/** O dia de hoje na rede (Brasília). */
	public LocalDate hoje() {
		return hojeNaZona(clock);
	}

	/** O "hoje" de um relógio, no horário da rede. */
	public static LocalDate hojeNaZona(Clock clock) {
		return LocalDate.now(clock.withZone(ZONA));
	}

	/** Esta conta faz aniversário hoje? (leitura do próprio perfil; já passou pelo banimento). */
	public boolean fazAniversarioHoje(String userId) {
		return userId != null && !userId.isBlank()
			&& profiles.findById(userId).map(profile -> elogiavel(profile, hoje())).orElse(false);
	}

	/** Entre as contas dadas, quem faz aniversário hoje. Nulls e ids vazios são ignorados. */
	public Set<String> aniversariantes(Collection<String> userIds) {
		if (userIds == null || userIds.isEmpty()) {
			return Set.of();
		}
		LocalDate hoje = hoje();
		Set<String> confirmados = new HashSet<>();
		for (UserProfile profile : profiles.findAllByIds(Set.copyOf(userIds))) {
			if (elogiavel(profile, hoje)) {
				confirmados.add(profile.id());
			}
		}
		return confirmados;
	}

	private static boolean elogiavel(UserProfile profile, LocalDate hoje) {
		LocalDate nascimento = profile.birthDate();
		if (nascimento == null || profile.isMinorOn(hoje)) {
			return false;
		}
		if (nascimento.getMonthValue() != hoje.getMonthValue()
				|| nascimento.getDayOfMonth() != hoje.getDayOfMonth()) {
			return false;
		}
		LocalDate cadastro = LocalDate.ofInstant(profile.createdAt(), ZONA);

		boolean mesmaData = nascimento.getMonthValue() == cadastro.getMonthValue()
			&& nascimento.getDayOfMonth() == cadastro.getDayOfMonth();
		return !mesmaData || !nascimento.plusYears(IDADE_MAXIMA_DA_COPIA).isAfter(cadastro);
	}
}
