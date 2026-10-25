# Morphe Patches — personal build

**Personal build, unsupported.** This is a community fork with a custom YouTube video magnifier patch. It is not an official Morphe release and is not supported by MorpheApp.

[Add this source to Morphe Manager](https://morphe.software/add-source?github=TankKunn/morphe-patches)

## Switch from the official source

1. In Morphe Manager, **disable** the official Morphe Patches source; do not delete it.
2. Add this fork using the link above.
3. Repatch YouTube once using this fork. That switches the installed patch set to this source.

Enable **Pre-release patches** for this source so Morphe can fetch the fork's prereleases. For newer experimental YouTube app versions, also enable **Experimental app versions**.

## Updates and releases

The `dev` branch contains the custom YouTube video magnifier and is the prerelease channel. The `main` branch carries the upstream baseline plus fork automation. A weekly workflow proposes upstream changes in a pull request, runs the Morphe patch tests and Android bundle build, and merges the update into `dev` and publishes a prerelease only when both pass. Merge conflicts stop the run before a pull request is updated; failed checks leave the pull request open for review. Morphe can fetch the published prerelease when Pre-release patches is enabled for this source.

GitHub Actions may need the repository secrets `MORPHE_GPR_USER` and `MORPHE_GPR_KEY` to resolve Morphe's GitHub Packages plugin. Use a read-only `read:packages` credential; workflows pass these secrets only to Gradle package resolution. They are never stored in repository files.

## Patch list

<!-- PATCHES_START -->
<!-- PATCHES_END -->

## Source and license

This fork follows the upstream [Morphe Patches](https://github.com/MorpheApp/morphe-patches) project and includes its [LICENSE](LICENSE) and [NOTICE](NOTICE). The upstream project is based on prior work by [ReVanced](https://github.com/ReVanced). This repository and its custom changes are provided as a personal, unsupported build.
