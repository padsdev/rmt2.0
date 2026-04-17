# DEV LOG — Módulo de IA para RMT (GraphCodeBERT)

## Objetivo do Documento

Este documento tem como objetivo registrar, de forma incremental e estruturada, as decisões arquiteturais, problemas enfrentados, soluções adotadas e evolução do desenvolvimento do módulo de Inteligência Artificial integrado à Refactoring and Measurement Tool (RMT).

O conteúdo aqui descrito servirá como base direta para a redação dos Capítulos 1.3 (Metodologia), 3 (Proposta de Solução) e 4 (Análise e Discussão) do Trabalho de Conclusão de Curso.

---

# 1. Arquitetura Geral do Sistema

## 1.1 Arquitetura Base da RMT

O fluxo original da RMT é composto pelos seguintes módulos:

* `project-sync-bff`
* `detection-and-refactoring`
* `metrics-calculator`

A ferramenta realiza:

1. Detecção de candidatos à refatoração
2. Aplicação determinística dos padrões
3. Avaliação por métricas de qualidade

---

## 1.2 Arquitetura Alvo com IA

A nova arquitetura introduz uma camada intermediária no módulo `detection-and-refactoring`:

Extração de entidade → IA (Python) → Decisão → Aplicação (Java)


### Características principais:

* A IA atua **apenas na detecção**
* A aplicação da refatoração permanece **100% determinística**
* Comunicação via **HTTP/JSON**
* Serviço de IA implementado em **Python (FastAPI)**

---

## 1.3 Princípio de Desacoplamento

A arquitetura foi desenhada para garantir:

* Independência tecnológica (Java ↔ Python)
* Evolução separada do modelo de IA
* Segurança na aplicação das refatorações

---

# 2. Decisões Arquiteturais

## 2.1 Uso de Microserviço para IA

**Decisão:** Implementar o módulo de IA como serviço independente.

**Motivação:**

* Desacoplamento da RMT
* Facilidade de experimentação com modelos
* Escalabilidade futura

**Alternativas consideradas:**

* Integração direta em Java → rejeitada (alto acoplamento)
* Uso de APIs externas de LLM → rejeitada (baixa reprodutibilidade experimental)

---

## 2.2 Escolha do Modelo: GraphCodeBERT

**Decisão:** Utilizar GraphCodeBERT.

**Motivação:**

* Considera fluxo de dados (data flow)
* Melhor para compreensão estrutural
* Adequado para classificação semântica

---

## 2.3 Abordagem de Classificação Multilabel

**Decisão:** Tratar detecção como problema multilabel.

**Motivação:**

* Um trecho pode sugerir múltiplos padrões
* Maior flexibilidade experimental

---

## 2.4 Separação Detecção vs Aplicação

**Decisão crítica:**

* IA detecta
* RMT aplica

**Impacto:**

* Segurança garantida
* Comparação direta com heurísticas

---

# 3. Estratégia de Integração Incremental

## 3.1 Modos de Operação

### OFF

* IA desativada
* RMT opera normalmente

### SHADOW

* IA executa paralelamente
* Não influencia decisão

### GATED

* IA influencia decisão
* Com fallback heurístico

---

## 3.2 Justificativa

* Redução de risco
* Permite coleta de dados
* Facilita avaliação experimental

---

# 4. Invariantes Arquiteturais

Os seguintes princípios devem ser preservados durante todo o desenvolvimento:

* Health endpoint nunca deve mentir
* IA não pode aplicar refatoração
* Semântica multilabel deve ser mantida
* Labels devem ser centralizados
* Serviço deve ser desacoplado (sem singletons rígidos)
* Lifecycle deve refletir estado real do sistema
* Dados “operacionais” devem ser reais (sem simulação enganosa)

---

# 5. Lacunas Identificadas no Sistema

## 5.1 Ausência de Serviço de IA

* Nenhum módulo Python existente
* Falta de infraestrutura (Docker, requirements, etc.)

## 5.2 Acoplamento da Detecção e Refatoração

