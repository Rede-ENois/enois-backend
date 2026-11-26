-- Leitura de intenção de texto (tema real, intenção e resumo), em cache.
--
-- Uma linha por texto lido. A chave é o HASH do conteúdo (sha-256 hex): o mesmo texto, editado ou
-- relido, casa a MESMA linha — a leitura é idempotente e nunca paga duas vezes pelo mesmo texto.
-- O worker lê publicações/comentários sem linha, chama a inferência em lote e grava aqui; o Papo
-- Reto e o Wrapped leem daqui, nunca da inferência no carregamento da tela.
--
-- Sem FK (padrão do projeto). DDL com charset/collation explícito (MariaDB de produção difere do H2).

create table if not exists leitura_texto (
  conteudo_hash char(64) not null,
  -- Tema(s) reais, normalizados e separados por | (no máximo 3). Vazio quando não houve leitura.
  temas varchar(400) not null default '',
  -- Intenção: um dos valores do enum Intencao (DESCONHECIDA quando degradou).
  intencao varchar(24) not null default 'DESCONHECIDA',
  resumo varchar(280),
  -- 1 quando veio do modelo; 0 quando degradou para a extração determinística.
  lido_pelo_modelo boolean not null default false,
  -- Versão do prompt/contrato que produziu a linha, para reprocessar quando o contrato mudar.
  versao int not null default 1,
  lido_em timestamp not null,
  primary key (conteudo_hash)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

-- A fila do worker: as leituras degradadas (lido_pelo_modelo = 0) podem ser refeitas quando o
-- modelo volta; a varredura acha essas por esta ordem.
create index if not exists idx_leitura_texto_degradada on leitura_texto (lido_pelo_modelo, lido_em);
