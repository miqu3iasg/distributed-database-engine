---
title: General Description
tags:
    - documentation
    - project
lang: pt-BR # switch language
date: 2026-11-22T12:06:34
id: c23b88a7-efb9-471f-9e1f-3221d2c7cf19
---

# General Description

Um banco de dados distribuído, escrito em Java, que continua funcionando e continua correto mesmo
quando partes dele falham.

---

## 1. O que é o GeoDB

O GeoDB é um banco de dados que não vive em uma única máquina. Ele roda em vários computadores
(chamados de **nós**) que trabalham juntos e, para quem usa, parecem ser um banco só.

Você guarda um dado, lê o dado de volta, e não precisa saber em qual máquina ele está, quantas
cópias existem ou o que aconteceu quando uma das máquinas desligou no meio da madrugada. O sistema
cuida disso.

Por baixo, ele é um **armazenamento de chave e valor ordenado**: você associa uma chave (por exemplo
`usuario:42`) a um valor, e pode buscar por chave ou por faixas de chaves. É o mesmo modelo de base
usado por bancos como CockroachDB, TiKV e Google Spanner. A ideia é que camadas mais sofisticadas
(como SQL) possam ser construídas em cima dele no futuro.

---

## 2. Qual problema ele resolve

Um banco de dados em uma única máquina funciona bem até o dia em que:

1. **A máquina quebra.** Disco falha, energia cai, o sistema operacional trava. Se só existia uma
   cópia dos dados, o serviço para, e dependendo do caso os dados se perdem.
2. **Os dados não cabem mais.** Em algum momento o volume ultrapassa o que um disco ou uma memória
   aguentam.
3. **Pedidos demais chegam ao mesmo tempo.** Uma máquina só tem limite de quantas operações consegue
   atender por segundo.

A resposta óbvia é usar várias máquinas. Mas isso cria um problema novo e muito mais difícil:

> **Como fazer várias máquinas concordarem sobre o que é verdade, quando qualquer uma delas pode
> falhar, ficar lenta ou perder contato com as outras a qualquer momento?**

Se duas máquinas têm cópias do mesmo dado e a rede entre elas cai, qual cópia está certa? Se um
cliente grava um valor e outro lê logo em seguida, ele vê o valor novo ou o antigo? Se uma operação
precisa alterar dados em duas máquinas diferentes e uma delas morre na metade, o que acontece?

O GeoDB existe para responder a essas perguntas de forma **correta**, e não apenas "na maioria das
vezes".

---

## 3. A ideia central, em uma analogia

Imagine um cartório com várias filiais espalhadas pela cidade. Cada filial guarda uma cópia do livro
de registros.

- Se todas as filiais pudessem escrever no livro quando quisessem, os livros logo ficariam
  diferentes entre si.
- Então elas combinam uma regra: **só uma filial, a líder, aceita novos registros**. Antes de
  considerar um registro válido, ela confirma com a maioria das outras filiais que anotou o mesmo.
- Se a líder some (incêndio, falta de energia), as filiais restantes fazem uma **eleição**
  e escolhem uma nova líder, sem perder nenhum registro que já tinha sido confirmado pela maioria.
- Quando a filial que sumiu volta, ela se atualiza copiando o que perdeu.

Esse é, em essência, o funcionamento do GeoDB. Todo o resto do projeto são camadas construídas sobre
essa ideia.

---

## 4. Os mecanismos principais

### 4.1 Replicação: várias cópias de cada dado

Cada dado existe em mais de uma máquina (por padrão, três). Assim, a falha de uma máquina não faz
o dado desaparecer nem tira o serviço do ar.

### 4.2 Consenso (Raft): como as cópias concordam

Ter cópias não basta: é preciso garantir que elas **não divirjam**. O GeoDB usa o **Raft**, um
algoritmo de consenso, que funciona como o cartório da analogia:

- **Um líder** recebe as escritas e as propaga para as demais réplicas.
- Uma escrita só é considerada confirmada quando a **maioria** das réplicas a registrou. Com três
  réplicas, duas bastam.
- Se o líder falha, as réplicas **elegem outro automaticamente**.
- Como toda decisão exige maioria, o sistema nunca fica com "dois líderes ao mesmo tempo" tomando
  decisões conflitantes, mesmo se a rede se dividir ao meio.

O custo dessa garantia é que, se a maioria das réplicas estiver fora do ar ou isolada, o sistema
prefere **parar de aceitar escritas** a arriscar inconsistência. Para um banco de dados, essa
é a escolha certa.

### 4.3 Particionamento (sharding): dividir os dados entre máquinas

Quando os dados crescem, o GeoDB os divide em **faixas contínuas de chaves**, chamadas _ranges_. Por
exemplo, chaves de `a` até `m` formam um range e de `m` até `z` formam outro.

- **Cada range é replicado de forma independente**, com seu próprio grupo de consenso. Isso permite
  que o sistema trabalhe em vários ranges em paralelo.
- **Uma mesma máquina guarda pedaços de vários ranges**, e o sistema distribui esses pedaços para
  equilibrar a carga.
- Um range que fica grande demais é **dividido em dois** automaticamente.
- Existe um **diretório** que sabe qual range está em quais máquinas, para encaminhar cada pedido ao
  lugar certo.

Em vez de uma máquina gigante, o GeoDB cresce adicionando máquinas e redistribuindo ranges entre
elas.

### 4.4 Armazenamento local: como cada nó guarda seus dados

Cada nó precisa gravar dados no disco de forma segura e rápida. O GeoDB usa uma estrutura chamada
**LSM-tree**, a mesma ideia por trás de RocksDB e Cassandra:

- Toda escrita é primeiro anotada em um **log de segurança** no disco (_WAL_). Se a máquina cair,
  o log permite reconstruir o que se perdeu.
