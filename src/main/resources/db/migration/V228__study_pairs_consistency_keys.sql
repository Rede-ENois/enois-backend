-- Study pairs: as duas invariantes que o aplicativo sozinho nao conseguia garantir sob
-- concorrencia, agora no banco.
--
-- 1) Uma solicitacao pendente por pessoa. `open_user_id` vale o dono enquanto o pedido esta
--    OPEN e fica NULL quando ele e casado/cancelado; o indice unico sobre uma coluna anulavel
--    faz o papel de indice parcial (que o MariaDB nao tem) e o H2 dos testes aceita.
-- 2) No maximo um par ATIVO entre a mesma dupla. `active_pair_key` e "menor:maior" dos dois
--    ids enquanto o par esta ACTIVE e NULL depois de encerrado.
--
-- Antes de criar os indices, saneia o que a aplicacao sem trava poderia ter deixado para tras:
-- pedidos OPEN repetidos da mesma pessoa (fica o mais antigo) e pares ATIVOS repetidos da mesma
-- dupla (fica o mais antigo, os demais sao encerrados). Ambas as limpezas sao idempotentes.
alter table study_pair_requests add column if not exists open_user_id
  varchar(64) character set utf8mb4 collate utf8mb4_uca1400_ai_ci null;

update study_pair_requests r
  set r.status = 'CANCELLED'
  where r.status = 'OPEN'
    and r.id not in (
      select keep_id from (
        select min(id) as keep_id
          from study_pair_requests
          where status = 'OPEN'
          group by user_id
      ) kept
    );

update study_pair_requests set open_user_id = case when status = 'OPEN' then user_id else null end;

create unique index if not exists uk_study_pair_requests_open_user
  on study_pair_requests (open_user_id);

alter table study_pairs add column if not exists active_pair_key
  varchar(145) character set utf8mb4 collate utf8mb4_uca1400_ai_ci null;

update study_pairs p
  set p.status = 'ENDED', p.ended_at = coalesce(p.ended_at, p.started_at)
  where p.status = 'ACTIVE'
    and p.id not in (
      select keep_id from (
        select min(id) as keep_id
          from study_pairs
          where status = 'ACTIVE'
          group by least(user_a, user_b), greatest(user_a, user_b)
      ) kept
    );

update study_pairs
  set active_pair_key = case
      when status = 'ACTIVE' then concat(least(user_a, user_b), ':', greatest(user_a, user_b))
      else null
    end;

create unique index if not exists uk_study_pairs_active_pair on study_pairs (active_pair_key);