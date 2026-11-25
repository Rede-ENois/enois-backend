#!/usr/bin/env python3
"""Verifica o upgrade do texto das vagas externas em MariaDB legado, sem porta publicada nem rede."""

import os
from pathlib import Path
import subprocess
import time
import unittest
import uuid


ROOT = Path(__file__).resolve().parents[2]
IMAGE = os.environ.get(
    "MARIADB_TEST_IMAGE",
    "mariadb:11.4@sha256:250e1609e93c0e6aecf64bfbab2d84d5e89d2481540fe77447ff6c855c3b1873",
)
MIGRATION = ROOT / "src/main/resources/db/migration/V379__vagas_externas_texto_utf8mb4.sql"
TABELA = "external_jobs"

# (coluna, tipo, nulidade, default) das colunas de texto depois da migracao.
COLUNAS = [
    ("platform", "varchar(64)", "NO", None),
    ("external_id", "varchar(255)", "NO", None),
    ("title", "varchar(500)", "NO", None),
    ("company", "varchar(255)", "YES", None),
    ("location", "varchar(255)", "YES", None),
    ("url", "varchar(1000)", "NO", None),
    ("canonical_url", "varchar(1000)", "YES", None),
    ("external_status", "varchar(32)", "NO", "FOUND"),
    ("salary", "varchar(255)", "YES", None),
    ("currency", "varchar(8)", "YES", None),
    ("salary_min", "varchar(50)", "YES", None),
    ("salary_max", "varchar(50)", "YES", None),
    ("salary_period", "varchar(20)", "YES", None),
    ("apply_email", "varchar(320)", "YES", None),
    ("submitted_by", "varchar(255)", "YES", None),
    ("description", "varchar(5000)", "YES", None),
    ("employment_type", "varchar(40)", "YES", None),
    ("level", "varchar(20)", "YES", None),
    ("work_model", "varchar(20)", "YES", None),
    ("city", "varchar(120)", "YES", None),
    ("state", "varchar(60)", "YES", None),
    ("country", "varchar(60)", "YES", None),
    ("salary_currency", "varchar(8)", "YES", None),
    ("salary_period_code", "varchar(10)", "YES", None),
    ("attribution_url", "varchar(1000)", "YES", None),
]

# O que o anuncio das fontes traz: espaco fino (U+202F) antes do simbolo de moeda, que e o byte da
# linha do log da qa, mais acento e emoji.
TEXTO_UNICODE = "12\u202f000 € pro Jahr 🧑‍💻 — desenvolvimento"

