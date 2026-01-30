# Desafio Estágio 01 - Consolidação de Despesas

Este projeto consolida despesas de eventos/sinistros da ANS e agora inclui uma etapa de validação com foco em qualidade de dados.

## Estratégia de validação

### 1) CNPJ duplicado com razão social divergente
- Coletamos CNPJ e Razão Social quando presentes nos arquivos de origem.
- Se o mesmo CNPJ aparecer com mais de uma razão social, ele é listado no relatório `relatorio_validacao.csv`.
- No consolidado, os registros desse CNPJ recebem o status `CNPJ_DIVERGENTE`.

### 2) Valores zerados ou negativos
- Valores `<= 0` não são descartados.
- Eles são marcados com o status `VALOR_SUSPEITO` no consolidado para revisão posterior.

### 3) Normalização de trimestre
A normalização segue o padrão único `1T2025`:
- Exemplos aceitos: `1T2025`, `2025_1_trimestre`, `1-2025`.
- Quando não há data completa, o parser tenta extrair trimestre e ano a partir desses formatos.
- O resultado é gravado na coluna `TrimestreReferencia`.

## Saídas geradas
- `target/ans/consolidado_despesas.csv`: consolidado com coluna `StatusValidacao`.
- `target/ans/relatorio_validacao.csv`: relatório de CNPJs com razões sociais divergentes (quando existir).
