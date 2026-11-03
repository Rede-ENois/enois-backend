-- Aparência da vitrine: como o PERFIL se apresenta a quem visita (social e profissional,
-- independentes). Guarda o preset escolhido e os ajustes finos num único documento JSON,
-- separado do tema da conta (preferences) — o tema da conta é a escolha de quem está olhando,
-- este é a escolha de quem é olhado.
--
-- Aditiva e idempotente: a coluna nasce nula, então toda conta existente continua no padrão da
-- plataforma e nada muda para quem já usa o site. `longtext` porque o documento cresce com os
-- ajustes finos e não tem teto útil previsível.
alter table users
  add column if not exists profile_appearance longtext null;
