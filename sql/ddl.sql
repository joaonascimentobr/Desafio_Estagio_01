-- PostgreSQL 10+ DDL (Teste 3) - Modelo Normalizado (Opção B)
BEGIN;

CREATE SCHEMA IF NOT EXISTS ans;
SET search_path TO ans;

CREATE TABLE IF NOT EXISTS operadoras_cadastrais (
    registro_ans         VARCHAR(20),
    cnpj                 VARCHAR(20),
    razao_social         TEXT,
    nome_fantasia        TEXT,
    modalidade           TEXT,
    logradouro           TEXT,
    numero               TEXT,
    complemento          TEXT,
    bairro               TEXT,
    cidade               TEXT,
    uf                   CHAR(2),
    cep                  TEXT,
    ddd                  TEXT,
    telefone             TEXT,
    fax                  TEXT,
    endereco_eletronico  TEXT,
    representante        TEXT,
    cargo_representante  TEXT,
    data_registro_ans    DATE
);

ALTER TABLE operadoras_cadastrais
    ADD CONSTRAINT operadoras_cadastrais_pk PRIMARY KEY (cnpj);

CREATE INDEX IF NOT EXISTS idx_operadoras_cnpj ON operadoras_cadastrais (cnpj);
CREATE INDEX IF NOT EXISTS idx_operadoras_registro_ans ON operadoras_cadastrais (registro_ans);

CREATE TABLE IF NOT EXISTS despesas_consolidadas (
    id             BIGSERIAL PRIMARY KEY,
    cnpj           VARCHAR(20) NOT NULL REFERENCES operadoras_cadastrais (cnpj),
    registro_ans   VARCHAR(20),
    ano            INTEGER NOT NULL,
    trimestre      VARCHAR(32) NOT NULL,
    valor_despesas DECIMAL(18,2) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_despesas_cnpj ON despesas_consolidadas (cnpj);
CREATE INDEX IF NOT EXISTS idx_despesas_ano_tri ON despesas_consolidadas (ano, trimestre);

CREATE TABLE IF NOT EXISTS despesas_agregadas_final (
    id             BIGSERIAL PRIMARY KEY,
    cnpj           VARCHAR(20) NOT NULL REFERENCES operadoras_cadastrais (cnpj),
    registro_ans   VARCHAR(20),
    modalidade     TEXT,
    uf             CHAR(2),
    total          DECIMAL(18,2) NOT NULL,
    media          DECIMAL(18,2) NOT NULL,
    desvio_padrao  DECIMAL(18,2) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_agregado_cnpj ON despesas_agregadas_final (cnpj);
CREATE INDEX IF NOT EXISTS idx_agregado_uf ON despesas_agregadas_final (uf);

CREATE TABLE IF NOT EXISTS stg_operadoras_cadastrais (
    cnpj           TEXT,
    registro_ans   TEXT,
    razao_social   TEXT,
    modalidade     TEXT,
    uf             TEXT,
    data_cadastro  TEXT
);

CREATE TABLE IF NOT EXISTS stg_despesas_consolidadas (
    cnpj            TEXT,
    registro_ans    TEXT,
    ano             TEXT,
    trimestre_label TEXT,
    valor_despesas  TEXT
);

CREATE TABLE IF NOT EXISTS stg_despesas_agregadas (
    cnpj          TEXT,
    registro_ans  TEXT,
    modalidade    TEXT,
    uf            TEXT,
    total         TEXT,
    media         TEXT,
    desvio_padrao TEXT
);

CREATE TABLE IF NOT EXISTS log_erros_importacao (
    id            BIGSERIAL PRIMARY KEY,
    tabela_origem TEXT NOT NULL,
    payload       TEXT NOT NULL,
    motivo        TEXT NOT NULL,
    criado_em     TIMESTAMP NOT NULL DEFAULT NOW()
);

COMMIT;
