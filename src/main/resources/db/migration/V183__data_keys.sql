-- Chaves de dados (DEK) embrulhadas pelo cofre.
--
-- O que fica nesta tabela é o material JA CIFRADO pela chave-mestra do cofre (Transit). A chave em
-- claro nunca entra no banco, em backup nem em dump: sem o cofre deslacrado, o conteudo daqui nao
-- decifra nada.
--
-- `retired_at` marca chave que nao cifra mais nada (rotacao). Fica a linha para ainda abrir os
-- registros antigos; apagar a linha e o que torna aqueles dados irrecuperaveis (crypto-shredding).
--
-- Charset/collation explicitos: o padrao do MariaDB de producao difere do H2 e de outros bancos
-- (armadilha ja registrada em migration anterior).
CREATE TABLE IF NOT EXISTS data_keys (
  kid VARCHAR(64) NOT NULL,
  wrapped_key MEDIUMTEXT NOT NULL,
  algorithm VARCHAR(32) NOT NULL DEFAULT 'AES-256-GCM',
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  retired_at TIMESTAMP(6) NULL,
  PRIMARY KEY (kid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
