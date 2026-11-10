-- Histórico de deploys que publicaram as "Novidades" (O que há de novo) automaticamente.
--
-- O CI de produção chama POST /internal/novidades-deploy depois do health check com o SHA que
-- acabou de entrar no ar; esta tabela é a memória que impede o mesmo deploy de publicar duas
-- vezes (reexecução do workflow, rede tentando de novo): SHA repetido é recusado com 409 e o
-- que está no ar permanece.
--
-- Também responde "qual o último SHA publicado" quando o CI não tem essa informação (primeiro
-- deploy depois da feature, repositório novo): sem ela, não há como o CI montar o changelog
-- entre versões. Não é tabela de configuração: só cresce um pouco por deploy, com o JSON dos
-- itens publicados — e um registro de auditoria do que cada deploy colocou no ar.
--
-- O changelog em si continua na release_note (V225): este é o livro-razão dos deploys.

create table if not exists deploy_publication (
  commit_sha varchar(40) not null,
  items_json longtext not null,
  published_at timestamp not null,
  primary key (commit_sha)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;
