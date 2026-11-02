-- Mentoria paga em faíscas: o mentor publica o preço de uma sessão (null = não aceita) e cada
-- inscrição guarda o valor retido no escrow, liberado (menos a taxa) quando a sessão conclui.
alter table mentors add column if not exists sparks_price bigint null;

alter table mentor_sessions add column if not exists price_sparks bigint null;
