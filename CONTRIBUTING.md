# Contributing

GeoDB is a distributed, transactional key-value database built to learn distributed systems deeply and to **demonstrate correctness**, not to win benchmarks. Contributions are welcome, and every one is judged against two things: the roadmap phase the project is in, and the correctness rules in [`AGENTS.md`](./AGENTS.md).

## Read first

| Document                            | Why                                                                                                                     |
| ----------------------------------- | ----------------------------------------------------------------------------------------------------------------------- |
| [`GLOSSARY.md`](./GLOSSARY.md)      | Shared vocabulary. Use these terms in issues, PRs and docs.                                                             |
| [`docs/overview/`](./docs/overview) | The general description and the technical specification (pt-BR): scope, non-goals, roadmap, acceptance criteria.        |
| [`docs/adr/`](./docs/adr)           | The architecture decisions and the alternatives already rejected. Check before proposing something that was considered. |
| [`AGENTS.md`](./AGENTS.md)          | The non-negotiable rules: determinism, Raft ordering contract, durability, verification. Humans should read it too.     |

Check the **non-goals** and the **roadmap phase** before filing anything. We do not build Byzantine tolerance, SQL or secondary indexes, TrueTime-style clocks, multi-tenancy, or chase raw performance records. We also do not build features of a later phase ahead of time.

## Where each request goes

| You have                                                                      | Go to                                                                                         |
| ----------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------- |
| A bug: wrong behavior, data loss, violated invariant, crash, recovery failure | [Bug issue](../../issues/new?template=bug.yml)                                                |
| A failing simulation seed, a checker violation, or a fuzzing crash            | [Bug issue](../../issues/new?template=bug.yml), with the seed or input                        |
| A change or addition within planned scope                                     | [Feature or improvement issue](../../issues/new?template=feature.yml)                         |
| A design decision to propose or challenge                                     | [Design decision (ADR proposal) issue](../../issues/new?template=adr-proposal.yml)            |
| An error, gap, or contradiction in the docs                                   | [Documentation issue](../../issues/new?template=documentation.yml)                            |
| A missing test, invariant, fault, or checker capability                       | [Test or verification gap issue](../../issues/new?template=verification-gap.yml)              |
| A question, or "how do I...?"                                                 | [Discussions: Q&A](../../discussions/categories/q-a)                                          |
| An open-ended idea or research direction                                      | [Discussions: Ideas](../../discussions/categories/ideas)                                      |
| A security vulnerability                                                      | A private [security advisory](../../security/advisories/new). Do **not** open a public issue. |

Always search open and closed issues for a duplicate first.

## Contribution workflow

1. **Start with an issue** for anything beyond a typo or an obvious one-line fix, so the approach is agreed before work begins. Comment to claim it.
2. **Branch** from `main` using `<type>/<short-kebab-description>`, e.g. `feat/raft-prevote`, `fix/wal-torn-write`, `docs/adr-joint-consensus`.
3. **Write the test first (TDD).** See [`docs/guides/testing-guidelines.md`](./docs/guides/testing-guidelines.md). A bug fix starts with a failing test or seed.
4. **Commit small and often** using Conventional Commits. See [`docs/guides/commit-guidelines.md`](./docs/guides/commit-guidelines.md).
5. **Document in the same change.** Spec, guides, and an ADR when you make or change a decision. See [`docs/guides/documentation-guidelines.md`](./docs/guides/documentation-guidelines.md).
6. **Open a pull request** and fill in the template in [`.github/PULL_REQUEST_TEMPLATE.md`](./.github/PULL_REQUEST_TEMPLATE.md). See [`docs/guides/pull-request-guidelines.md`](./docs/guides/pull-request-guidelines.md). Open it as a Draft until CI is green.

| Guide                                                                 | What it covers                                                                  |
| --------------------------------------------------------------------- | ------------------------------------------------------------------------------- |
| [Commit guidelines](./docs/guides/commit-guidelines.md)               | Conventional Commits, scopes, small frequent commits, footers (`Seed:`, `ADR:`) |
| [Pull request guidelines](./docs/guides/pull-request-guidelines.md)   | Size, template, checklist, review focus per module, merging                     |
| [Documentation guidelines](./docs/guides/documentation-guidelines.md) | Doc layout, ADR lifecycle, bug write-ups, evidence ledger                       |
| [Testing guidelines](./docs/guides/testing-guidelines.md)             | TDD, simulation, checkers, TLA+, fuzzing, mutation, CI tiers                    |

### Development environment (planned, from Phase 0)

JDK 25 LTS (core modules compile at language level 21), Maven, Docker with Compose. Exact setup and commands will be documented here when the Maven skeleton lands.

## Writing ADRs and documentation

- Write an ADR when a decision is hard to reverse, has real alternatives, constrains other modules, or changes a correctness assumption.
- Copy the template at [`docs/templates/adr-template.md`](./docs/templates/adr-template.md) into [`docs/adr/`](./docs/adr) and follow the naming and numbering rules in the documentation guide.
- Accepted ADRs are not rewritten. To change a decision, write a new ADR that supersedes the old one.
- ADRs, code, comments and commit messages are in **English**. The overview and specification are in **pt-BR**.

## The quality bar

A contribution is merged only if it respects the rules that protect the project's value:

- **Determinism.** Core modules reach time, randomness, network, disk and threads only through `geodb-env`. ArchUnit and forbidden-apis enforce this.
- **Ordering and durability.** The Raft `Ready`/`advance` contract is preserved; a write is acknowledged only after a majority has `fsync`ed it.
- **Verification.** Every bug becomes a permanent regression (a seed in `sim/corpus/` or a test). Simulation invariants, checkers and CI gates are never weakened to make something pass.
- **Honest evidence.** No benchmark, seed count, mutation score or simulated-hours figure unless it comes from a real, recorded run.

The full list is in [`AGENTS.md`](./AGENTS.md).

## Writing issues and PRs with AI

Agents are welcome to help, but you post the issue or PR, so you own it:

- **One problem per issue.** Several findings from one session are several issues, or none.
- **Short.** If it does not fit on one screen, cut it.
- **Repro first.** The seed, input, or steps that show the problem come before any analysis.
- **No fabricated evidence.** Do not invent numbers, seeds, or test results. State exactly what was run.
- **Real, checkable claims.** An AI-written ADR proposal must list genuine alternatives and honest downsides, not generic text.
- **PRs follow the same guides.** Small, test-first, conventional commits, template filled in. Do not let an agent merge its own PR or weaken a gate.

Agent-specific instructions live in [`AGENTS.md`](./AGENTS.md) and [`CLAUDE.md`](./CLAUDE.md).

## Review and merging

- CI must be green: build, ArchUnit, forbidden-apis, tests, and all regression seeds.
- Review threads are answered; blocking comments are resolved.
- Default merge strategy is rebase and merge, so each commit must be a good Conventional Commit.
- Be kind and specific in review. Critique the code and the design, not the person.
