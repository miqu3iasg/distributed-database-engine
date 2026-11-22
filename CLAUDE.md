---
title: GeoDB Claude
tags:
    - documentation
related:
    -  # "[[related-note]]"
lang: en-US # switch language
date: 2026-11-22T13:05:48
id: db715e24-d28f-4bc1-a95b-8427181fb9c8
---

# CLAUDE.md

@AGENTS.md

The file above is the shared source of truth for every agent. This file only adds what is specific to working with Claude Code. If the two ever conflict, fix the conflict; do not follow one silently.

## Where we are

Phase 0 project setup. Maven multi-module skeleton exists (`geodb-common`, `geodb-env`, `geodb-raft`, `geodb-sim`), CI workflow configured, ArchUnit + forbidden-apis guards configured, `geodb-env` Clock SPI present, ArchUnit determinism-rules test added. The next engineering milestone is completing Phase 0 (CI green, `geodb-env` real + simulated, a reproducible virtual-time "hello world"). Do not scaffold later-phase modules "to save time".

## How to work in this repo

1. **Plan before touching consensus, storage, MVCC or transactions.** State which ADR(s) and spec section(s) you are following, what invariant could break, and how it will be tested (simulation seed, model test, checker). Then implement.
2. **Docs first when docs are silent.** If a decision is not covered, propose an ADR draft instead of choosing silently. List the alternatives you considered.
3. **Keep the determinism rules mechanical.** Never write `System.nanoTime()` or `new Random()` in a core module "just for now". Extend `geodb-env` instead.
4. **When a simulation fails, preserve the evidence.** Record `(seed, code version)`, reproduce, find the root cause, add the seed to `sim/corpus/`. Never respond to a failure with a retry, a sleep, or a weaker assertion.
5. **Test the testers.** Any change to `geodb-lincheck` or the isolation checker must be accompanied by a test that injects a known bug and expects detection.
6. **Never invent numbers.** Do not write benchmark results, seed counts, mutation scores, or simulated hours into docs unless they come from a run you actually executed and can show.
7. **Verify before claiming done.** The build does not exist yet; once it does, run the full verification (ArchUnit, forbidden-apis, regression seeds) before saying a change works, and say exactly what you ran.

## Editing documentation

- ADRs and the spec use Obsidian-style front matter and `[[wikilinks]]`. Preserve both, keep the `id`, and update `date`.
- ADR sections: Context, Decision, Alternatives Considered, Consequences (Positive/Negative), References.
- The overview and spec are **pt-BR**; ADRs, code, comments and commit messages are **English**. Talk to the user in the language they write in (currently Portuguese).
- When you touch a document, check the "Known inconsistencies" list in AGENTS.md section 12 and fix or extend it. Do not leave new contradictions behind.

## Code style reminders

- `record` + `sealed interface` for messages and commands; exhaustive `switch`.
- Core modules compile at language level 21; do not use newer APIs there.
- Virtual threads only at the client-facing edge; prefer `ReentrantLock` over `synchronized` there.
- Protobuf conversion belongs in `geodb-server`, never in `geodb-raft`.
- Cross-reference the TLA+ spec in comments on Raft handlers: `/* TLA: <ActionName> */`.

## Context management

When context is compacted, preserve: the current phase, the ADRs/spec sections being followed, any failing seeds and their status, open questions awaiting the user, and files already modified.

## Optional setup (not created yet)

Worth adding once Phase 0 exists, and only with the user's approval:

- A pre-commit or CI hook that runs ArchUnit and forbidden-apis.
- Project permissions that allow `mvn` and `git` read commands without prompting, and require confirmation for anything that deletes files under `sim/corpus/`.
- Slash commands or skills for "new ADR from template" and "replay failing seed".
