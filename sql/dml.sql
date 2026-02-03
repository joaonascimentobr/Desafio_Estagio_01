SET search_path TO ans;
SET datestyle = 'ISO, DMY';

COPY operadoras_cadastrais (
    registro_ans,
    cnpj,
    razao_social,
    nome_fantasia,
    modalidade,
    logradouro,
    numero,
    complemento,
    bairro,
    cidade,
    uf,
    cep,
    ddd,
    telefone,
    fax,
    endereco_eletronico,
    representante,
    cargo_representante,
    data_registro_ans
) FROM '/home/jean/Documents/ans-demonstracoes/target/ans/operadoras_de_plano_de_saude_ativas.csv'
WITH (FORMAT csv, HEADER true, DELIMITER ';', ENCODING 'ISO-8859-1');

COPY despesas_consolidadas (
    cnpj,
    registro_ans,
    ano,
    trimestre,
    valor_despesas
) FROM '/home/jean/Documents/ans-demonstracoes/target/ans/consolidado_despesas.csv'
WITH (FORMAT csv, HEADER true, DELIMITER ';', ENCODING 'UTF8');
