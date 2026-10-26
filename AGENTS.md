# Fork maintenance

- `main` is the stable channel and `dev` is the prerelease channel. Both carry the YouTube magnifier and use separate version streams.
- Weekly syncs follow upstream stable on `main` and upstream `dev` on `dev`; approved `main` changes also trigger a stable build. Tests, bundle lint, and fork-URL/asset checks must pass before a channel moves or publishes.
- A sync conflict or failed check must leave the previous bundle available and create a failure issue when repository Issues are enabled.
- Morphe Settings exposes one magnifier preference: `morphe_magnifier_enabled`. Border appearance stays in the player Style panel.
- Package credentials must be read-only (`read:packages`) and supplied through user-level Gradle properties for local builds or the repository Actions secrets `MORPHE_GPR_USER` / `MORPHE_GPR_KEY` for CI. Never add credential values to source, workflow files, logs, or commits.
- Do not commit APKs, bundles, signing files, local tools, device captures, or test logs.