# Schema legado: a tabela nasceu (V40) sem charset e herdou o default do banco da epoca. As colunas
# do texto do anuncio entram como estavam antes da V379 (V44 canonical_url, V65 apply_email,
# V102 submitted_by, V112 descricao e faixas, V269 normalizados).
SCHEMA_LEGADO = """
    create table external_jobs (
        id varchar(255) not null,
        platform varchar(64) not null,
        external_id varchar(255) not null,
        title varchar(500) not null,
        company varchar(255),
        location varchar(255),
        salary varchar(255),
        currency varchar(8),
        url varchar(1000) not null,
        imported_at timestamp not null,
        last_checked_at timestamp not null,
        external_status varchar(32) not null default 'FOUND',
        primary key (id)
    ) engine=InnoDB default charset=latin1 collate=latin1_swedish_ci;

    alter table external_jobs add constraint uk_external_jobs_source unique (platform, external_id);
    create index idx_external_jobs_checked on external_jobs (last_checked_at);

    alter table external_jobs add column canonical_url varchar(1000);
    update external_jobs set canonical_url = url where canonical_url is null;
    create index idx_external_jobs_canonical on external_jobs (canonical_url);

    alter table external_jobs add column apply_email varchar(320) null;
    alter table external_jobs add column if not exists submitted_by varchar(255);
    create index if not exists idx_external_jobs_submitted_by on external_jobs (submitted_by);

    alter table external_jobs add column if not exists description varchar(5000) null;
    alter table external_jobs add column if not exists salary_min varchar(50) null;
    alter table external_jobs add column if not exists salary_max varchar(50) null;
    alter table external_jobs add column if not exists salary_period varchar(20) null;

    alter table external_jobs add column if not exists published_at timestamp null;
    alter table external_jobs add column if not exists employment_type varchar(40) null;
    alter table external_jobs add column if not exists level varchar(20) null;
    alter table external_jobs add column if not exists work_model varchar(20) null;
    alter table external_jobs add column if not exists city varchar(120) null;
    alter table external_jobs add column if not exists state varchar(60) null;
    alter table external_jobs add column if not exists country varchar(60) null;
    alter table external_jobs add column if not exists salary_min_amount numeric(12,2) null;
    alter table external_jobs add column if not exists salary_max_amount numeric(12,2) null;
    alter table external_jobs add column if not exists salary_currency varchar(8) null;
    alter table external_jobs add column if not exists salary_period_code varchar(10) null;
    alter table external_jobs add column if not exists attribution_url varchar(1000) null;
    alter table external_jobs add column if not exists last_seen_at timestamp null;
    alter table external_jobs add column if not exists expires_at timestamp null;
    alter table external_jobs add column if not exists removed_at timestamp null;

    create index if not exists idx_external_jobs_active on external_jobs (removed_at, published_at);
    create index if not exists idx_external_jobs_work_model on external_jobs (work_model);
    create index if not exists idx_external_jobs_level on external_jobs (level);
    create index if not exists idx_external_jobs_salary on external_jobs (salary_min_amount);
    create index if not exists idx_external_jobs_platform_seen on external_jobs (platform, last_seen_at);

    insert into external_jobs
        (id, platform, external_id, title, company, location, salary, currency, url,
         imported_at, last_checked_at, external_status, description, city, state, work_model)
    values
        ('job_1', 'Adzuna', 'ext-1', 'Designer de Produto à distância', 'Empresa Cafés',
         'São Paulo', 'R$ 8.000', 'BRL', 'https://exemplo.test/vaga/1',
         current_timestamp, current_timestamp, 'FOUND', 'Descrição com acentuação',
         'São Paulo', 'SP', 'remote');
"""


