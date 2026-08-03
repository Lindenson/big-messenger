# Module distribution strategy — messenger-hl

**Status:** proposed · **Date:** 2026-08-01 · **Scope:** `messenger-hl-contracts`, `messenger-hl`,
`messenger-hl-redis` · **Supersedes:** in-repo standalone modules (current) for the extracted HL toolkit.

> Governance mirror: this decision should also be recorded as **ADR-022** in the architecture workspace
> (`architecture/adr/drafts/`). This file is the messenger-repo copy that engineers act on.

## Decision

Split the extracted high-performance modules into **separate Git/GitHub repositories** and consume them
as **published, versioned Maven artifacts** — not as in-repo reactor modules and not as source
dependencies. Version alignment is carried by a **BOM**; releases are driven by **semver git tags**;
day-to-day cross-repo development runs on **`-SNAPSHOT`** artifacts.

- **Distribution backend (default): GitHub Packages, private.** Pull access = GitHub repo/team membership
  — this *is* the "access in parts" (доступ частями) requirement, with no second permission system. We are
  already GitHub-native (GHCR for images).
- **Public tiers are additive and per-module.** Any module we choose to fully open-source later moves to
  **Maven Central** (or JitPack) *in addition* — a reversible, per-tier step. `messenger-hl-contracts` is
  the natural first candidate (pure interfaces), then `messenger-hl` (already framework-neutral).
- **No Quarkus extension.** Rejected — see below.

## Why (context)

The HL toolkit was extracted precisely to be reusable and framework-neutral: `messenger-hl` is pure POJOs
on slf4j + Mutiny + Micrometer + Caffeine, no CDI, no Quarkus, no Jandex; the app wires each mechanism via
`@Produces`. That neutrality is the asset we are now distributing.

The driver for separate repos is **graduated source access**, not runtime reuse (the other services —
IDS, client-directory — are Go, so there is no second JVM consumer *yet*). Separate repos let us grant
different people/teams access to different tiers.

## Repository topology

| Repo | Artifact (`org.hormigas:…`) | Tier / access | Notes |
|---|---|---|---|
| `messenger-hl-contracts` | `messenger-hl-contracts` | widest (public candidate) | also **hosts the BOM** (`messenger-hl-bom`) to avoid a 5th repo |
| `messenger-hl` | `messenger-hl` | mechanisms | depends on contracts (published artifact) |
| `messenger-hl-redis` | `messenger-hl-redis` | narrowest (infra) | the only Quarkus/CDI-coupled module (has Jandex) |
| `messenger` (app) | `messanger` | private | consumes the three via the BOM; publishes nothing |

**No shared parent POM.** The modules are standalone today; keep them so. A published parent would make
every module inherit cross-repo on every build — needless coupling. Distribution config
(`<distributionManagement>` / `<repositories>`) lives in each module's own POM; the consumer's registry
credentials live in `settings.xml`. Version alignment is the BOM's job, nothing else's.

## The two axes (the crux of doing it "красиво")

These are independent — do not conflate them:

| Axis | Mechanism | Governs |
|---|---|---|
| **Source access** ("in parts") | separate **git repos** + GitHub team/collaborator perms | who *sees and commits* code |
| **Dependency management** | **Maven registry** + semver tags + BOM | who *builds against* the binary |

Java has no Go-style "depend on a git tag". You publish a versioned artifact; consumers depend on
coordinates. Access-in-parts is a *repo/registry permission* concern, layered on top.

## Versioning & the development flow (the accepted cost)

After the split, `messenger-hl` depends on `messenger-hl-contracts` as a **published artifact**, not a
local reactor module — so the current "one `mvn install` and everything sees everything" convenience is
lost. Standard mitigation (already half in place — the modules are `1.1.0-SNAPSHOT`):

- **`main` publishes `-SNAPSHOT`** to the registry on each merge → the downstream repo pulls the fresh
  snapshot (`mvn -U`). During active cross-cutting work you may still `mvn install` a dependency locally.
- **Releases are tags.** `git tag v1.2.0` → CI builds and publishes the release version → BOM bumps.
- **Semver contract:** MAJOR = breaking contract change, MINOR = additive, PATCH = fix. The
  `messenger-hl-contracts` surface is the thing semver protects; treat it as an API.

This friction is the real price of multi-repo. It is worth it only because *graduated access* is a real
requirement; it buys nothing for reuse until a second JVM consumer exists.

## Rejected alternatives

- **Quarkus extension (runtime + deployment modules, `@ConfigRoot`, `Recorder`, `BuildStep`).** Reverses
  the framework-neutrality we deliberately built, adds heavy build-time machinery, and pays off only for
  native-image / build-time optimization / distribution to the Quarkus community — none of which apply to
  an internal JVM team with one consumer. The only CDI-coupled piece (`messenger-hl-redis`) is a plain
  `@ApplicationScoped` bean, not extension machinery.
- **Keep everything in one repo, publish multiple artifacts (monorepo + registry).** Simpler dependency
  management, but does *not* satisfy graduated source access — everyone with repo access sees everything.
  This is the fallback if the multi-repo dev friction proves too high *and* the access requirement relaxes.
- **JitPack for private tiers.** Closest to Go's model (build from a tag), free for public repos, but
  private requires a paid plan + token. Fine as the *public*-tier option; not the private default.

## Execution

See `docs/dist/` for the ready-to-run migration kit: extraction script (history-preserving), the BOM POM,
the `<distributionManagement>` snippet, `settings.xml` example, and the publish-on-tag GitHub Actions
workflow. Two steps are inherently human: **creating the GitHub repos / setting visibility**, and
**committing the workflow YAML** (the automation token cannot push `.github/workflows/*`).