* Execução sequencial direta
* Falta de etapa intermediária

## 5.3 Falta de Pipeline Experimental

* Sem dataset
* Sem avaliação comparativa
* Sem exportação de resultados

---

# 6. Evolução por Milestones

## 6.1 Milestone 1 — Scaffold do Serviço de IA

### Objetivo:

Criar estrutura inicial do serviço Python

### Entregas:

* FastAPI
* Endpoints `/health` e `/analyze`
* Schemas de entrada/saída
* Testes básicos

---

## 6.2 Problemas Identificados no M1

### Problema: Health falso

* Serviço reportava estado saudável sem readiness real

### Problema: Acoplamento ao stub

* Lógica fixa e não extensível

### Problema: Semântica incorreta

* Single-label em vez de multilabel

### Problema: Testes superficiais

* Não validavam HTTP real

---

## 6.3 Correções Aplicadas no M1

* Introdução de lifecycle real
* Desacoplamento do stub
* Ajuste para multilabel
* Uso de TestClient real
* Melhoria na validação de contrato

---

## 6.4 Problemas Residuais após M1

* Lifecycle ainda simplificado
* Falta de simulação de falha de startup
* Campos de evidência ainda stubados
* Limitações de ambiente (Python 3.14 + TestClient)

---

# 7. Preparação para Milestone 2

## 7.1 Objetivo do M2

* Introduzir integração Java → IA
* Sem alterar comportamento da RMT

---

## 7.2 Riscos Identificados

* Falhas de lifecycle não capturadas
* Client HTTP acoplado
* Divergência de contrato

---

## 7.3 Estratégia de Mitigação

* Feature flag (IA desligada por default)
* Fallback completo para heurística
* Validação forte de contrato

---

# 8. Impacto para o TCC

Este desenvolvimento contribui diretamente para:

## Capítulo 3

* Arquitetura do sistema
* Pipeline de IA
* Estratégia de integração

## Capítulo 4

* Análise de decisões
* Problemas enfrentados
* Avaliação comparativa

---

# ➕ 9. Iterações Futuras

## Template para novas entradas

### Iteração X — [Nome do Milestone]

**Objetivo:**
...

**Alterações realizadas:**
...

**Problemas encontrados:**
...

**Soluções aplicadas:**
...

**Riscos residuais:**
...

**Impacto arquitetural:**
...

---

### Iteração M2 — Revisão Arquitetural do Milestone 2

**Objetivo:**
Revisar criticamente a costura Java → serviço de IA em Python, identificando riscos de acoplamento, incompatibilidades com o M3, falhas de fallback e possíveis violações dos invariantes definidos ao final do M1.

**Alterações realizadas:**
Foi adicionada apenas uma nova entrada de revisão ao `dev-log.md`. Nenhuma alteração funcional foi aplicada ao código do M2 nesta etapa.

**Problemas encontrados:**
* A interface `ProjectAiAnalyzer` retorna `void`, e o resultado da IA é descartado após log, o que torna a transição para o M3 mais invasiva do que o desejável.
* `AiAnalyzeRequestFactory` está acoplada às classes concretas das heurísticas atuais (`WeiEtAl2014*` e `ZafeirisEtAl2016Candidate`), em vez de depender de uma representação intermediária mais estável.
* O boundary Java espelha diretamente detalhes do contrato Python (`model`, `evidence`, `top_prediction`), expondo o lado Java a mudanças do scaffold do serviço de IA.
* O fallback atual trata indisponibilidade, erro HTTP, corpo vazio e erro de serialização da mesma forma (`Optional.empty()`), o que é seguro para continuidade, mas fraco para observabilidade e para detectar drift de contrato no M3.
* Ainda existe duplicação cross-stack de labels suportados: o lado Java define `AiPatternLabel` localmente, o que preserva consistência interna do módulo, mas não a centralização do sistema como um todo.

