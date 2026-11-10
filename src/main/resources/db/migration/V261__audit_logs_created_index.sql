-- Indice para a retencao da trilha de auditoria.
--
-- audit_logs so tinha (actor_id, created_at), que serve para "o que fulano fez". A retencao varre
-- por created_at puro (nao por ator) e, sem indice dedicado, o MariaDB lia a tabela inteira: medido
-- em 11.4 com 400 mil linhas, 400.001 leituras + filesort por rodada para achar as 500 mais antigas.
-- Com este indice a mesma consulta vira range: 499 leituras, sem filesort.
--
-- Aditivo e idempotente: nao toca dado nem a tabela existente.
create index if not exists idx_audit_created on audit_logs (created_at);
