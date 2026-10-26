# Morphe Patches — personal build

**Personal build, unsupported.** This is a community fork with a custom YouTube video magnifier patch. It is not an official Morphe release and is not supported by MorpheApp.

[Add this source to Morphe Manager](https://morphe.software/add-source?github=TankKunn/morphe-patches)

## Switch from the official source

1. In Morphe Manager, **disable** the official Morphe Patches source; do not delete it.
2. Add this fork using the link above.
3. Repatch YouTube once using this fork. That switches the installed patch set to this source.

## Choose a channel

- **Stable:** use the `main` source with **Pre-release patches off**. It follows upstream stable releases weekly, carries the magnifier version approved on `main`, and publishes a separate stable fork release only after tests, bundle lint, and the release-asset check pass.
- **Development:** use the `dev` source with **Pre-release patches on**. It follows upstream `dev` weekly and publishes a prerelease after the same build and bundle checks pass.

For newer experimental YouTube app versions, enable **Experimental app versions** for this source. Keep the official source disabled (not deleted) while using this fork.

## Updates and releases

The scheduled sync runs weekly. The stable channel also rebuilds after an approved change lands on `main`; it follows the newest upstream stable release. The development channel follows upstream `dev`. Both retain the magnifier patch, run patch tests and build the bundle, then verify the generated `.mpp` and the fork release URL before publishing or moving the source branch. A conflict or failed check keeps the previous source bundle in place. A magnifier-only change gets a new fork version so Morphe Manager can offer it as an update without colliding with upstream tags.

Morphe Manager compares the source's bundle version with the patched app's version and looks for relevant patch changes. A higher fork version is required for an update to appear; changing patch files under an already released version is not enough.

GitHub Actions may need the repository secrets `MORPHE_GPR_USER` and `MORPHE_GPR_KEY` to resolve Morphe's GitHub Packages plugin. Use a read-only `read:packages` credential; workflows pass these secrets only to Gradle package resolution. They are never stored in repository files.

## Patch list

<!-- PATCHES_START -->
<!-- PATCHES_END -->

## Source and license

This fork follows the upstream [Morphe Patches](https://github.com/MorpheApp/morphe-patches) project and includes its [LICENSE](LICENSE) and [NOTICE](NOTICE). The upstream project is based on prior work by [ReVanced](https://github.com/ReVanced). This repository and its custom changes are provided as a personal, unsupported build.
