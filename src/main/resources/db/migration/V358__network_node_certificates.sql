-- Brief §10 (Segurança e isolamento): "mTLS", "certificados individuais", "rotação e revogação",
-- "criptografia em trânsito". O doc 04 §1 descreve DUAS credenciais por nó — o certificado (protege o
-- canal) e a API key (autorização administrativa). Até aqui só existia a segunda (`network_node_keys`);
-- esta tabela é a primeira, e o §8 do mesmo doc declarava o mTLS "ainda não implementado".
--
-- O certificado é emitido pelo Control Plane a partir de um CSR do nó (o nó gerou o par e guarda a
-- chave privada no próprio keystore). Por isso aqui NÃO existe coluna de chave privada, nem da CA:
--   * a chave privada do nó nunca sai do nó;
--   * a chave da CA vive na configuração do Control Plane (variável de ambiente), nunca no banco —
--     o operador com acesso ao disco lê certificados, não pode assinar com eles (brief §10).
--
-- O que se guarda é o certificado (público), a chave pública que ele carrega, o período de
-- validade e o estado de revogação. O `fingerprint_sha256` é o identificador que o painel e o
-- operador conferem; o `serial` é o que o cliente vê no TLS.
--
-- Revogação é linha alterada, não linha apagada: auditoria e o painel precisam do histórico da
-- rotação, e apagar perderia o rastro do certificado que circulou.
--
-- Aditiva e idempotente, como as demais deste módulo.

create table if not exists network_node_certificates (
  id varchar(40) not null,
  node_id varchar(40) not null,
  serial varchar(64) not null,
  subject varchar(160) not null,
  pem text not null,
  public_key varchar(512) not null,
  fingerprint_sha256 varchar(64) not null,
  not_before timestamp(3) not null,
  not_after timestamp(3) not null,
  created_at timestamp(3) not null,
  revoked_at timestamp(3) null,
  revoked_by varchar(40) null,
  revoked_reason varchar(120) null,
  primary key (id),
  unique key uk_network_node_certificates_fingerprint (fingerprint_sha256),
  key idx_network_node_certificates_node (node_id, revoked_at),
  key idx_network_node_certificates_serial (serial)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;