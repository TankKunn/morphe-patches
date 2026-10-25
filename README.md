# Morphe Patches — personal build

**Personal build, unsupported.** This is a community fork with a custom YouTube video magnifier patch. It is not an official Morphe release and is not supported by MorpheApp.

[Add this source to Morphe Manager](https://morphe.software/add-source?github=TankKunn/morphe-patches)

## Switch from the official source

1. In Morphe Manager, **disable** the official Morphe Patches source; do not delete it.
2. Add this fork using the link above.
3. Repatch YouTube once using this fork. That switches the installed patch set to this source.

Enable **Pre-release patches** for this source so Morphe can fetch the fork's prereleases. For newer experimental YouTube app versions, also enable **Experimental app versions**.

## Updates and releases

The fork's `dev` prerelease is refreshed weekly from Morphe's upstream `dev` branch. The automation carries forward the magnifier source, runs the patch tests and builds the Android bundle, then updates the fork's `dev` branch and publishes a prerelease only if those checks pass. If the patch no longer applies cleanly or a check fails, it stops before updating the branch or publishing. Enable **Pre-release patches** for this source in Morphe Manager to receive these releases.

GitHub Actions may need the repository secrets `MORPHE_GPR_USER` and `MORPHE_GPR_KEY` to resolve Morphe's GitHub Packages plugin. Use a read-only `read:packages` credential; workflows pass these secrets only to Gradle package resolution. They are never stored in repository files.

## Patch list

<!-- PATCHES_START -->
<!-- PATCHES_END -->

## Source and license

This fork follows the upstream [Morphe Patches](https://github.com/MorpheApp/morphe-patches) project and includes its [LICENSE](LICENSE) and [NOTICE](NOTICE). The upstream project is based on prior work by [ReVanced](https://github.com/ReVanced). This repository and its custom changes are provided as a personal, unsupported build.
