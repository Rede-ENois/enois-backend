-- Mentoria: sessão cujo horário terminou é concluída pelo relógio, mas o valor retido no escrow
-- continua com a regra de sempre (confirmação do mentor, cancelamento, ou a liberação automática
-- depois da janela de contestação). settled_at marca quando o dinheiro foi resolvido — pago ao
-- mentor ou devolvido ao mentorado — e null significa "ainda retido". É o que separa "concluída e
-- paga" de "concluída esperando a janela", e o que impede o worker de liberar o mesmo valor duas
-- vezes.
alter table mentor_sessions add column if not exists settled_at datetime(6) null;

-- Sessões encerradas antes desta coluna existir já tinham o valor resolvido no encerramento
-- (a conclusão e o cancelamento sempre moveram o escrow). Marcar agora evita que a liberação
-- automática tente pagar de novo o que já foi pago ou devolvido.
update mentor_sessions set settled_at = now()
 where settled_at is null and status in ('COMPLETED', 'CANCELLED') and price_sparks is not null;