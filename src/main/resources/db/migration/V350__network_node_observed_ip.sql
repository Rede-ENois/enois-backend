-- Brief §5 (servidores domésticos atrás de NAT): "Detecção de mudança de IP".
--
-- O participante doméstico não informa IP no painel (o §5 lista isso como o que ele NÃO deve
-- precisar fazer) e a conexão é sempre de saída. Quem sabe o endereço de origem em cada momento é
-- o Control Plane, na borda do heartbeat -- e a rede precisa enxergar QUANDO ele mudou. Sem isso,
-- duas coisas ficam invisíveis: um nó que mudou de rede (mudou o roteador, trocou a operadora, saiu
-- do NAT e voltou) e um heartbeat que passou a vir de outro lugar com uma credencial válida.
--
-- O que se guarda é o ÚLTIMO endereço visto e o instante da última MUDANÇA -- não uma série
-- histórica. Mesmo princípio de `last_seen_at` e da reconexão (§8.6): transição, não amostra. Uma
-- linha por heartbeat por nó afogaria o banco com o que não muda.
--
-- Nulo em `observed_ip` é "não medido", nunca "sem endereço": há caminhos que não expõem o peer
-- (teste sem socket, proxy que não propaga) e gravar vazio ali seria afirmar que o nó não tem
-- endereço. Nulo em `observed_ip_changed_at` é "nunca mudou desde o primeiro contato visto".
--
-- Aditiva e idempotente, como as demais deste módulo.

-- Duas instruções, não um `alter table` com duas colunas: o H2 (contexto de teste) recusa a forma
-- composta, e a migração tem de rodar no H2 dos testes e no MariaDB real da produção.
alter table network_nodes add column if not exists observed_ip varchar(45) null;

alter table network_nodes add column if not exists observed_ip_changed_at timestamp(3) null;