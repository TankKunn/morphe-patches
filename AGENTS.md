# Fork maintenance

- `main` is the upstream baseline plus fork-specific automation. The custom YouTube magnifier is carried on `dev`.
- Upstream updates are proposed to `dev` as pull requests. Review the build and compatibility results before merging; never fast-forward `dev` directly from an automatic job.
- The `dev` prerelease workflow runs only by manual dispatch. Its `publish_prerelease` input defaults to `false`; leave it off for build-only validation.
- Morphe Settings exposes one magnifier preference: `morphe_magnifier_enabled`. Border appearance stays in the player Style panel.
- Package credentials must be read-only (`read:packages`) and supplied through user-level Gradle properties for local builds or the repository Actions secrets `MORPHE_GPR_USER` / `MORPHE_GPR_KEY` for CI. Never add credential values to source, workflow files, logs, or commits.
- Keep APKs, bundles, signing files, local tools, device captures, and test logs out of Git.
- The Windows build reached protobuf generation but Windows failed to start `protoc` with CreateProcess error 623. The patch Kotlin compilation passed; full extension/bundle validation remains for Linux CI or a Windows protobuf-runtime fix.
