-- Termômetro: indicadores coletivos de clima e necessidade do Explorar.
--
-- O painel não é enquete e não pergunta nada a ninguém: o sistema classifica o conteúdo PÚBLICO
-- já publicado (posts e comentários do feed, desabafos, confissões do Confessionário) com um
-- léxico determinístico local — sem LLM, sem serviço externo — e agrega o resultado por janela
-- de tempo. Nada individual sai daqui: só porcentagens por emoção, o total de manifestações
-- analisadas e quantas pessoas distintas participaram.
--
-- Duas tabelas, um corte incremental:
--
-- termometro_fatias é a CONTABILIDADE por hora do dia (bucket): cada manifestação publicada
-- entra numa fatia com a sua emoção classificada e o id do autor é IMEDIATAMENTE descartado —
-- a fatia guarda só um Set de contagem aproximada? Não: guarda os ids dos autores distintos
-- da hora, porque o chão de amostra (20 pessoas) precisa de contagem distinta real. Os ids
-- ficam SÓ aqui e SÓ pelo tempo da janela máxima (30 dias); nunca saem para o painel.
--
-- termometro_cortes é o RESULTADO por janela (HOJE, 24H, 7D, 30D), recalculado pelo worker a
-- partir das fatias do intervalo — sem releitura de posts, comentários, desabafos ou confissões.
--
-- O texto classificado nunca é gravado: da fonte sai apenas (bucket, emoção, autor). O worker
-- roda a cada 5 minutos; antes de existir amostra suficiente (20 pessoas distintas na janela),
-- o painel diz que ainda não há indicador.

create table if not exists termometro_fatias (
  -- Identidade da fatia: hora cheia + autor + emoção. Uma manifestação nova do mesmo autor
  -- na mesma hora é OUTRA linha (emoções distintas ou repetição), então o id sintético é
  -- gerado na gravação; o que importa ao corte é (hora, emocao, autor_id).
  id varchar(64) not null,
  -- Hora cheia da publicação, em UTC (o bucket): "2026-11-17T18:00:00Z".
  hora timestamp not null,
  -- Fonte da manifestação: POST, COMMENT, DESABAFO ou CONFISSAO.
  fonte varchar(20) not null,
  -- Emoção que o léxico classificou para o texto.
  emocao varchar(20) not null,
  -- Autor interno, para a contagem distinta da janela; nunca sai daqui.
  autor_id varchar(64) not null,
  -- O momento da leitura que produziu esta fatia: a fronteira do corte incremental (o `hora`
  -- acima é o bucket da publicação, não o agora — releitura sem esta coluna duplicaria fatia).
  lida_em timestamp not null,
  primary key (id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

create index if not exists idx_termometro_fatias_hora on termometro_fatias (hora);
create index if not exists idx_termometro_fatias_hora_autor on termometro_fatias (hora, autor_id);
create index if not exists idx_termometro_fatias_lida_em on termometro_fatias (lida_em);

create table if not exists termometro_cortes (
  -- A janela é a chave: o worker reescreve a linha a cada corte de 5 minutos.
  periodo varchar(20) not null,
  contagens text not null,
  manifestacoes bigint not null,
  participantes bigint not null,
  -- Fronteira de leitura das fatias por esta janela (observabilidade do corte incremental).
  watermark timestamp not null,
  updated_at timestamp not null,
  primary key (periodo)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;