**Soluções aplicadas:**
Nenhuma solução foi aplicada nesta revisão, por decisão de escopo. Os problemas foram apenas registrados para orientar a revisão final do M2 e preparar a entrada no M3.

**Riscos residuais:**
* O M3 tende a exigir mudança de assinatura ou criação de novo boundary para transportar resultado estruturado da IA.
* Há risco de drift silencioso entre o contrato Python e os DTOs Java, especialmente em campos acessórios do scaffold.
* O fallback pode mascarar regressões de integração se erros de contrato forem tratados como simples indisponibilidade.
* A adição de novos detectores ou novas fontes de candidatos tende a exigir mudanças no `AiAnalyzeRequestFactory`, ampliando o acoplamento arquitetural.

**Impacto arquitetural:**
O M2 cumpriu o objetivo de introduzir a costura de integração sem alterar a decisão da RMT, mas ainda não estabeleceu uma fronteira suficientemente estável para o M3. A principal tensão arquitetural está entre uma integração mínima, segura para shadow mode, e a ausência de um resultado de domínio desacoplado para suportar evolução futura sem retrabalho.

---

### Iteração — M2.1 Hardening da Integração

Objetivo:
remover acoplamentos e preparar fronteira IA ↔ RMT

Principais mudanças:
- introdução de modelo de domínio (ProjectAiAnalysis)
- isolamento do contrato HTTP
- desacoplamento das heurísticas
- tratamento semântico de falhas

Impacto:
- habilita evolução para M3 sem retrabalho
- melhora isolamento entre camadas
- prepara coleta de dados experimental

Riscos residuais:
- resultado ainda não integrado ao fluxo
- labels ainda não sincronizados cross-stack

---

### Iteração — M3 Shadow Mode no Fluxo Real

**Objetivo:**  
Executar o módulo de IA dentro do fluxo real da RMT em modo de observação, sem alterar a decisão heurística nem o resultado final da ferramenta.

**Alterações realizadas:**  
- Integração do `ProjectAiAnalyzer` ao fluxo principal de `ProcessRefactorCandidate`.
- Produção de um `ProjectAiAnalysis` por projeto, contendo `CandidateAnalysis` por entidade candidata.
- Introdução de `ProjectAiAnalysisContext` para manter o resultado da IA em memória apenas durante a execução do processamento.
- Inclusão de logs estruturados com eventos `ai_shadow_observation` e `ai_shadow_failure`.

**Problemas encontrados:**  
- Necessidade de tornar o resultado da IA acessível sem acoplar ao domínio `Project`.
- Necessidade de observar a IA no fluxo real sem alterar a semântica determinística da RMT.
- Necessidade de preparar a coleta de dados experimentais sem introduzir persistência prematura.

**Soluções aplicadas:**  
- Uso de um contexto efêmero (`ProjectAiAnalysisContext`) associado ao ciclo de execução do processamento.
- Manutenção da heurística como única fonte de verdade para decisão.
- Estruturação dos logs com campos relevantes para exportação futura, incluindo `trace_id`, `project_id`, `candidate_id`, `entity_id`, `predicted_labels` e `confidence`.

**Riscos residuais:**  
- O contexto de análise é efêmero e local à execução, o que pode não ser suficiente para cenários assíncronos futuros.
- Ainda não existe política explícita para consumo operacional das falhas da IA.
- Os thresholds e níveis de confiança ainda não foram calibrados experimentalmente.
- A compatibilidade de contrato com o serviço Python ainda depende principalmente de testes locais.

**Impacto arquitetural:**  
- Consolidação do `ProjectAiAnalysis` como artefato de execução real.
- Preparação da trilha experimental para comparação entre heurística e IA.
- Preservação do desacoplamento entre observação inteligente e aplicação determinística da refatoração.

---

# Observação Final

Este documento deve ser atualizado a cada nova interação relevante com o processo de desenvolvimento, especialmente ao utilizar o Codex como ferramenta de apoio.

Ele constitui um artefato essencial para garantir rastreabilidade, reprodutibilidade e qualidade científica do trabalho.
