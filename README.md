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

**Teste 2 - Transformação e Validação**
- **Validação de integridade**: o sistema valida o **formato e o dígito verificador do CNPJ** antes de qualquer processamento.
- **Tratamento de inconsistências (trade-off)**: registros com **valores de despesas negativos ou zerados** são **descartados e logados** para evitar distorções nas métricas estatísticas.
- **Estratégia de deduplicação**: a chave de unicidade é `CNPJ + Ano + Trimestre`, preservando a integridade da série temporal.

**Enriquecimento e Agregação**
- **Join de dados (trade-off)**: os dados cadastrais são carregados em **HashMap** para busca **O(1)** durante o join.
- **Cálculos estatísticos**: a **Java Streams API** é usada para calcular **média trimestral** e **desvio padrão**, identificando operadoras com alta volatilidade de custos.
- **Ordenação**: o resultado final é ordenado **por total de despesas (desc)** para destacar maior impacto financeiro.

**Instruções de Execução**
1. Compilar e empacotar:
   ```bash
   mvn clean install
   ```
2. Executar o JAR gerado:
   ```bash
   java -jar target/ans-demonstracoes-1.0.0.jar
   ```

**Instruções de Execução - API (Python)**
1. Instalar dependências:
   ```bash
   pip install -r api/requirements.txt
   ```
2. Iniciar a API:
   ```bash
   python api/main.py
   ```
3. Documentação interativa:
   ```text
   http://localhost:8085/docs
   ```

**Configuração Front-end (Vue.js)**
```javascript
const api = axios.create({
  baseURL: 'http://localhost:8085'
});
```

**Docker (Motivação e Uso)**
- **Por que Docker**: garante ambiente reproduzível (mesmas versões de PostgreSQL e dependências), reduz erros de "funciona na minha máquina" e facilita a avaliação.
- **Isolamento**: o banco de dados roda em container separado, sem interferir com serviços locais.
- **Portabilidade**: permite executar o projeto em qualquer máquina com Docker instalado, sem setup manual do PostgreSQL.

**Execução com Docker**
```bash
docker compose up --build
```

**Observação**
- O `docker-compose.yml` inicia o PostgreSQL e a API, e aplica o schema via `sql/ans_postgresql.sql`.

**Análises Críticas**
- Validação: "Optei por descartar registros com CNPJ inválido ou valores negativos pois, em uma análise de despesas de saúde, dados inconsistentes distorceriam o cálculo do Desvio Padrão e da Média trimestral".
- Performance do Join: "A escolha de um HashMap para os dados cadastrais visa a performance O(1) na busca durante o processamento do arquivo de despesas, que possui volume significativamente maior".
- Desvio Padrão: "O cálculo do desvio padrão foi implementado para identificar operadoras com alta volatilidade em seus gastos, o que é um indicador crítico para a saúde financeira da operação".
- Escolha do PostgreSQL: "Optou-se pelo PostgreSQL pela robustez no suporte a tipos de dados financeiros (DECIMAL) e pela eficiência de suas funções analíticas (Window Functions), essenciais para o cálculo de crescimento e médias trimestrais".
- Normalização: "A abordagem normalizada foi escolhida para evitar a duplicação de dados das operadoras em cada linha de despesa, otimizando o armazenamento e a consistência dos dados".
- Tratamento de Inconsistências SQL: "Durante a importação, strings inválidas em campos numéricos são rejeitadas para manter a integridade dos cálculos estatísticos de média e desvio padrão realizados anteriormente no Java".
- Resiliência na Importação SQL: "Os scripts SQL foram desenhados para suportar a natureza heterogênea dos dados da ANS. Enquanto o arquivo de despesas (consolidado via Java) utiliza UTF-8, o arquivo cadastral original utiliza ISO-8859-1 com delimitador ;. O script dml.sql trata essas diferenças explicitamente para evitar corrupção de caracteres especiais (acentuação) em nomes de operadoras e cidades."
- Segurança e Escalabilidade: "A conexão com o PostgreSQL foi isolada em variáveis de ambiente (.env), seguindo boas práticas de segurança. Além disso, a API implementa paginação nativa e filtros no lado do servidor (Server-side filtering), garantindo que a aplicação permaneça rápida mesmo com o crescimento da base de dados da ANS."
- Modo Offline: "Para garantir a portabilidade da solução em ambientes com restrição de rede (Air-gapped ou Sandbox), o pipeline implementa uma verificação de cache local. Se os arquivos .csv e .zip da ANS já estiverem presentes no diretório, o sistema realiza o processamento imediato sem necessidade de nova conexão."

**Saída**
- `target/ans/consolidado_despesas.csv`
- `target/ans/consolidado_despesas.zip`
- `target/ans/despesas_agregadas.csv` (resultado do Teste 2)

**Entrega**
- O pacote final deve ser um único arquivo `Teste_(seu_nome).zip` contendo **código-fonte**, **CSV(s)** e **documentação**.
