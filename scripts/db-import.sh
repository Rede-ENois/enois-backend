#!/usr/bin/env bash
set -euo pipefail

# Importa um dump SQL para o banco local (H2 ou MariaDB local)
# Uso: ./scripts/db-import.sh dump.sql
# Uso: ./scripts/db-import.sh dump.sql local_mysql (para importar em MySQL local em vez de H2)

MODE="${2:-h2}"
FILE="${1:-dump.sql}"

if [ ! -f "$FILE" ]; then
  echo "Arquivo $FILE nao encontrado."
  echo "Uso: $0 [arquivo.sql] [h2|local_mysql]"
  exit 1
fi

case "$MODE" in
  h2)
    echo "Importando $FILE para H2 local (dev)..."
    echo "Passos manuais necessarios:"
    echo ""
    echo "1. Inicie a aplicacao em dev: ./mvnw spring-boot:run"
    echo "2. Conecte no H2 Console em http://localhost:8081/h2-console"
    echo "   JDBC URL: jdbc:h2:mem:rede"
    echo "   User: sa"
    echo "   Password: (vazio)"
    echo "3. Execute o conteudo do arquivo SQL manualmente ou use:"
    echo "   java -cp h2*.jar org.h2.tools.RunScript \\"
    echo "     -url 'jdbc:h2:mem:rede' -user sa -script $FILE"
    echo ""
    echo "Flyway vai rodar as migrations ao iniciar, entao o dump"
    echo "deve conter apenas dados (INSERT), sem schema."
    echo "Remova as linhas CREATE TABLE e ALTER TABLE do dump."
    echo ""
    echo "Dica: grep -v '^CREATE TABLE\|^ALTER TABLE\|^DROP TABLE' $FILE > apenas_dados.sql"
    ;;

  local_mysql)
    echo "Importando $FILE para MySQL/MariaDB local..."
    mysql -u root -p "$(basename "$FILE" .sql)" < "$FILE"
    echo "Pronto. Banco '$(basename "$FILE" .sql)' criado/populado."
    ;;

  *)
    echo "Modo invalido: $MODE. Use 'h2' ou 'local_mysql'."
    exit 1
    ;;
esac
