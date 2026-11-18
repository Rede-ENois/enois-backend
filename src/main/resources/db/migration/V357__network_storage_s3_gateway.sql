-- Gateway S3 do Énóis Storage (brief §11).
--
-- O que faltava: o Storage já tem o modelo certo — `bucket` + `object_key` únicos, chave byte a byte,
-- índice para listagem por prefixo, ETag, Range, `storage.list(bucket, prefix, after, limit)`. Mas a
-- única porta era `/network/storage/*` autenticada por chave de NÓ (X-Node-Id + X-Node-Key), que é
-- credencial de máquina da rede, não de aplicação cliente. Nenhum SDK S3 fala esse protocolo.
--
-- Esta tabela é a credencial de CLIENTE: o par Access Key / Secret Key que o SigV4 assina. Ela é
-- separada de `network_node_keys` de propósito, e não é uma variação dela:
--  * a chave de nó dá acesso a OPERAR a rede (puxar tarefas, gravar réplica); a credencial S3 dá
--    acesso a BUCKETS específicos e a operações específicas — escopo por bucket e por verbo;
--  * rotacionar a credencial de uma aplicação não pode derrubar um nó, e vice-versa;
--  * o nó nunca recebe credencial S3, e a credencial S3 nunca recebe chave de nó (§10: "credenciais
--    administrativas nunca compartilhadas com participantes da infraestrutura").
--
-- Segredo: DUAS colunas, e cada uma responde a uma pergunta diferente:
--   * `secret_hash` (SHA-256) confere a credencial e permite auditar sem expor nada;
--   * `secret_encrypted` guarda o segredo CIFRADO com o `SecretCipher` (AES-GCM) que a rede já usa
--     para o segredo do TOTP, com a chave de `REDE_SECURITY_ENCRYPTION_KEY`. O SigV4 precisa do
--     segredo em claro para recalcular o HMAC — um hash não serve, e guardar claro seria pior.
--     Cifrar pelo mesmo mecanismo existente é o reuso obrigatório: nada de segundo cofre.
-- Sem a chave de cifra (perfil de dev sem ela), a criação de credencial é recusada com 503, e uma
-- credencial sem segredo não autentica — em vez de "aceitar sem conferir".
--
-- Idempotente e aditiva.

create table if not exists network_storage_s3_credentials (
  id varchar(40) not null,
  -- Access Key é o identificador público (vai no header Authorization, não é segredo).
  access_key varchar(32) not null,
  secret_hash varchar(64) not null,
  -- Cifrado, nunca em claro. Nulo em credencial criada sem chave de cifra disponível (recusada).
  secret_encrypted varchar(255) null,
  -- Dono: a conta que criou. Buckets ficam por conta (owner_user_id), como no catálogo.
  owner_user_id varchar(40) not null,
  -- Bucket permitido. NULO = todos os buckets do dono. '*' literal não é usado: nulo diz
  -- "todos" sem ambiguidade com um bucket chamado '*' (um nome válido de bucket).
  allowed_bucket varchar(63) null,
  -- Verbos permitidos, separados por espaço ("GET PUT"). Ordem não importa.
  allowed_operations varchar(120) not null default 'GET',
  revoked_at timestamp(3) null,
  created_at timestamp(3) not null default current_timestamp(3),
  primary key (id),
  constraint uq_network_storage_s3_credentials_access unique (access_key),
  constraint chk_network_storage_s3_credentials_ops check (length(allowed_operations) > 0)
) engine = InnoDB default charset = utf8mb4 collate = utf8mb4_unicode_ci;

-- A autenticação chega pelo Access Key, a cada requisição: o índice é a própria unicidade.

-- As credenciais de um dono, para o painel listar o que ele tem ativo.
create index if not exists idx_network_storage_s3_credentials_owner
  on network_storage_s3_credentials (owner_user_id, created_at);