class ExternalJobsTextoUtf8mb4MigrationTests(unittest.TestCase):
    @classmethod
    def comando(cls, *args, entrada=None):
        return subprocess.run(
            args,
            input=entrada,
            text=True,
            encoding="utf-8",
            capture_output=True,
            check=True,
            timeout=120,
        ).stdout.strip()

    @classmethod
    def sql(cls, texto):
        return cls.comando(
            "docker", "exec", "-i", cls.container,
            "mariadb", "--default-character-set=utf8mb4", "--batch",
            "--skip-column-names", "-uroot", "vagas", entrada=texto,
        )

    @classmethod
    def setUpClass(cls):
        cls.comando("docker", "image", "inspect", IMAGE)
        cls.container = "enois-vagas-texto-test-" + uuid.uuid4().hex[:12]
        try:
            cls.comando(
                "docker", "run", "-d", "--pull=never", "--network=none",
                "--name", cls.container,
                "-e", "MARIADB_ALLOW_EMPTY_ROOT_PASSWORD=1",
                "-e", "MARIADB_DATABASE=vagas",
                "-e", "MARIADB_CHARACTER_SET_SERVER=latin1",
                "-e", "MARIADB_COLLATION_SERVER=latin1_swedish_ci",
                IMAGE,
            )
            for _ in range(90):
                pronto = subprocess.run(
                    ["docker", "exec", cls.container, "healthcheck.sh",
                     "--connect", "--innodb_initialized"],
                    capture_output=True, timeout=15,
                )
                if pronto.returncode == 0:
                    break
                time.sleep(1)
            else:
                raise RuntimeError("MariaDB de teste nao ficou pronto")
            cls.sql(SCHEMA_LEGADO)
            cls.sql(MIGRATION.read_text(encoding="utf-8"))
        except BaseException:
            subprocess.run(["docker", "rm", "-f", "--volumes", cls.container], capture_output=True, timeout=30)
            raise

    @classmethod
    def tearDownClass(cls):
        cls.comando("docker", "rm", "-f", "--volumes", cls.container)

    def coluna(self, coluna):
        return self.sql(f"""
            select concat(character_set_name, '|', collation_name, '|', is_nullable, '|',
                          column_type, '|', column_default)
            from information_schema.columns
            where table_schema = 'vagas' and table_name = '{TABELA}' and column_name = '{coluna}';
        """)

    def test_colunas_de_texto_declaram_utf8mb4(self):
        for coluna, tipo, nulidade, default in COLUNAS:
            self.assertEqual(
                self.coluna(coluna),
                # Sem default, o mariadb --batch escreve o proprio NULL desta linha.
                f"utf8mb4|utf8mb4_unicode_ci|{nulidade}|{tipo}|{default or 'NULL'}",
                msg=f"{TABELA}.{coluna} nao ficou em utf8mb4 como esperado",
            )

    def test_nenhuma_coluna_de_texto_ficou_no_charset_legado(self):
        restantes = self.sql(f"""
            select group_concat(column_name order by column_name)
            from information_schema.columns
            where table_schema = 'vagas' and table_name = '{TABELA}'
              and data_type in ('char', 'varchar', 'text', 'mediumtext', 'longtext')
              and character_set_name <> 'utf8mb4';
        """)
        # `id` fica no charset antigo de proposito: converter a pk (alvo do uk) e o drift de
        # collation que a migracao evita.
        self.assertEqual(restantes, "id", msg=f"colunas fora do utf8mb4: {restantes}")

    def test_id_continua_na_collation_original(self):
        self.assertEqual(
            self.sql(f"""
                select concat(character_set_name, '|', collation_name)
                from information_schema.columns
                where table_schema = 'vagas' and table_name = '{TABELA}' and column_name = 'id';
            """),
            "latin1|latin1_swedish_ci",
        )

    def test_conteudo_legado_continua_igual(self):
        self.assertEqual(
            self.sql("select title from external_jobs where id = 'job_1';"),
            "Designer de Produto à distância",
        )
        self.assertEqual(self.sql("select company from external_jobs where id = 'job_1';"), "Empresa Cafés")
        self.assertEqual(self.sql("select city from external_jobs where id = 'job_1';"), "São Paulo")
        self.assertEqual(
            self.sql("select description from external_jobs where id = 'job_1';"),
            "Descrição com acentuação",
        )

    def test_anuncio_com_espaco_fino_e_emoji_entra(self):
        hexadecimal = TEXTO_UNICODE.encode("utf-8").hex()
        self.sql(f"""
            insert into external_jobs
                (id, platform, external_id, title, url, imported_at, last_checked_at,
                 salary_min, description, currency)
            values
                ('job_2', 'Arbeitnow', 'ext-2', convert(unhex('{hexadecimal}') using utf8mb4),
                 'https://exemplo.test/vaga/2', current_timestamp, current_timestamp,
                 convert(unhex('{hexadecimal}') using utf8mb4),
                 convert(unhex('{hexadecimal}') using utf8mb4), 'EUR');
        """)
        self.assertEqual(
            self.sql("select hex(description) from external_jobs where id = 'job_2';"),
            hexadecimal.upper(),
        )
        self.assertEqual(
            self.sql("select hex(title) from external_jobs where id = 'job_2';"),
            hexadecimal.upper(),
        )

    def test_chave_da_fonte_continua_sem_repetir(self):
        with self.assertRaises(subprocess.CalledProcessError):
            self.sql("""
                insert into external_jobs (id, platform, external_id, title, url, imported_at, last_checked_at)
                values ('job_3', 'Adzuna', 'ext-1', 'Outra', 'https://exemplo.test/vaga/3',
                        current_timestamp, current_timestamp);
            """)


if __name__ == "__main__":
    unittest.main(verbosity=2)
