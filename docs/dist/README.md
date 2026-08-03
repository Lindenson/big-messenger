# Migration kit — split messenger-hl into separate repos

Ready-to-run assets for the decision in [`../MODULE-DISTRIBUTION.md`](../MODULE-DISTRIBUTION.md):
turn the in-repo modules into separate GitHub repos publishing versioned Maven artifacts.

Placeholders to set before use: `OWNER` (GitHub org/user, e.g. `Lindenson`), `groupId` = `org.hormigas`,
current version `1.1.0-SNAPSHOT`.

## Files

| File | Goes to | Purpose |
|---|---|---|
| `extract-module.sh` | run locally | extract one module subdir into a fresh repo, **preserving git history** |
| `messenger-hl-bom.pom.xml` | `messenger-hl-contracts` repo (as `bom/pom.xml`) | one artifact that pins all module versions |
| `distribution-management.snippet.xml` | each module's `pom.xml` | where CI publishes the artifact |
| `consumer-repositories.snippet.xml` | the app's `pom.xml` | where the app resolves the artifacts from |
| `settings.xml.example` | `~/.m2/settings.xml` and CI | registry credentials (GitHub Packages) |
| `publish-on-tag.yml` | each repo's `.github/workflows/` | build + publish on a `v*` tag (**you** commit this — the automation token cannot push workflows). Needs a `PACKAGES_TOKEN` secret (classic PAT, `write:packages`) in each repo — one credential covers publishing the module and reading the upstream contracts package. |

## Order of operations

1. **Per module** (`contracts` → `hl` → `hl-redis`): run `extract-module.sh` to create the standalone repo
   with history, create the GitHub repo (private), push.
2. Add `distribution-management.snippet.xml` to each module POM; add `publish-on-tag.yml` to each repo.
3. Put the **BOM** in the contracts repo (`bom/` module); tag `v1.1.0` there to publish it.
4. Tag each module `v1.1.0` → CI publishes the release artifacts to GitHub Packages.
5. In the **app** repo: drop the three `messenger-hl-*` sub-directories, add
   `consumer-repositories.snippet.xml` + the BOM import (see `MODULE-DISTRIBUTION.md`), and change the three
   dependencies to versionless (BOM-managed). Update `deploy.sh`: remove the three `mvn -f …/pom.xml install`
   steps — the app now resolves published artifacts.
6. Grant access per repo (this is the "in parts" part): add teams/collaborators to each repo as intended.

## Development after the split

- `main` of each lib repo publishes `-SNAPSHOT` (add a snapshot-on-push job or run `mvn deploy` from CI on
  main). Downstream repos pull fresh snapshots with `mvn -U`.
- Releases are tags (`vX.Y.Z`). Bump the BOM when a module releases.
- For a change spanning two repos during development, `mvn install` the upstream module locally, then build
  the downstream against the local `-SNAPSHOT` — same as today, just across repo checkouts.

## The GitHub Packages wrinkle

Each GitHub Packages Maven feed is **repo-scoped** (`maven.pkg.github.com/OWNER/REPO`), so the app lists one
`<repository>` per source repo (see `consumer-repositories.snippet.xml`) and one matching `<server>` per feed
in `settings.xml`. Keep them centralized in `settings.xml`, not scattered across POMs. If the multi-URL
bothers you later, run a tiny **Reposilite** on the deploy host as a single proxy URL — but then access
control moves to Reposilite (coarser than per-repo GitHub perms). Not needed to start.
