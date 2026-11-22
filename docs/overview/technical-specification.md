---
title: Techinical Specification
tags:
    - documentation
related:
    - "[[general-description.md]]"
version: 1.0.0
date: 2026-11-22T12:17:57
id: 1c8671e1-f2c7-42b5-b8c2-2257c4f7e437
---

# GeoDB Techinical Specification

|                     |                                                                            |
| ------------------- | -------------------------------------------------------------------------- |
| **Status**          | Rascunho v0.1                                                              |
| **Linguagem**       | Java (21 como mínimo, 25 LTS recomendado)                                  |
| **Natureza**        | Banco de dados distribuído, transacional, chave-valor ordenado, multi-Raft |
| **Documento irmão** | `GeoDB-visao-geral.md` (explicação de alto nível)                          |

Este documento define **o que será construído, com quais estruturas e por quê**. Onde uma decisão tem alternativas relevantes, ela é marcada como **[ADR]** e deve virar um registro de decisão próprio em `docs/adr/`.

---

## Índice

1. [Escopo, objetivos e não-objetivos](#1-escopo-objetivos-e-não-objetivos)
2. [Modelo de falhas e garantias](#2-modelo-de-falhas-e-garantias)
3. [Stack tecnológica](#3-stack-tecnológica)
4. [Arquitetura geral e módulos](#4-arquitetura-geral-e-módulos)
5. [Princípio de design: núcleo determinístico](#5-princípio-de-design-núcleo-determinístico)
6. [Módulo `geodb-raft`](#6-módulo-geodb-raft)
7. [Log durável do Raft](#7-log-durável-do-raft)
8. [Motor de armazenamento (LSM)](#8-motor-de-armazenamento-lsm)
9. [Camada de range e Multi-Raft](#9-camada-de-range-e-multi-raft)
10. [Relógio híbrido lógico e MVCC](#10-relógio-híbrido-lógico-e-mvcc)
11. [Transações distribuídas](#11-transações-distribuídas)
12. [Controle do cluster, splits e rebalanceamento](#12-controle-do-cluster-splits-e-rebalanceamento)
13. [API, protocolo e cliente](#13-api-protocolo-e-cliente)
14. [Modelo de concorrência do nó](#14-modelo-de-concorrência-do-nó)
15. [Estratégia de testes e verificação](#15-estratégia-de-testes-e-verificação)
16. [Observabilidade](#16-observabilidade)
17. [Benchmarking](#17-benchmarking)
18. [Roadmap com critérios de aceite](#18-roadmap-com-critérios-de-aceite)
19. [Riscos](#19-riscos)
20. [Registro de evidências para o currículo](#20-registro-de-evidências-para-o-currículo)
21. [Lista de ADRs e referências](#21-lista-de-adrs-e-referências)

---

## 1. Escopo, objetivos e não-objetivos

### Objetivos

- **O1.** Armazenar pares chave-valor ordenados (`byte[] → byte[]`) com operações `Get`, `Put`, `Delete`, `Scan(range)` e `CompareAndSet`.
- **O2.** Replicar cada partição com consenso (Raft), tolerando a falha de `f` réplicas em grupos de `2f+1`.
- **O3.** Particionar o espaço de chaves em ranges contíguos, com split automático e rebalanceamento.
- **O4.** Oferecer transações multi-chave e multi-range com atomicidade e isolamento (Snapshot Isolation primeiro, Serializable depois).
- **O5.** Garantir **linearizabilidade** em operações de chave única.
- **O6.** Sobreviver a crash, reinício, partição de rede, atraso, duplicação e reordenação de mensagens, e corrupção de disco detectável.
- **O7.** Ser **verificável**: simulação determinística, checker de linearizabilidade, especificação formal do protocolo central.

### Não-objetivos (explícitos, para proteger o escopo)

- Tolerância a falhas bizantinas.
- SQL, planejador de consultas ou índices secundários (possível extensão futura).
- Replicação geográfica com relógios atômicos (TrueTime). Usamos HLC.
- Multi-tenancy, controle de acesso granular e quotas.
- Competir em desempenho absoluto com RocksDB, CockroachDB ou TiKV. O objetivo é desempenho **medido e explicado**, não recorde.

---

## 2. Modelo de falhas e garantias

### 2.1 Modelo de falhas assumido

| Categoria                                                  | Tratamento                                                                                                                        |
| ---------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------- |
| **Crash-recovery de nó**                                   | Suportado. O nó volta com o estado durável e reentra no cluster.                                                                  |
| **Perda, duplicação, atraso e reordenação de mensagens**   | Suportado. O protocolo é idempotente e tolera qualquer ordem de chegada.                                                          |
| **Partição de rede** (simétrica e assimétrica)             | Suportado. Apenas a partição com maioria progride.                                                                                |
| **Nó lento ("gray failure")**                              | Mitigado com timeouts, CheckQuorum e detecção de líder obsoleto.                                                                  |
| **Torn write e perda de dados não sincronizados em crash** | Suportado via WAL com CRC e `fsync` explícito.                                                                                    |
| **Corrupção silenciosa de bloco**                          | Detectada via CRC32C por bloco e entrada. Não corrigida automaticamente (o nó é isolado e refeito via snapshot de outra réplica). |
| **Relógio com deriva**                                     | Limitado por `max_clock_offset` configurável. O nó se auto-encerra se medir offset acima do limite.                               |
| **Falhas bizantinas**                                      | Fora de escopo.                                                                                                                   |

### 2.2 Garantias oferecidas

| Propriedade            | Escopo                                          | Mecanismo                                        |
| ---------------------- | ----------------------------------------------- | ------------------------------------------------ |
| **Linearizabilidade**  | Operações de chave única                        | Raft + `ReadIndex` (ou lease)                    |
| **Durabilidade**       | Escrita confirmada                              | Maioria com `fsync` antes do ack                 |
| **Atomicidade**        | Transações                                      | Registro de transação como ponto único de commit |
| **Snapshot Isolation** | Transações (fase 4)                             | MVCC + detecção de conflito escrita-escrita      |
| **Serializabilidade**  | Transações (fase 6)                             | Timestamp cache + read refresh                   |
| **Disponibilidade**    | Enquanto houver maioria de cada range envolvido | Eleição automática                               |

> **Nota honesta sobre isolamento.** Snapshot Isolation permite _write skew_. O projeto documenta e **testa de propósito** essa anomalia na fase 4 para depois demonstrar que ela some ao ativar Serializable.

---

## 3. Stack tecnológica

| Área                      | Escolha                                                      | Justificativa                                                             |
| ------------------------- | ------------------------------------------------------------ | ------------------------------------------------------------------------- |
| Linguagem                 | **Java 21+** (recomendado 25 LTS)                            | Records, sealed interfaces, pattern matching, virtual threads, FFM API    |
| Build                     | **Maven multi-módulo** + `maven-enforcer` + BOM próprio      | Reprodutível, regras de dependência verificáveis                          |
| RPC externo e interno     | **gRPC + Protobuf** (`grpc-netty-shaded`)                    | Streaming bidirecional, evolução de schema, padrão de mercado             |
| IO de rede                | Netty (via gRPC)                                             | Event loop eficiente                                                      |
| Armazenamento             | **LSM próprio** sobre `FileChannel` e mmap (`MemorySegment`) | Aprendizado profundo de WAL, memtable, SSTable, compaction                |
| Fallback de storage       | RocksDB via JNI atrás da mesma interface `KvEngine`          | Permite comparar engines e isolar bugs                                    |
| Checksums                 | `java.util.zip.CRC32C`                                       | Intrínseco na JVM, rápido                                                 |
| Compressão (opcional)     | LZ4 (`lz4-java`)                                             | Barata e suficiente                                                       |
| Testes unitários          | JUnit 5, AssertJ                                             | Padrão                                                                    |
| Testes de propriedade     | **jqwik**                                                    | Geração e _shrinking_ de cenários                                         |
| Concorrência              | **JCStress**                                                 | Valida estruturas lock-free e memória                                     |
| Fuzzing                   | **Jazzer**                                                   | Codecs, parsers de WAL e SSTable                                          |
| Mutation testing          | **PIT**                                                      | Mede a qualidade dos testes do Raft                                       |
| Arquitetura               | **ArchUnit** + `forbidden-apis`                              | Impede dependências proibidas e uso de APIs não determinísticas no núcleo |
| Microbenchmarks           | **JMH**                                                      | Padrão para JVM                                                           |
| Histogramas               | **HdrHistogram**                                             | Latência correta (evita _coordinated omission_)                           |
| Métricas                  | **Micrometer** + Prometheus                                  | Padrão                                                                    |
| Tracing                   | **OpenTelemetry**                                            | Rastreio de requisição entre nós                                          |
| Logs                      | SLF4J + Logback (JSON)                                       | Logs estruturados, com `nodeId`, `rangeId`, `term`                        |
| Containers                | Docker + Compose, **Testcontainers**                         | Cluster local reproduzível                                                |
| Injeção de falhas de rede | **Toxiproxy**                                                | Latência, corte e _slow close_ em testes de caos                          |
| Especificação formal      | **TLA+** (TLC model checker)                                 | Verificar o protocolo antes e depois do código                            |
| CI                        | GitHub Actions                                               | Gratuito para repositórios públicos                                       |

Todas as ferramentas são gratuitas e executam em uma máquina pessoal.

---

## 4. Arquitetura geral e módulos

### 4.1 Camadas

```
┌─────────────────────────────────────────────────────────────┐
│ Cliente (geodb-client): roteamento, retry, sessão idempotente│
└───────────────────────────────┬─────────────────────────────┘
                                │ gRPC
┌───────────────────────────────▼─────────────────────────────┐
│ Gateway / TxnCoordinator  (qualquer nó pode ser gateway)     │
├──────────────────────────────────────────────────────────────┤
│ Router: cache de RangeDescriptor + retry em NotLeader/Moved  │
├──────────────────────────────────────────────────────────────┤
│ Store (1 por nó)                                             │
│  ├─ Replica(rangeId=1) ─ RaftNode ─┐                         │
│  ├─ Replica(rangeId=2) ─ RaftNode ─┼─ Raft Log Engine (WAL)  │
│  └─ Replica(rangeId=N) ─ RaftNode ─┘   (group commit)        │
│        │ apply (determinístico)                              │
│        ▼                                                     │
│  MVCC Layer ──► KvEngine (LSM: memtable + SSTables + MANIFEST)│
├──────────────────────────────────────────────────────────────┤
│ Transport (gRPC stream por par de nós, mensagens em lote)    │
└──────────────────────────────────────────────────────────────┘
```

### 4.2 Módulos Maven e regras de dependência

```
geodb/
├── geodb-common      HLC, ids, codecs, Result/erros, config
├── geodb-env         interfaces: Clock, Timers, Rand, Network, Disk (SPI de determinismo)
├── geodb-raft        consenso puro (depende só de common e env)
├── geodb-storage     KvEngine, LSM, WAL, SSTable, MANIFEST, raft-log-engine
├── geodb-mvcc        versões, intents, GC, iteradores MVCC
├── geodb-range       Replica, state machine, split, snapshot, leases
├── geodb-txn         TxnCoordinator, txn records, resolução de intents
├── geodb-cluster     metadados, alocador, rebalanceador, membership
├── geodb-proto       .proto (API externa, Raft transport, internos)
├── geodb-server      composição do nó: gRPC, wiring, bootstrap, admin
├── geodb-client      SDK Java
├── geodb-sim         simulador determinístico + invariantes + nemesis
├── geodb-lincheck    checker de linearizabilidade e anomalias de isolamento
├── geodb-bench       JMH + gerador de carga estilo YCSB
├── geodb-chaos       testes Docker/Toxiproxy
└── specs/tla         especificações TLA+
```

**Regras verificadas por ArchUnit (o build falha se violadas):**

- `geodb-raft` **não** depende de `geodb-proto`, de gRPC, de sistema de arquivos nem de threads. Mensagens Raft são `records` Java; a tradução para Protobuf acontece na borda (`geodb-server`).
- Módulos de núcleo (`raft`, `range`, `txn`, `mvcc`, `cluster`) não podem chamar `System.currentTimeMillis`, `System.nanoTime`, `Thread.sleep`, `new Random()`, `ThreadLocalRandom`, `UUID.randomUUID`, `new Thread`, nem iterar `HashMap/HashSet` com chaves de `hashCode` por identidade. Tudo passa por `geodb-env`.
- Dependências fluem de cima para baixo: `server → txn → range → mvcc → storage`; `raft` é consumido por `range`.

---

## 5. Princípio de design: núcleo determinístico

Toda a viabilidade da simulação depende deste princípio, que precisa existir desde o primeiro commit.

> **O comportamento do núcleo é uma função pura de (estado, entrada, tempo virtual, semente aleatória).**

### 5.1 SPI de ambiente (`geodb-env`)

```java
public interface Clock {
    long nowMillis();            // tempo de parede (pode ter skew em simulação)
    long monotonicNanos();       // tempo monotônico
}

public interface Timers {
    Cancellable schedule(Duration delay, Runnable task);
}

public interface Rand {
    long nextLong();
    int nextInt(int bound);
    // derivável: Rand fork(String label)  → subfluxo determinístico
}

public interface Network {
    void send(NodeId to, Message msg);        // assíncrono, sem garantia de entrega
    void onReceive(MessageHandler handler);
}

public interface Disk {
    FileHandle open(Path p, OpenMode mode);
    // FileHandle: write(pos, bytes), read(pos, len), sync(), truncate(len), size()
    // Semântica: dados não sincronizados podem se perder ou ficar parciais em crash
}
```

Há duas implementações de cada uma:

- **Real:** relógio do sistema, `ScheduledExecutor`, gRPC, `FileChannel`.
- **Simulada:** tempo virtual controlado por uma fila de eventos, rede com faltas injetáveis, disco em memória com modelo de crash.

### 5.2 Disco simulado e semântica de crash

O `SimDisk` mantém, por arquivo, duas visões: **bytes escritos** e **bytes confirmados por `sync()`**. Em um crash simulado, para cada arquivo o simulador escolhe com a seed uma das opções: descartar tudo que não foi sincronizado, manter um prefixo arbitrário, ou manter blocos fora de ordem (torn write em granularidade de setor, 512 B ou 4 KiB). Também injeta, com probabilidade configurável: falha de `fsync`, bit flip em leitura e `ENOSPC`.

Isso é o que permite encontrar bugs de recuperação de WAL que testes comuns jamais exercitam.

### 5.3 Execução da simulação

- Um único thread executa um **laço de eventos** com fila de prioridade ordenada por `(tempo virtual, número de sequência)`.
- O tempo "salta" para o próximo evento; um cenário de horas de tempo simulado roda em segundos.
- Todo cenário é reproduzível por `seed` (`long`). Falhas são registradas no formato `seed + versão do código`, e viram **testes de regressão permanentes** no corpus (`sim/corpus/`).

---

## 6. Módulo `geodb-raft`

### 6.1 Modelo: máquina de estados sem I/O (estilo `etcd/raft`)

O `RaftNode` **não** faz rede, disco ou threads. Ele recebe eventos e devolve um `Ready` descrevendo o que o chamador deve fazer.

```java
public final class RaftNode {
    void tick();                                  // avança relógio lógico (eleição, heartbeat)
    void step(RaftMessage msg);                   // mensagem recebida
    ProposeResult propose(byte[] data);           // cliente quer replicar comando
    ProposeResult proposeConfChange(ConfChange c);
    void readIndex(ReadCtx ctx);                  // leitura linearizável
    void transferLeadership(NodeId target);
    boolean hasReady();
    Ready ready();
    void advance(Ready rd);                       // confirma que o Ready foi processado
}

public record Ready(
    SoftState softState,                // líder, papel (só se mudou)
    HardState hardState,                // term, vote, commit (só se mudou)
    List<Entry> entriesToPersist,       // novas entradas para o WAL
    List<Entry> committedEntries,       // prontas para aplicar à máquina de estados
    List<RaftMessage> messages,         // para enviar
    SnapshotMeta snapshotToInstall,     // se houver
    List<ReadState> readStates          // leituras que podem prosseguir
) {}
```

**Contrato de ordenação do chamador (crítico para correção):**

1. Persistir `hardState` e `entriesToPersist` (com `fsync`).
2. Só então enviar mensagens que dependem dessa persistência (ex.: resposta de seguidor a `AppendEntries`, voto). O **líder** pode enviar `AppendEntries` em paralelo à sua própria persistência.
3. Aplicar `committedEntries` à máquina de estados.
4. Chamar `advance`.

### 6.2 Estado

```
Persistente (sobrevive a crash):   currentTerm, votedFor, log[]
Volátil (todos):                   commitIndex, lastApplied
Volátil (líder):                   nextIndex[], matchIndex[], inflights (janela de pipeline)
```

### 6.3 Mensagens

`RequestVote` / `PreVote`, `RequestVoteResponse`, `AppendEntries`, `AppendEntriesResponse` (com `rejectHint` para recuar `nextIndex` em um salto, em vez de um a um), `InstallSnapshot` (em _chunks_), `Heartbeat`, `HeartbeatResponse`, `TimeoutNow` (transferência de liderança), `ReadIndex` e resposta.

### 6.4 Funcionalidades obrigatórias, em ordem

| #   | Funcionalidade                             | Detalhe                                                                                       |
| --- | ------------------------------------------ | --------------------------------------------------------------------------------------------- |
| 1   | Eleição de líder                           | Timeout randomizado por `Rand`; mínimo de um `electionTimeout` sem ouvir líder                |
| 2   | Replicação de log                          | Verificação de consistência `(prevLogIndex, prevLogTerm)`; truncamento de conflitos           |
| 3   | Regra de commit                            | Líder só confirma por contagem de maioria entradas **do seu próprio termo** (§5.4.2 do paper) |
| 4   | **No-op ao assumir**                       | Novo líder propõe entrada vazia para firmar commit de entradas anteriores                     |
| 5   | **PreVote**                                | Evita que um nó isolado incremente o termo e derrube um líder saudável ao voltar              |
| 6   | **CheckQuorum**                            | Líder que não ouve maioria por um `electionTimeout` renuncia                                  |
| 7   | `ReadIndex`                                | Leitura linearizável sem escrever no log (confirmação por heartbeat de maioria)               |
| 8   | Snapshots e compactação                    | `InstallSnapshot` para seguidores muito atrasados                                             |
| 9   | Pipelining e controle de fluxo             | Janela de mensagens em voo por seguidor, limite de bytes                                      |
| 10  | Batching                                   | Agrupar propostas e entradas por mensagem                                                     |
| 11  | Learners                                   | Réplicas não votantes que recebem log, usadas para ingresso seguro de nós                     |
| 12  | Mudança de membros por **joint consensus** | `C_old,new` → `C_new`, com degrau intermediário de _single-server change_ **[ADR]**           |
| 13  | Transferência de liderança                 | `TimeoutNow` após o alvo estar atualizado                                                     |
| 14  | Lease reads (otimização)                   | Leitura local do líder durante lease, dependente de `max_clock_offset` **[ADR]**              |

### 6.5 Invariantes verificados em tempo de simulação

Os invariantes do Raft viram _assertions_ executadas a cada evento do simulador:

- **Election Safety:** no máximo um líder por termo.
- **Leader Append-Only:** um líder nunca sobrescreve nem apaga entradas do seu log.
- **Log Matching:** se dois logs têm uma entrada com mesmo índice e termo, então os logs são idênticos até aquele ponto.
- **Leader Completeness:** toda entrada confirmada num termo aparece nos logs de líderes de termos posteriores.
- **State Machine Safety:** dois nós nunca aplicam comandos diferentes no mesmo índice.

### 6.6 Especificação TLA+

`specs/tla/Raft.tla` é adaptada da especificação pública do Raft e estendida com PreVote e CheckQuorum. O TLC verifica os invariantes acima para clusters pequenos (3 a 5 nós, termos e log limitados). O código Java referencia a spec em comentários por _ação_ (`/* TLA: HandleAppendEntriesRequest */`). Divergências entre spec e código são tratadas como bug de um dos dois.

---

## 7. Log durável do Raft

### 7.1 Decisão **[ADR]**: engine de log dedicado e compartilhado

Um **único log append-only por Store**, compartilhado entre todos os ranges daquele nó, separado do motor LSM de dados. Motivos:

- O log Raft tem padrão de acesso sequencial e de truncamento; usar LSM para ele gera amplificação de escrita desnecessária.
- Um único arquivo ativo permite **group commit**: dezenas de ranges fazem seus `Ready` e o Store faz **um** `fsync` para todos.

### 7.2 Formato em disco

```
Segmento: raft-<firstSeq>.log   (tamanho alvo 64 MiB, pré-alocado)

Registro:
┌──────────┬─────────┬──────────┬───────┬───────┬──────┬─────────┐
│ len (u32)│ crc32c  │ rangeId  │ index │ term  │ type │ payload │
└──────────┴─────────┴──────────┴───────┴───────┴──────┴─────────┘
type ∈ {ENTRY, HARD_STATE, TRUNCATE_SUFFIX, SNAPSHOT_MARK}
```

- O CRC cobre todo o registro. Na recuperação, o log é lido sequencialmente e **truncado no primeiro registro inválido** (trata torn writes).
- **Índice em memória** por range: `rangeId → (firstIndex, lastIndex, offsets[])`, reconstruído na inicialização.
- **Truncamento de prefixo:** segmentos inteiros são apagados quando todos os ranges neles já passaram do `compactedIndex`.
- **Truncamento de sufixo** (conflito de líder): registro `TRUNCATE_SUFFIX` e ajuste do índice, sem reescrever disco.
- **Política de fsync:** `fsync` por lote (group commit), configurável por tempo máximo de espera (ex.: até 200 µs) e tamanho de lote.

### 7.3 Atomicidade entre log e dados aplicados

O `appliedIndex` de cada range é gravado **no mesmo `WriteBatch` atômico** do KvEngine que contém os efeitos do comando. Assim, após um crash, a máquina de estados sabe exatamente de onde retomar a aplicação do log, e reaplicar nunca duplica efeitos.

---

## 8. Motor de armazenamento (LSM)

### 8.1 Interface

```java
public interface KvEngine extends AutoCloseable {
    void write(WriteBatch batch, WriteOptions opts);   // atômico
    byte[] get(byte[] key, ReadOptions opts);
    KvIterator iterator(byte[] lower, byte[] upper, ReadOptions opts);
    Snapshot snapshot();                                // visão consistente por seqnum
    void ingest(List<Path> sstables);                   // carga em massa (snapshots de range)
    EngineStats stats();
}
```

Implementações: `LsmEngine` (própria) e `RocksDbEngine` (JNI, comparativo).

### 8.2 Componentes

| Componente              | Projeto                                                                                                                                                   |
| ----------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **WAL do LSM**          | Segmentado, CRC32C por registro, recuperação por _replay_ até o primeiro registro inválido                                                                |
| **Memtable**            | _Skiplist_ concorrente (uma escritora, muitas leitoras), alocação em arena (`ByteBuffer` off-heap) para reduzir pressão de GC                             |
| **Imutáveis**           | Memtable cheia vira imutável e é _flushada_ em background para L0                                                                                         |
| **SSTable**             | Blocos de dados de 4 KiB com **compressão de prefixo** e pontos de reinício; bloco de índice; **Bloom filter** por arquivo; footer com magic number e CRC |
| **Cache de blocos**     | LRU segmentado, limitado por bytes; contadores de hit/miss expostos                                                                                       |
| **MANIFEST**            | Log de `VersionEdit` (arquivos adicionados/removidos por nível); atomicidade da troca de versão; `CURRENT` aponta para o MANIFEST válido                  |
| **Compaction**          | _Leveled_: L0 por sobreposição, L1+ com arquivos disjuntos e razão de tamanho 10x; escolha de arquivo por pontuação de nível                              |
| **Iteradores**          | _k-way merge_ por _heap_ sobre memtables e SSTables; filtro por _sequence number_ do snapshot                                                             |
| **Controle de escrita** | _Write stalls_ e _slowdown_ quando L0 acumula arquivos demais (back-pressure para cima)                                                                   |
| **Snapshots**           | Pin de _sequence number_; arquivos só são removidos quando nenhum snapshot os referencia                                                                  |

### 8.3 Codificação de chaves

Chaves internas: `[prefixo de espaço (1B)] [user_key] [separador] [seqnum | tipo]`. O motor é agnóstico a MVCC; MVCC é uma camada acima (ver §10), que codifica a versão **dentro** da chave de usuário.

Espaços de chave reservados (prefixos de 1 byte):

```
0x01  dados de usuário (MVCC)
0x02  metadados de range (RangeDescriptor, appliedIndex, lease, truncatedState)
0x03  registros de transação
0x04  sistema/cluster
```

### 8.4 Testes específicos do storage

- **Modelo de referência:** um `TreeMap` em memória; testes de propriedade (jqwik) executam sequências aleatórias de `put/delete/get/scan/snapshot/flush/compact/crash+recover` e comparam com o modelo.
- **Crash em todo ponto:** o `SimDisk` interrompe em cada `write/sync` e a recuperação é conferida contra o último estado confirmado.
- **Fuzzing (Jazzer)** de leitores de WAL, SSTable e MANIFEST contra arquivos corrompidos.

---

## 9. Camada de range e Multi-Raft

### 9.1 Conceitos

```java
record RangeDescriptor(
    RangeId id,
    byte[] startKey,            // inclusivo
    byte[] endKey,              // exclusivo
    List<ReplicaDescriptor> replicas,   // (nodeId, storeId, replicaId, tipo: VOTER | LEARNER)
    long generation             // incrementa a cada mudança (split, merge, conf change)
) {}
```

- Um **Store** corresponde a um disco/diretório de dados e hospeda muitas **Replicas** de ranges diferentes.
- Cada **Replica** possui seu `RaftNode`, sua máquina de estados e referências ao Raft Log Engine e ao `KvEngine` do Store.

### 9.2 Eficiência do Multi-Raft

| Técnica                          | Efeito                                                                                                                   |
| -------------------------------- | ------------------------------------------------------------------------------------------------------------------------ |
| **Heartbeats coalescidos**       | Um heartbeat por par de nós por tick, carregando a lista de ranges; evita tempestade de mensagens com milhares de ranges |
| **Group commit no log engine**   | Um `fsync` para todos os ranges (§7)                                                                                     |
| **Ranges "frios" (quiescência)** | Ranges sem tráfego deixam de enviar ticks e heartbeats até serem acordados                                               |
| **Transporte multiplexado**      | Um stream gRPC bidirecional por par de nós, com mensagens de vários ranges em lote                                       |

### 9.3 Máquina de estados de range

A aplicação de uma entrada committed é **determinística**: o líder define timestamps, ids e demais valores _antes_ de propor. A aplicação **nunca** consulta relógio, aleatoriedade ou estado local não replicado.

Comandos replicados (`sealed interface RaftCommand`):

```
Write(batch de puts/deletes MVCC, resultado esperado)
Intent(Put/Delete com TxnMeta)
ResolveIntent(txnId, status, commitTs)
TxnRecordUpdate(txnId, status, ...)
Split(splitKey, newRangeId, ...)
Merge(...)                   // opcional, fase 7
ChangeReplicas(conf change)
TruncateLog(newCompactedIndex)
LeaseRequest(...)            // se usado leases
GcRange(threshold)
NoOp
```

### 9.4 Idempotência de cliente

Cada cliente abre uma **sessão** (`clientId`) e numera suas requisições (`seq`). A máquina de estados mantém uma tabela de **deduplicação** `clientId → (últimoSeq, resposta)` (com expiração por sessão). Um _retry_ após _timeout_ de um comando que na verdade foi aplicado devolve a resposta original, e não reaplica. Isso dá semântica _exactly-once_ em operações de escrita não idempotentes (ex.: `CompareAndSet`).

### 9.5 Leituras

Modos, em ordem de implementação:

1. **ReadIndex:** o líder confirma liderança por heartbeat de maioria, espera `appliedIndex ≥ readIndex` e lê localmente. Linearizável sem depender de relógios.
2. **Lease reads (otimização) [ADR]:** o líder serve leituras locais enquanto detém uma lease, com duração menor que `electionTimeout − max_clock_offset`. A segurança depende do limite de deriva; o simulador injeta _skew_ de relógio acima e abaixo do limite para provar o comportamento.
3. **Follower reads (stretch):** leitura em seguidor com _closed timestamp_, retornando dados de snapshot (sem linearizabilidade, com staleness limitada e declarada).

### 9.6 Snapshots de range

- **Geração:** iterador sobre o `KvEngine.snapshot()` restrito ao span do range, serializado em _chunks_ (ex.: 1 MiB) com CRC.
- **Envio:** stream gRPC com controle de fluxo e limite de taxa (para não saturar produção).
- **Instalação:** na réplica receptora, os chunks viram SSTables que são **ingeridas** diretamente no LSM (`ingest`), sem passar por memtable.
- **Gatilho:** seguidor cujo `nextIndex` caiu abaixo do `compactedIndex` do líder.

---

## 10. Relógio híbrido lógico e MVCC

### 10.1 Hybrid Logical Clock (HLC)

Timestamp de 64 bits (ou `record Hlc(long wallMillis, int logical)` com serialização compacta):

```
 63          16 15          0
┌──────────────┬─────────────┐
│ wall (48 b)  │ logical(16b)│   ordenável como long sem sinal
└──────────────┴─────────────┘
```

Regras:

- **Evento local/envio:** `wall' = max(wall, physicalNow)`; se `wall' == wall` então `logical++`, senão `logical = 0`.
- **Recebimento de `m`:** `wall' = max(wall, m.wall, physicalNow)`; ajusta `logical` conforme qual `wall` venceu.
- **Proteção:** se `m.wall − physicalNow > max_clock_offset`, a mensagem é rejeitada, e o nó se auto-encerra se detectar o problema de forma persistente.

O HLC é injetado por `Clock` e portanto simulável com _skew_ e saltos.

### 10.2 Codificação de versões

Chave MVCC física:

```
[0x01] [user_key (escapada)] [0x00 0x01] [~ts (8 B, big-endian)]
```

O timestamp é **invertido** (`~ts`) para que versões mais novas ordenem **antes** das antigas: um `Seek(user_key, readTs)` encontra a versão visível em uma única busca.

O escape de `user_key` (byte `0x00` duplicado) garante que o separador seja inequívoco e preserve a ordem lexicográfica.

Tipos de valor: `VALUE`, `TOMBSTONE`, `INTENT` (versão provisória com `TxnMeta`, sempre sob o ts `0` reservado, "uma intent por chave", como no CockroachDB).

### 10.3 Operações MVCC

```java
interface MvccStore {
    MvccValue get(byte[] key, Hlc readTs, Txn txn);                 // pode lançar WriteIntentError
    void put(byte[] key, byte[] value, Hlc writeTs, Txn txn);       // lança WriteTooOld / WriteIntentError
    MvccIterator scan(byte[] start, byte[] end, Hlc readTs, Txn txn, ScanOptions o);
    void resolveIntent(byte[] key, TxnId txn, TxnStatus status, Hlc commitTs);
    void gc(byte[] start, byte[] end, Hlc threshold);
}
```

### 10.4 Garbage collection

- `gcThreshold` por range, derivado de um TTL configurável e do menor `readTs` de transações ativas.
- Versões abaixo do limiar são removidas por um **filtro de compaction**, mais uma passagem explícita (`GcRange`) replicada, para que todas as réplicas descartem o mesmo conjunto.
- Leituras abaixo do `gcThreshold` retornam erro `BatchTimestampBeforeGCError`.

---

## 11. Transações distribuídas

O desenho é inspirado em CockroachDB (e em Percolator), com **o registro de transação como ponto único e atômico de commit**.

### 11.1 Componentes

- **TxnCoordinator** (no nó gateway): mantém o estado da transação, `readTs`, `writeTs` (que pode avançar), conjunto de chaves escritas (_lock footprint_), conjunto de leituras (para _refresh_), e envia _heartbeats_ ao registro.
- **Transaction Record:** vive no range da **primeira chave escrita** (a _âncora_), replicado por Raft. Estados: `PENDING → COMMITTED | ABORTED`, com campos `lastHeartbeat`, `writeTs`, `priority`, lista de intents (_in-flight writes_).
- **Write Intent:** versão provisória gravada na própria chave, contendo `TxnMeta {id, anchorKey, epoch, writeTs, priority}`.

### 11.2 Fluxo de escrita e commit

```
1. begin: txnId (determinístico via Rand), readTs = hlc.now(), priority
2. write(k, v): enviar Intent(k) ao range de k via Raft
      · primeira escrita cria o TxnRecord(PENDING) no range da âncora
      · se existir versão commitada com ts > readTs → WriteTooOld
            · SI: aborta e o cliente tenta de novo
            · Serializable: tenta empurrar writeTs e fazer read refresh
      · se existir intent de outra txn → resolver conflito (11.4)
3. commit:
      a. (Serializable) refresh das leituras de readTs até writeTs; se falhar → retry
      b. escrever TxnRecord = COMMITTED(commitTs = writeTs) no range da âncora  ← PONTO DE COMMIT
         (uma única escrita Raft; atômica)
      c. responder sucesso ao cliente
      d. em background: ResolveIntent(commitTs) em cada range tocado
4. abort: TxnRecord = ABORTED; intents são removidas (de forma preguiçosa ou ativa)
```

A transação **é considerada efetivada no instante em que o registro vira COMMITTED**, mesmo que nenhuma intent tenha sido resolvida ainda. Qualquer leitor que encontre uma intent consulta o registro para saber seu destino real.

**Otimização futura (stretch): parallel commits**, com estado `STAGING` para commit em uma única rodada de consenso (RTT).

### 11.3 Isolamento

| Nível                           | Mecanismo                                                                          | Anomalias remanescentes           |
| ------------------------------- | ---------------------------------------------------------------------------------- | --------------------------------- |
| **Snapshot Isolation** (fase 4) | Leitura em `readTs` fixo; conflito escrita-escrita aborta (_first-committer-wins_) | Write skew                        |
| **Serializable** (fase 6)       | + **Timestamp Cache** por range e _read refresh_                                   | Nenhuma (verificado pelo checker) |

**Timestamp Cache:** estrutura em memória por range (árvore de intervalos ou _skiplist_) registrando o maior timestamp de leitura de cada chave/intervalo. Uma escrita com `writeTs ≤ maxReadTs` da chave é **empurrada** para `maxReadTs + 1`. Em mudança de líder, o _low-water mark_ do cache é elevado para o início da nova lease/termo, o que preserva a segurança (conservadoramente).

**Uncertainty interval:** como relógios não são perfeitos, leituras com `readTs` tratam valores no intervalo `(readTs, readTs + max_clock_offset]` como incertos; se encontrados, a leitura reinicia com timestamp maior. Isso evita anomalias de "causalidade inversa" entre relógios.

### 11.4 Resolução de conflitos (leitor ou escritor encontra intent alheia)

```
intent pertence a txn T, com TxnRecord R:
  R = COMMITTED   → resolver intent como commitida, continuar
  R = ABORTED     → remover intent, continuar
  R = não existe  → se intent é "velha" (timeout de criação), ABORTAR T (pusher cria registro ABORTED)
  R = PENDING:
        · heartbeat expirado (> txnLivenessTimeout) → ABORTAR T
        · senão → PUSH:
              leitor com readTs ≥ intent.ts: empurrar writeTs de T acima de readTs
              (T seguirá, mas terá de re-avaliar; no Serializable refaz refresh)
              escritor: aguardar T numa fila (lock table) ou abortar o de menor prioridade
```

**Deadlocks:** detectados por **grafo de espera distribuído** (pushes registram arestas `waiter → pushee`; consultas de `QueryTxn` propagam dependências). Ciclo detectado → aborta a de menor prioridade. Alternativa mais simples na primeira versão: _timeouts_ + prioridade (**[ADR]**).

### 11.5 Falha do coordenador

- O coordenador envia **heartbeats** ao registro. Se parar, outras transações que esbarrem nas intents abortam a txn após `txnLivenessTimeout`.
- Um job de **recuperação de intents órfãs** (varredura periódica de registros `PENDING` expirados e intents antigas) limpa o resíduo.
- O cliente que perde a conexão com o gateway durante o commit recebe `AmbiguousResultError`, e pode consultar o estado pelo `txnId` (o registro é a verdade).

### 11.6 Verificação

- Testes de **write skew** (SI deve permitir; Serializable deve bloquear).
- Teste **bank transfer**: N contas, transferências concorrentes sob falhas, invariante "soma constante".
- **Teste de append de listas** (estilo Jepsen/Elle): transações que fazem `append` e `read` em listas; o checker reconstrói o grafo de dependências (ww, wr, rw) e busca **ciclos** que caracterizam G0, G1a/b/c, G-single e G2.

---

## 12. Controle do cluster, splits e rebalanceamento

### 12.1 Endereçamento de ranges **[ADR]**

- **Range 0 / system range**: um grupo Raft especial com o **mapa de ranges** (`startKey → RangeDescriptor`) e o registro de nós/stores. Começa como range único; evolui para endereçamento em dois níveis (`meta1`/`meta2`) apenas se necessário.
- Cada cliente/gateway mantém um **cache de roteamento** (árvore ordenada por `startKey`). Entradas obsoletas são corrigidas por resposta `RangeKeyMismatch` / `NotLeaseholder`, que carrega o descriptor atualizado ou a dica do novo líder.

### 12.2 Bootstrap e descoberta

1. O primeiro nó inicializa o cluster, criando o range 0 e o range inicial `[−∞, +∞)`.
2. Nós seguintes entram com `--join=<endereço>`, recebem um `nodeId` e passam a ser candidatos a réplicas.
3. Descoberta por **gossip simples** (ou via consulta ao range 0): lista de nós, capacidade e carga por store.

### 12.3 Split de range

Disparado quando o range excede `maxRangeBytes` (ex.: 64 MiB) ou `maxQPS`:

1. O líder escolhe `splitKey` (mediana aproximada por amostragem do SSTable).
2. Propõe o comando replicado `Split(splitKey, newRangeId)`.
3. **Na aplicação**, atomicamente: o descriptor da esquerda é encurtado, o da direita é criado com as **mesmas réplicas**, e as réplicas da direita são inicializadas como novos grupos Raft com estado inicial vazio e `appliedIndex` inicial.
4. **Nenhum dado é copiado**: o `KvEngine` do Store é um só, e o range é apenas um intervalo de chaves.
5. Estado transferido: timestamp cache (usando o maior do range pai), tabela de deduplicação e informações de lease.

O desafio de correção está em _races_ (uma réplica atrasada recebe mensagens do range direito antes de aplicar o split). Tratamento: mensagens para range desconhecido são retidas/descartadas com resposta `RangeNotFound`, e a réplica atrasada recebe via **snapshot** do grupo à direita se necessário.

### 12.4 Rebalanceamento

O **alocador** (executado no líder do range 0, ou por range líder, **[ADR]**) avalia periodicamente:

- Número de réplicas por store (alvo: equilibrar).
- Número de líderes por store (alvo: equilibrar; usar `TransferLeadership`).
- Restrições de diversidade (réplicas de um mesmo range em nós distintos; opcionalmente em "zonas").
- Réplicas faltantes (nó morto por `> deadNodeTimeout`): **reparo** criando nova réplica em outro nó.

**Mover uma réplica** é uma sequência segura:

```
1. adicionar novo nó como LEARNER                (recebe snapshot + log, sem votar)
2. quando atualizado → promover a VOTER          (joint consensus)
3. remover réplica antiga                        (joint consensus)
4. GC dos dados locais da réplica removida
```

Nunca se remove a réplica antiga antes de a nova estar votando e atualizada. O invariante "sempre existe maioria de réplicas atualizadas" é verificado na simulação.

### 12.5 Merge (opcional, fase 7+)

Reunir ranges adjacentes pequenos exige coordenar dois grupos Raft; implementar apenas se o tempo permitir. É um excelente tópico de ADR de "o que não fiz e por quê".

---

## 13. API, protocolo e cliente

### 13.1 API gRPC externa (esboço)

```proto
service GeoDb {
  rpc Batch(BatchRequest) returns (BatchResponse);          // Get/Put/Delete/Scan/CAS agrupados
  rpc BeginTxn(BeginTxnRequest) returns (BeginTxnResponse);
  rpc TxnBatch(TxnBatchRequest) returns (TxnBatchResponse);
  rpc CommitTxn(CommitTxnRequest) returns (CommitTxnResponse);
  rpc AbortTxn(AbortTxnRequest) returns (AbortTxnResponse);
  rpc Scan(ScanRequest) returns (stream ScanChunk);
  rpc QueryTxn(QueryTxnRequest) returns (QueryTxnResponse);   // estado ambíguo
}

message RequestHeader {
  bytes  client_id   = 1;
  uint64 seq         = 2;       // idempotência
  Hlc    timestamp   = 3;
  Consistency level  = 4;       // LINEARIZABLE | STALE_BOUNDED
  uint64 range_gen   = 5;       // geração do descriptor que o cliente assume
}
```

Erros tipados e _retryable_: `NotLeaseholder{hint}`, `RangeKeyMismatch{newDescriptor}`, `WriteIntentError`, `TxnAborted`, `TxnRetry{reason}`, `AmbiguousResult`, `ServerBusy`.

### 13.2 API interna

```proto
service RaftTransport {
  rpc Stream(stream RaftBatch) returns (stream RaftBatch);  // multiplexa ranges
}
service SnapshotTransfer {
  rpc Send(stream SnapshotChunk) returns (SnapshotAck);
}
service Internal {
  rpc Gossip(...); rpc ResolveIntent(...); rpc PushTxn(...); rpc AdminSplit(...);
}
```

### 13.3 Cliente Java

- Cache de roteamento, _retry_ com _backoff_ exponencial com jitter, e **orçamento de tentativas** (_retry budget_).
- Sessão idempotente (`clientId`, `seq`).
- API fluente e transacional:

```java
try (GeoClient c = GeoClient.connect("localhost:7001")) {
    c.put("k".getBytes(), "v".getBytes());
    c.txn(IsolationLevel.SERIALIZABLE, tx -> {
        long a = tx.getLong("conta:a");
        long b = tx.getLong("conta:b");
        tx.putLong("conta:a", a - 10);
        tx.putLong("conta:b", b + 10);
    }); // o lambda é reexecutado automaticamente em TxnRetry
}
```

### 13.4 Evolução de protocolo

Campos Protobuf sempre aditivos; `reserved` para campos removidos; versão de protocolo negociada na abertura da conexão; comandos Raft versionados por _cluster version_ (um nó não aplica comando de versão que ainda não conhece).

---

## 14. Modelo de concorrência do nó

Princípio: **estado de um range é acessado por um único thread por vez**, eliminando locks de domínio e preservando determinismo.

```
 gRPC (Netty event loops)
        │  requisições
        ▼
 Virtual threads (aguardam resultados, sem bloquear threads de plataforma)
        │  propõe / enfileira
        ▼
 ┌───────────────────────────────────────────────┐
 │ Raft Workers (N threads, N ≈ nº de cores)     │   ← "batch system" estilo TiKV
 │  range i vai para o worker (hash(i) % N)       │
 │  cada worker: mailbox por range, tick, step,   │
 │  ready → persistir → enviar                    │
 └───────┬────────────────────────┬──────────────┘
         │ Ready (entradas)       │ committed
         ▼                        ▼
 Log Writer (1 thread,       Apply Workers (M threads)
 group commit + fsync)       aplicam por range, em ordem
```

Regras:

- **Virtual threads** apenas para o lado "cliente" (espera de respostas). Código com `synchronized` e _pinning_ é evitado; usa-se `ReentrantLock`.
- O **ciclo de vida** de um range (criar, destruir, split) é serializado no worker dono dele.
- Estruturas compartilhadas (cache de blocos, métricas, cache de roteamento) são seguras por construção (_lock striping_, `LongAdder`, CAS) e testadas com **JCStress**.
- **Back-pressure** de ponta a ponta: filas limitadas em cada estágio, e rejeição com `ServerBusy` quando saturadas (melhor que acumular latência).

Em simulação, todos esses "workers" colapsam em um **único thread determinístico** com ordem de eventos escolhida pela seed.

---

## 15. Estratégia de testes e verificação

Esta é a parte que define o nível do projeto.

### 15.1 Pirâmide

| Nível                              | Ferramenta         | O que cobre                                           |
| ---------------------------------- | ------------------ | ----------------------------------------------------- |
| Unitários                          | JUnit 5            | Estruturas isoladas (HLC, skiplist, SSTable, codecs)  |
| Propriedade                        | jqwik              | Modelo de referência vs implementação (storage, MVCC) |
| **Simulação determinística**       | `geodb-sim`        | Cluster completo sob falhas, reproduzível por seed    |
| **Linearizabilidade e isolamento** | `geodb-lincheck`   | Valida históricos gerados pela simulação e pelo caos  |
| Concorrência                       | JCStress           | Memória e visibilidade em estruturas compartilhadas   |
| Fuzzing                            | Jazzer             | Parsers de WAL, SSTable, MANIFEST, protobuf           |
| Caos real                          | Docker + Toxiproxy | Cluster de processos reais                            |
| Mutation                           | PIT                | Avalia se os testes do Raft pegam bugs injetados      |
| Formal                             | TLA+ / TLC         | Protocolo de consenso (e possivelmente de transação)  |

### 15.2 Simulador (`geodb-sim`)

- **Cluster simulado:** 3 a 7 nós, com `SimClock` (por-nó, com skew e saltos), `SimNetwork` e `SimDisk`.
- **Nemesis** (gerador de falhas, dirigido pela seed): crash e restart de nós, partições (simétricas, assimétricas, ponte entre grupos), atraso e jitter, duplicação e reordenação, _clock skew_, falhas de disco (§5.2), lentidão ("gray failure") e mudanças de membership.
- **Workloads:** registro simples (`read/write/cas`), contas bancárias, lista com `append`, _scans_ concorrentes com splits.
- **Verificadores a cada evento:** invariantes do Raft (§6.5), invariantes de range (descriptors consistentes, sem sobreposição de spans), consistência entre réplicas ao fim (todas convergem para o mesmo estado).
- **Detecção de _liveness_:** após cessarem as falhas, o cluster deve voltar a progredir em tempo limitado (senão é _livelock_ ou bug).
- **Campanhas:** `mvn -pl geodb-sim exec:java -Dseeds=0..1000000`. Seeds que falham são salvas e viram regressão. O CI noturno executa um lote grande de seeds novas.
- **Cobertura guiada:** _buggify_ (como no FoundationDB): pontos do código ativam atrasos/falhas raras extras apenas em simulação (`if (Buggify.fire("snapshot.delay")) ...`).

### 15.3 Checker de linearizabilidade (`geodb-lincheck`)

- Entrada: histórico de operações `(clientId, tipo, args, invocação, resposta, resultado)`, em que operações sem resposta (timeout) são tratadas como **indeterminadas** (podem ter ou não ocorrido, e com fim em +∞).
- Algoritmo: busca por **Wing & Gong com otimizações de Lowe** e **memoização** do par `(conjunto linearizado em bitset, estado do modelo)`; decomposição **por chave** (P-compositionality), que reduz drasticamente o custo.
- Modelos: registro (`get/put/cas`), e depois transações (para SI/Serializable, via checker de ciclos estilo **Elle**).
- Saída: ao falhar, produz o **menor sub-histórico** que viola, e uma visualização (HTML com linha do tempo) para depuração.

### 15.4 Caos com processos reais (`geodb-chaos`)

- Cluster de 3 a 5 containers (Docker Compose / Testcontainers).
- Toxiproxy entre os nós para latência, perda e corte de conexão.
- `kill -9`, `SIGSTOP/SIGCONT` (pausa de processo, simulando GC longo) e preenchimento de disco.
- Carga com clientes reais, histórico gravado, e verificação com o mesmo `geodb-lincheck`.

### 15.5 Metas de qualidade (gates de CI)

- Build verde com ArchUnit e forbidden-apis.
- Suite de regressão de seeds: 100% verde.
- Campanha de simulação de N seeds novas sem violações (N crescente por fase).
- Cobertura de linhas e _mutation score_ mínimos no `geodb-raft` (ex.: ≥ 85% de mutantes mortos).
- TLC sem contraexemplos nas configurações definidas.

---

## 16. Observabilidade

### 16.1 Métricas (Micrometer → Prometheus)

| Grupo      | Exemplos                                                                                                                                         |
| ---------- | ------------------------------------------------------------------------------------------------------------------------------------------------ |
| Raft       | `raft_term`, `raft_is_leader`, `raft_commit_index`, `raft_applied_index`, `raft_election_count`, `raft_proposals_dropped`, `raft_append_latency` |
| Log engine | `wal_fsync_latency`, `wal_group_commit_batch_size`, `wal_bytes_written`                                                                          |
| LSM        | `lsm_memtable_bytes`, `lsm_l0_files`, `lsm_compaction_bytes`, `lsm_write_stall_seconds`, `lsm_block_cache_hit_ratio`, `lsm_write_amplification`  |
| Range      | `range_count`, `range_bytes`, `range_split_count`, `range_snapshot_bytes_sent`                                                                   |
| Txn        | `txn_commits`, `txn_aborts{reason}`, `txn_retries`, `txn_push_count`, `txn_commit_latency`                                                       |
| API        | `api_latency{method}` (histogramas), `api_errors{type}`, `inflight_requests`                                                                     |
| HLC        | `hlc_max_offset_observed`, `hlc_rejected_messages`                                                                                               |

### 16.2 Tracing e logs

- **OpenTelemetry:** um _trace_ acompanha uma requisição do cliente ao gateway, ao líder do range, à replicação em seguidores, ao `fsync`, à aplicação.
- **Logs JSON** com `nodeId`, `rangeId`, `term`, `index`, `txnId`, e _correlation id_.
- **Admin HTTP** (leve): `/debug/ranges`, `/debug/raft/{rangeId}`, `/debug/txn/{id}`, `/metrics`, `/health`.

### 16.3 Dashboards

Dashboards Grafana versionados em `ops/grafana/`, subindo junto com o Docker Compose (Prometheus + Grafana + Jaeger/Tempo).

---

## 17. Benchmarking

### 17.1 Microbenchmarks (JMH)

Memtable (insert/lookup), codificação de chave MVCC, iterador _merge_, Bloom filter, append do WAL com e sem group commit, serialização Protobuf, HLC `now()` concorrente.

### 17.2 Benchmarks de sistema

- Gerador próprio em `geodb-bench`, com perfis do **YCSB**: A (50/50 leitura/escrita), B (95/5), C (somente leitura), E (scans), F (read-modify-write).
- Distribuições de chaves: uniforme, **zipfian** (hot keys), latest.
- Cenários: 1 range e N ranges; 3 e 5 nós; com e sem falhas; escrita linearizável vs leitura `ReadIndex` vs lease.
- **Metodologia correta:** carga em modelo **aberto** (taxa fixa de chegada) para evitar _coordinated omission_; **HdrHistogram** para p50/p95/p99/p99.9; aquecimento descartado; repetições com intervalo de confiança; registro de hardware, versão da JVM e flags.

### 17.3 Hipóteses de desempenho (a validar, não prometer)

| Hipótese                                                        | Raciocínio                                 |
| --------------------------------------------------------------- | ------------------------------------------ |
| Latência de escrita linearizável ≈ 1 RTT + `fsync` da maioria   | Dominada por `fsync` em disco local        |
| Group commit multiplica vazão de escritas sob concorrência      | Um `fsync` serve muitas propostas          |
| Leituras via lease > ReadIndex > escrita em vazão               | Elimina rodada de rede ou de disco         |
| Vazão escala com número de ranges até saturar CPU/disco do host | Multi-Raft paraleliza grupos independentes |

Os números reais entram no README com a metodologia e as limitações.

---

## 18. Roadmap com critérios de aceite

Cada fase só termina quando **todos** os critérios abaixo passam.

### Fase 0: Fundamentos e esqueleto

- Ler: paper do Raft (versão estendida) e a tese do Ongaro, capítulos de membership e snapshots.
- Esqueleto Maven multi-módulo, CI, ArchUnit, forbidden-apis, `geodb-env` com implementações real e simulada.
- **Aceite:** build verde; regra que impede `System.currentTimeMillis` no núcleo; simulador executa um "hello world" de eventos com tempo virtual reproduzível.

### Fase 1: Raft puro

- Itens 1 a 6 da tabela §6.4, persistência sobre `SimDisk`, invariantes de §6.5.
- TLA+ do Raft base rodando no TLC.
- **Aceite:** 1 milhão de seeds de simulação (3 e 5 nós, com partições, crashes, reordenação) sem violar invariantes; PIT ≥ 85% no módulo.

### Fase 2: KV replicado + linearizabilidade

- Máquina de estados KV sobre o Raft, `ReadIndex`, snapshots e compactação (itens 7 a 10), deduplicação de sessões.
- `geodb-lincheck` com modelo de registro.
- **Aceite:** históricos de workloads sob nemesis são linearizáveis em N seeds; o checker **detecta** bugs injetados de propósito (testar o testador).

### Fase 3: Storage engine próprio

- WAL, memtable, SSTable, MANIFEST, compaction, cache; `RocksDbEngine` como comparativo.
- Raft Log Engine com group commit.
- **Aceite:** testes de modelo (jqwik) e de crash em todo ponto; fuzzing sem crashes por X horas; JMH comparativo documentado.

### Fase 4: MVCC e transações em um range (Snapshot Isolation)

- HLC, chaves MVCC, intents, `TxnRecord`, resolução, GC.
- **Aceite:** teste bank-transfer com invariante sob falhas; write skew demonstrado e documentado.

### Fase 5: Sharding e Multi-Raft

- Store com múltiplas Replicas, heartbeats coalescidos, split, range 0, roteamento com cache, rede real gRPC e `geodb-server` funcional em Docker.
- **Aceite:** cluster de 3 nós com centenas de ranges criados por split sob carga; linearizabilidade preservada durante splits e trocas de líder.

### Fase 6: Transações distribuídas e Serializable

- Transações multi-range, timestamp cache, read refresh, uncertainty interval, detecção de deadlock.
- **Aceite:** Elle-like checker sem anomalias G2 em Serializable; anomalia detectada em SI.

### Fase 7: Membership, rebalanceamento e operabilidade

- Learners, joint consensus, alocador, reparo de réplicas, transferência de liderança, lease reads.
- **Aceite:** adicionar/remover nós sob carga com linearizabilidade intacta; nó morto tem suas réplicas refeitas automaticamente.

### Fase 8: Polimento e evidências

- Observabilidade completa, benchmarks YCSB, documentação (ADRs, diagramas, "bugs que encontrei"), _post_ técnico e README.
- **Aceite:** README com arquitetura, garantias, limitações honestas, resultados reproduzíveis com um comando.

> **Ponto de corte sugerido:** o projeto já é excepcional ao término da **Fase 4** e muito raro ao término da **Fase 6**. As fases 7 e 8 são ganho adicional; a documentação (fase 8) deve ser feita **incrementalmente**, não deixada para o fim.

---

## 19. Riscos

| Risco                                      | Impacto | Mitigação                                                                          |
| ------------------------------------------ | ------- | ---------------------------------------------------------------------------------- |
| Escopo excessivo e abandono                | Alto    | Fases com entrega e aceite; ponto de corte claro                                   |
| Bugs sutis de Raft (snapshot, conf change) | Alto    | TLA+, invariantes em simulação, PIT, referências do `etcd/raft`                    |
| Não determinismo vazando para o núcleo     | Alto    | ArchUnit + forbidden-apis desde o dia 1                                            |
| Depurar storage próprio consome meses      | Médio   | `RocksDbEngine` como fallback para isolar bugs                                     |
| Complexidade de transações                 | Alto    | Fase 4 só em um range; distribuição só na fase 6; SI antes de Serializable         |
| Benchmarks enganosos                       | Médio   | Metodologia aberta, HdrHistogram, relatar limitações                               |
| Pressão de GC afetando latência            | Médio   | Memtable e blocos em arena off-heap; medir pausas; ZGC/Shenandoah como experimento |
| Perda de motivação                         | Alto    | Documentar cada bug encontrado; publicar progresso por fase                        |

---

## 20. Registro de evidências para o currículo

Desde a fase 1, o projeto **coleta automaticamente** os números que sustentam afirmações concretas. Manter um arquivo `docs/EVIDENCE.md` atualizado pelo CI com:

| Evidência                                                           | Origem                            |
| ------------------------------------------------------------------- | --------------------------------- |
| Nº de seeds/cenários simulados sem violação                         | Campanhas do `geodb-sim`          |
| Horas de tempo simulado equivalente                                 | Contador do simulador             |
| Bugs encontrados pela simulação, por categoria e fase               | Corpus de regressão + ADRs de bug |
| Tamanho de histórico validado como linearizável                     | `geodb-lincheck`                  |
| Anomalias de isolamento detectadas (SI) e eliminadas (Serializable) | Testes Elle-like                  |
| Vazão e latência p50/p99/p99.9 por cenário                          | `geodb-bench`                     |
| Nº de ranges, nós e tempo de recuperação após falha                 | Testes de caos                    |
| Mutation score e cobertura do `geodb-raft`                          | PIT / JaCoCo                      |
| Invariantes verificados no TLC (estados explorados)                 | Saída do TLC                      |

Isso permite escrever bullets baseados em fatos medidos, e não em adjetivos.

---

## 21. Lista de ADRs e referências

### ADRs a escrever (um por decisão)

1. Java e versão-alvo
2. Raft próprio vs biblioteca existente
3. Log Raft dedicado vs compartilhar o LSM
4. LSM próprio vs RocksDB (e como comparar)
5. Núcleo determinístico e SPI `geodb-env`
6. Estilo do `RaftNode` (Ready/Advance) e contrato de ordenação
7. Joint consensus vs single-server change
8. ReadIndex vs lease reads
9. HLC vs TrueTime-like
10. Codificação de chaves MVCC e política de GC
11. Local do registro de transação e protocolo de commit
12. SI primeiro, Serializable depois (e como o checker prova)
13. Detecção de deadlock: grafo de espera vs timeout e prioridade
14. Endereçamento de ranges (range 0 único vs meta1/meta2)
15. Split sem cópia de dados (engine compartilhado por Store)
16. Modelo de concorrência (workers por range, virtual threads só na borda)
17. Idempotência por sessão de cliente
18. Estratégia de testes: simulação, checker, caos, TLA+

### Referências essenciais

**Consenso**

- Ongaro & Ousterhout, _In Search of an Understandable Consensus Algorithm (Extended Version)_
- Ongaro, _Consensus: Bridging Theory and Practice_ (tese de doutorado)
- Código de referência: `etcd-io/raft`, `tikv/raft-rs`, `hashicorp/raft`
- Especificação TLA+ do Raft (repositório `ongardie/raft.tla`)

**Bancos distribuídos**

- Corbett et al., _Spanner: Google's Globally-Distributed Database_
- Taft et al., _CockroachDB: The Resilient Geo-Distributed SQL Database_ (SIGMOD 2020)
- Peng & Dabek, _Large-scale Incremental Processing Using Distributed Transactions and Notifications_ (Percolator)
- Documentação de arquitetura do TiKV e do CockroachDB

**Tempo e transações**

- Kulkarni et al., _Logical Physical Clocks and Consistent Snapshots in Globally Distributed Databases_ (HLC)
- Berenson et al., _A Critique of ANSI SQL Isolation Levels_
- Cahill et al., _Serializable Isolation for Snapshot Databases_
- Adya, _Weak Consistency: A Generalized Theory and Optimistic Implementations_

**Armazenamento**

- O'Neil et al., _The Log-Structured Merge-Tree_
- Chang et al., _Bigtable_
- Documentação interna do LevelDB e do RocksDB (formato de SSTable, MANIFEST, compaction)

**Verificação**

- Herlihy & Wing, _Linearizability: A Correctness Condition for Concurrent Objects_
- Wing & Gong; Lowe, _Testing for Linearizability_; Porcupine (checker de referência)
- Kingsbury & Alvaro, _Elle: Inferring Isolation Anomalies from Experimental Observations_
- Relatórios do Jepsen
- Zhou et al., _FoundationDB: A Distributed Unbundled Transactional Key Value Store_
- Material técnico sobre simulação determinística do FoundationDB e do TigerBeetle (VOPR)
- Lamport, _Specifying Systems_ (TLA+)
