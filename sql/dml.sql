-- PostgreSQL 10+ DML (Teste 3)
SET search_path TO ans;

-- COPY (operadoras) - ISO-8859-1 e delimitador ';'
-- Ajuste o caminho dos arquivos conforme o ambiente
-- Use psql e COPY (server-side) ou \copy (client-side)
SET datestyle = 'ISO, DMY';

-- COPY operadoras_cadastrais (
--     registro_ans,
--     cnpj,
--     razao_social,
--     nome_fantasia,
--     modalidade,
--     logradouro,
--     numero,
--     complemento,
--     bairro,
--     cidade,
--     uf,
--     cep,
--     ddd,
--     telefone,
--     fax,
--     endereco_eletronico,
--     representante,
--     cargo_representante,
--     data_registro_ans
-- ) FROM '/caminho/operadoras_de_plano_de_saude_ativas.csv'
-- WITH (FORMAT csv, HEADER true, DELIMITER ';', ENCODING 'ISO-8859-1');

-- COPY (despesas consolidadas) - UTF-8 e delimitador ';'
-- CSV gerado pelo Java: CNPJ, RegistroANS, Ano, Trimestre, ValorDespesas
-- COPY despesas_consolidadas (
--     cnpj,
--     registro_ans,
--     ano,
--     trimestre,
--     valor_despesas
-- ) FROM '/caminho/consolidado_despesas.csv'
-- WITH (FORMAT csv, HEADER true, DELIMITER ';', ENCODING 'UTF8');
