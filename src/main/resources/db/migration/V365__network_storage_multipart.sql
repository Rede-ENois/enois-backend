-- Énóis Storage (gateway S3): Multipart Upload (V361).
--
-- Por que a tabela existe: até aqui o gateway recusava CreateMultipartUpload com 501, e isso NÃO era
-- um detalhe de compatibilidade — é o caminho padrão de upload dos SDKs (boto3 acima de 8 MB, SDK
-- Java acima de 5 MiB). Quem mandava um vídeo pelo SDK recebia erro num arquivo que o protocolo
-- inteiro sabe enviar.
--
-- Dois estados, e os dois precisam ser duráveis:
--
-- 1. O upload em si (`network_storage_multipart_uploads`). Guarda quem começou, o bucket, a chave, o
--    tipo, a credencial que abriu e até quando as partes sobrevivem. A credencial fica aqui porque a
--    DONAGEM do bucket só pode ser provada no início — é o `CreateMultipartUpload` que cria o bucket
--    do dono; cada `UploadPart` depois carrega a mesma credencial do upload, não uma nova assinatura
--    que poderia ser de outra conta.
--
-- 2. As partes (`network_storage_multipart_parts`), com o digest de CADA uma. O digest por parte é o
--    que torna o arquivo inteiro verificável sem confiar na ordem de chegada: o ETag final é
--    sha256 dos digests concatenados, que o cliente e o servidor calculam do mesmo dado.
--
-- A conclusão é atômica do ponto de vista do cliente: `CompleteMultipartUpload` lê as partes na
-- ordem pedida e grava o objeto pelo MESMO caminho do PUT simples (`beginUpload`/`finishUpload`).
-- Ou o objeto existe inteiro, com o digest que o cliente viu, ou não existe — nunca um objeto pela
-- metade que um GET serviria como se estivesse completo.
--
-- TTL: partes de upload abandonado ocupam disco no nó e linha no catálogo. A varredura
-- (`rede.network.storage.multipart.sweep-hours`) descarta o que passou de `expires_at`; o prazo é
-- gravado no início, e é o cliente que escolhe (com teto da instalação).
--
-- Charset/collation explícitos (o H2 da suíte perdoa, o MariaDB não), e `key` vai entre crases: é
-- palavra reservada no MySQL/MariaDB e a migração falharia no schema real.
--
-- Os tamanhos de bucket e chave são os da V319 (`bucket varchar(63)`, `object_key varchar(700)`):
-- um upload multipart cria um objeto no mesmíssimo catálogo, e uma chave que cabe no PUT simples
-- mas não no multipart (ou o contrário) seria uma recusa que depende do verbo, não do dado.

create table if not exists network_storage_multipart_uploads (
  upload_id varchar(64) not null,
  bucket varchar(63) not null,
  `key` varchar(700) not null,
  content_type varchar(255) not null,
  owner_user_id varchar(64) not null,
  credential_id varchar(64) null,
  created_at datetime(3) not null,
  expires_at datetime(3) not null,
  primary key (upload_id),
  key idx_network_storage_multipart_uploads_expiry (expires_at),
  key idx_network_storage_multipart_uploads_owner (owner_user_id, created_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

create table if not exists network_storage_multipart_parts (
  upload_id varchar(64) not null,
  part_number int not null,
  bytes bigint not null,
  sha256 char(64) not null,
  received_at datetime(3) not null,
  primary key (upload_id, part_number),
  constraint fk_multipart_parts_upload foreign key (upload_id)
    references network_storage_multipart_uploads (upload_id) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;