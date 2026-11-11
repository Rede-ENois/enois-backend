-- Sessões de mentoria são histórico e sobrevivem ao perfil apagado: a liquidação já devolve o
-- escrow quando o mentor não existe mais e as listas mostram a sessão sem o perfil. A FK impedia
-- apagar o perfil de quem já teve qualquer sessão, então ela sai — mesmo padrão deliberado da V132.
alter table mentor_sessions drop constraint fk_mentor_sessions_mentor;
