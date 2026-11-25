package com.socialcoisa.rede.portfolio.application.port.out;

import java.util.Set;

/**
 * Quais cards de projeto pertencem ao dono. O portfólio referencia `user_projects` por id, então
 * a referência precisa ser conferida no mesmo instante da gravação — um id de outra pessoa não
 * pode virar peça do portfólio.
 */
public interface PortfolioProjectCatalog {

	Set<String> projectIdsOf(String userId);
}
