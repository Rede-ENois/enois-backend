-- Termômetro: a fatia passa a conhecer a publicação que a gerou.
--
-- Até aqui a fatia guardava só (bucket da hora, fonte, emoção, autor, momento da leitura): a
-- mesma publicação relida — recomeço do horizonte depois de 31 dias, ou qualquer nova leitura —
-- virava OUTRA linha, e o corte somava a manifestação de novo. Sem chave natural, também não
-- havia como saber se a publicação apagada deixou um fantasma na janela.
--
-- publicacao_id guarda o id da publicação de origem (post, comentário, desabafo, confissão).
-- A coluna nasce nula para as linhas antigas: a leitura seguinte adota a linha pelo bucket (mesma
-- fonte, autor e emoção na mesma hora) e grava o id a partir daí. A adoção não apaga nada — no
-- pior caso a linha antiga fica sem id até ser reconhecida, e nunca conta duas vezes, porque a
-- chave é (fonte, publicacao_id).
--
-- Aditiva: a base nova já nasce com a coluna, e a que já aplicou não a recebe de novo.

alter table termometro_fatias
  add column if not exists publicacao_id varchar(64) null after autor_id;

-- Uma publicação é UMA fatia por fonte: a releitura atualiza a linha em vez de criar outra.
-- As linhas sem id não entram na chave (no MySQL, NULL não colide numa chave única).
create unique index if not exists uk_termometro_fatias_publicacao
  on termometro_fatias (fonte, publicacao_id);
