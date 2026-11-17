package com.socialcoisa.rede.feed.application.port.in;

import java.time.Instant;
import java.util.Optional;

import com.socialcoisa.rede.feed.application.DiarioPage;
import com.socialcoisa.rede.feed.domain.UserId;

/**
 * Diario de atividade do perfil: linha do tempo unificada do que a pessoa publicou e repostou,
 * mais os proprios comentarios quando quem le e o dono (mesma regra de privacidade da aba de
 * respostas). Leitura derivada dos dados que ja existem; nada e gravado para o diario.
 */
public interface GetDiarioUseCase {

	DiarioPage getDiario(UserId viewerId, String username, Optional<Instant> before, int limit);
}
