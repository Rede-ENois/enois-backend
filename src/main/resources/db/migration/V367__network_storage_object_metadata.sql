-- Metadado do objeto e Cache-Control declarado pelo cliente (brief §11 / V367).
--
-- O que faltava: o gateway S3 guardava tipo, tamanho e digest e descartava o resto. Três coisas
-- sumiam em silêncio entre o PUT e o GET:
--   1. `x-amz-meta-*` — o cliente declarava e o HeadObject nunca devolvia;
--   2. `Cache-Control` do PUT — o GET respondia a política do TIPO de conteúdo, não a do objeto;
--   3. `Content-Disposition` do PUT — o `ResponseContentDisposition` da URL pré-assinada, que é o
--      jeito de baixar com nome de arquivo, devolvia o anexo sem o nome.
--
-- Por que duas colunas e não uma genérica: as duas primeiras são o que o S3 inteiro trata como
-- propriedade do objeto (volta no HEAD e no GET, entra na assinatura do cliente), e a terceira é
-- cabeçalho HTTP com regra própria. Guardar tudo num JSON só esconderia a coluna que a leitura usa
-- em toda requisição.
--
-- Nulo é "o cliente não declarou": a leitura cai na política de sempre (CachePolicy do tipo). É o
-- valor que reproduz o comportamento anterior, então objeto antigo continua sendo servido igual.
--
-- Aditiva e idempotente: coluna nova com valor nulo, nenhum dado antigo tocado.

alter table network_storage_objects add column if not exists metadata_json text null;

alter table network_storage_objects add column if not exists cache_control varchar(200) null;

alter table network_storage_objects add column if not exists content_disposition varchar(200) null;