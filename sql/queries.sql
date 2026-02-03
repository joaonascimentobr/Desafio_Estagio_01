SET search_path TO ans;

CREATE OR REPLACE VIEW vw_despesas_enriquecidas AS
SELECT
    d.cnpj,
    o.razao_social,
    d.trimestre,
    d.ano,
    d.valor_despesas,
    o.registro_ans,
    o.modalidade,
    o.uf
FROM ans.despesas_consolidadas d
LEFT JOIN ans.operadoras_cadastrais o
    ON o.cnpj = d.cnpj;

WITH ultimo AS (
    SELECT MAX(ano * 10 + split_part(trimestre, '_', 2)::INT) AS ordem_tri
    FROM ans.despesas_consolidadas
),
filtrado AS (
    SELECT d.*
    FROM ans.despesas_consolidadas d
    JOIN ultimo u
        ON (d.ano * 10 + split_part(d.trimestre, '_', 2)::INT) = u.ordem_tri
)
SELECT
    o.modalidade,
    SUM(f.valor_despesas) AS total_modalidade
FROM filtrado f
LEFT JOIN ans.operadoras_cadastrais o
    ON o.cnpj = f.cnpj
GROUP BY o.modalidade
ORDER BY total_modalidade DESC NULLS LAST;

SELECT DISTINCT d.cnpj
FROM ans.despesas_consolidadas d
LEFT JOIN ans.operadoras_cadastrais o
    ON o.cnpj = d.cnpj
WHERE o.cnpj IS NULL;


WITH ordem AS (
    SELECT
        d.cnpj,
        d.valor_despesas,
        (d.ano * 10 + split_part(d.trimestre, '_', 2)::INT) AS ordem_tri
    FROM ans.despesas_consolidadas d
),
minmax AS (
    SELECT
        cnpj,
        MIN(ordem_tri) AS min_tri,
        MAX(ordem_tri) AS max_tri
    FROM ordem
    GROUP BY cnpj
),
valores AS (
    SELECT
        m.cnpj,
        o1.valor_despesas AS valor_inicial,
        o2.valor_despesas AS valor_final
    FROM minmax m
    LEFT JOIN ordem o1
        ON o1.cnpj = m.cnpj AND o1.ordem_tri = m.min_tri
    LEFT JOIN ordem o2
        ON o2.cnpj = m.cnpj AND o2.ordem_tri = m.max_tri
)
SELECT
    v.cnpj,
    v.valor_inicial,
    v.valor_final,
    CASE
        WHEN v.valor_inicial IS NULL OR v.valor_inicial = 0 THEN NULL
        ELSE ((v.valor_final - v.valor_inicial) / v.valor_inicial) * 100
    END AS crescimento_percentual
FROM valores v
ORDER BY crescimento_percentual DESC NULLS LAST
LIMIT 5;

WITH por_uf AS (
    SELECT
        o.uf,
        SUM(d.valor_despesas) AS total_uf,
        AVG(d.valor_despesas) AS media_por_operadora
    FROM ans.despesas_consolidadas d
    LEFT JOIN ans.operadoras_cadastrais o
        ON o.cnpj = d.cnpj
    GROUP BY o.uf
)
SELECT *
FROM por_uf
ORDER BY total_uf DESC NULLS LAST
LIMIT 5;

WITH media_geral AS (
    SELECT AVG(valor_despesas) AS media_global
    FROM ans.despesas_consolidadas
),
comparativo AS (
    SELECT
        d.cnpj,
        d.ano,
        d.trimestre,
        CASE WHEN d.valor_despesas > mg.media_global THEN 1 ELSE 0 END AS acima_media
    FROM ans.despesas_consolidadas d
    CROSS JOIN media_geral mg
),
contagem AS (
    SELECT
        cnpj,
        COUNT(*) FILTER (WHERE acima_media = 1) AS trimestres_acima
    FROM comparativo
    GROUP BY cnpj
)
SELECT COUNT(*) AS operadoras_acima_media_em_2_ou_mais
FROM contagem
WHERE trimestres_acima >= 2;
