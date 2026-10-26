# Fork maintenance

- `main` holds the upstream baseline and fork automation; `dev` carries the YouTube magnifier and prerelease metadata.
- The weekly upstream sync reapplies only the magnifier-specific source additions and credential fallback, then runs patch tests and builds the Android bundle before updating `dev`. Conflicts or failed checks stop the update and publication.
- Dev prereleases are published by CI after a `dev` push passes tests and the bundle build. The sync and prerelease workflows share a concurrency lock. Manual dispatch defaults to build-only.
- Crowdin workflows are intentionally disabled in this fork. Upstream `dev` sync brings upstream translation updates.
- Morphe Settings exposes one magnifier preference: `morphe_magnifier_enabled`. Border appearance remains in the player Style panel.
- Package credentials must be read-only (`read:packages`) and supplied through user-level Gradle properties for local builds or repository Actions secrets `MORPHE_GPR_USER` and `MORPHE_GPR_KEY` for CI. Never add credential values to source, workflow files, logs, or commits.
- Keep APKs, bundles, signing files, local tools, device captures, and test logs out of Git.
- The local Windows build reached protobuf generation but Windows failed to start `protoc` with CreateProcess error 623. Kotlin patch compilation passed; full extension and bundle validation is performed by Linux CI.
