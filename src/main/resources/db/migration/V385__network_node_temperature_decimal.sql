-- Alinha a coluna ao tipo do mapeamento: a entidade usa BigDecimal, e o Hibernate valida a coluna
-- como numeric/decimal. A V382 criou a coluna como `double`; com `ddl-auto=validate` o boot falha
-- no MariaDB (Types.DOUBLE != NUMERIC) e no H2 (double precision != numeric(38,2)).
-- decimal(15,2) guarda graus Celsius com duas casas, sem perda útil.
alter table network_nodes modify column observed_temperature_c decimal(15,2) null;
alter table network_node_samples modify column temperature_c decimal(15,2) null;
