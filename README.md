# Projeto de Engenharia de Dados (Java 17+ / Maven)

Este projeto resolve o **Teste de Entrada para Estagiários v2.0** da Intuitive Care, com foco na integração e consolidação de dados da **API de Dados Abertos da ANS**. A solução automatiza o download, extração, normalização e consolidação de arquivos de despesas, gerando um CSV consolidado pronto para análise.

**Padrão de Organização (Normalização)**
- Adotado o padrão de identificação `YYYY_Q_trimestre` (ex: `2025_1_trimestre`) para garantir **ordenação cronológica** e evitar conflitos causados pela estrutura de diretórios **inconsistente** da ANS.

**Decisões Técnicas (Trade-offs) - Java**
- **Processamento incremental**: os arquivos CSV são processados via streams/iteradores para reduzir uso de memória e permitir escalabilidade com grandes volumes.
- **Tipagem de dados**: `BigDecimal` é usado para a coluna *Valor Despesas* a fim de garantir precisão financeira, evitando erros de arredondamento de `float/double`.

**Resiliência do Parser**
- **Encoding flexível**: o parser tenta UTF-8 e faz fallback para **Latin-1 (ISO-8859-1)**, comum nos arquivos da ANS.
- **Mapeamento de colunas**: nomes de colunas são lidos via cabeçalho e mapeados por palavras‑chave/aliases.
- **Limpeza**:
  - **CNPJs duplicados** são consolidados por chave única, evitando duplicidade no resultado final.
  - **Valores negativos ou zerados** podem ser descartados ou normalizados conforme a regra de negócio do consolidado.

**Gestão de Dependências (Maven)**
- **Apache Commons CSV** (leitura robusta de CSV)
- **Jackson** (JSON da API / CSV de saída)
- **Apache POI** (XLSX eventualmente fornecido pela ANS)
- **JUnit** (testes)

**Instruções de Execução**
1. Compilar e empacotar:
   ```bash
   mvn clean install
   ```
2. Executar o JAR gerado:
   ```bash
   java -jar target/ans-demonstracoes-1.0.0.jar
   ```

**Saída**
- `target/ans/consolidado_despesas.csv`
- `target/ans/consolidado_despesas.zip`