- Os dados recentes ficam em memória e são periodicamente despejados em **arquivos ordenados** no
  disco.
- Em segundo plano, esses arquivos são **mesclados e reorganizados** para manter as leituras
  rápidas.

### 4.5 Versões dos dados (MVCC): leitores e escritores sem se atrapalhar

Em vez de sobrescrever um valor, o GeoDB **guarda versões**, cada uma marcada com o momento em que
foi criada. Isso traz um benefício enorme:

- Quem **lê** enxerga uma "foto" consistente do banco em um instante, sem ser bloqueado por quem
  está escrevendo.
- Quem **escreve** cria uma nova versão, sem apagar a anterior de imediato.

Para ordenar esses momentos entre máquinas que têm relógios ligeiramente diferentes, o GeoDB usa um
**relógio híbrido lógico**, que mistura a hora real com um contador, garantindo que a ordem dos
eventos nunca volte atrás.

### 4.6 Transações distribuídas: tudo ou nada, entre várias máquinas

Muitas operações precisam alterar mais de um dado de uma vez. O exemplo clássico é uma transferência
bancária: debitar uma conta e creditar outra. Ou as duas coisas acontecem, ou nenhuma.

Quando essas contas estão em ranges diferentes, em máquinas diferentes, o GeoDB usa um protocolo de
**commit em duas fases**:

1. **Preparar:** cada range envolvido confirma que consegue fazer sua parte.
2. **Confirmar:** só depois que todos disseram sim, a transação é efetivada em todos.

O estado da própria transação também é replicado, então a queda de uma máquina no meio do processo
não deixa a operação "pela metade".

### 4.7 Recuperação de falhas

Falhas são tratadas como parte do funcionamento normal, não como exceção:

- **Nó cai e volta:** lê o seu log local e se atualiza com o que perdeu.
- **Nó novo entra no cluster:** recebe uma cópia dos dados e passa a participar.
- **Nó some para sempre:** as réplicas que ele guardava são recriadas em outras máquinas.
- **Rede se divide:** só o lado que tem a maioria continua aceitando escritas.

---

## 5. O que o GeoDB promete (e o que não promete)

**Promete:**

- **Consistência forte:** depois que uma escrita é confirmada, qualquer leitura posterior enxerga
  esse valor. Para o cliente, o banco se comporta como se existisse uma única cópia (essa
  propriedade se chama _linearizabilidade_).
- **Durabilidade:** dado confirmado não se perde, mesmo com a falha de uma minoria dos nós.
- **Atomicidade em transações:** ou tudo acontece, ou nada acontece.
- **Disponibilidade enquanto houver maioria:** com 3 réplicas, o sistema tolera a falha de 1.

**Não promete:**

- Funcionar quando a maioria das réplicas está indisponível. Nesse caso, ele para para não errar.
- Ser mais rápido que um banco em uma única máquina em operações simples. A coordenação entre
  máquinas tem custo. O ganho está em resiliência e escala.
- Ser um banco SQL completo. O núcleo é chave-valor, e camadas superiores são trabalho futuro.

---

## 6. Como saber se ele está correto

Esse é o ponto mais importante do projeto. Sistemas distribuídos falham de formas raras
e imprevisíveis: uma mensagem chega fora de ordem, uma máquina fica lenta no pior momento, a rede se
divide por meio segundo. Bugs assim passam despercebidos em testes comuns.

Por isso o GeoDB é construído para ser **testado de forma agressiva**:

- **Simulação determinística:** o sistema roda sobre uma rede e um relógio simulados, em que um
  programa de teste controla tudo: atrasa mensagens, perde mensagens, derruba nós, divide a rede.
  Cada cenário é gerado a partir de uma _seed_, então qualquer bug encontrado pode ser **reproduzido
  exatamente**. É a técnica usada por FoundationDB e TigerBeetle.
- **Verificador de linearizabilidade:** o simulador registra tudo o que os clientes fizeram e viram,
  e um verificador confere matematicamente se esse histórico é compatível com um banco que se
  comporta corretamente.
- **Testes de caos:** com o cluster rodando de verdade em containers, derrubamos processos
  e cortamos a rede para ver como ele reage.

O objetivo não é apenas "funciona na minha máquina", e sim **ter evidências de que continua correto
sob falhas**.

---

## 7. Visão do caminho

O projeto é construído em etapas, cada uma funcionando e testada antes da próxima:

1. **Consenso (Raft):** eleição de líder e replicação, sobre rede simulada.
2. **KV replicado:** o banco chave-valor funcionando sobre o consenso, com verificação de
   linearizabilidade.
3. **Armazenamento em disco:** WAL e LSM-tree próprios.
4. **Versões e transações em um range:** MVCC e relógio híbrido lógico.
5. **Particionamento:** ranges, divisão automática e diretório.
6. **Transações distribuídas:** commit em duas fases entre ranges.
7. **Gestão do cluster:** entrada e saída de nós, rebalanceamento.
8. **Polimento:** métricas, benchmarks e documentação das decisões.

---

## 8. Tecnologias

- **Linguagem:** Java 21 ou superior
- **Comunicação entre nós:** gRPC com Protocol Buffers
- **Build:** Maven multi-módulo
- **Testes de desempenho:** JMH
- **Execução do cluster local:** Docker

Tudo gratuito e rodando em uma máquina pessoal, com o cluster simulado por vários processos.

---

## 9. Resumo em uma frase

O GeoDB é um banco de dados que espalha os dados por várias máquinas, mantém cópias sincronizadas
por meio de votação por maioria, divide o trabalho em faixas de chaves e garante que as operações
continuem corretas mesmo quando máquinas e redes falham, e prova isso com testes que simulam o pior
cenário de forma reproduzível.
