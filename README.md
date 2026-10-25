# Morphe Patches — personal build

**Personal build, unsupported.** This is a community fork with a custom YouTube video magnifier patch. It is not an official Morphe release and is not supported by MorpheApp.

[Add this source to Morphe Manager](https://morphe.software/add-source?github=TankKunn/morphe-patches)

## Switch from the official source

1. In Morphe Manager, **disable** the official Morphe Patches source; do not delete it.
2. Add this fork using the link above.
3. Repatch YouTube once using this fork. That switches the installed patch set to this source.

For newer experimental YouTube versions, enable **Pre-release patches** and **Experimental app versions** for this source in Morphe Manager.

## Updates and releases

The `dev` branch is the prerelease channel. Scheduled upstream updates create a reviewable pull request; they do not update the branch or publish a release automatically. A prerelease is published only by manually running the gated release workflow on `dev` and opting in to publication.

GitHub Actions may need the repository secrets `MORPHE_GPR_USER` and `MORPHE_GPR_KEY` to resolve Morphe's GitHub Packages plugin. Use a read-only `read:packages` credential; workflows pass these secrets only to Gradle package resolution. They are never stored in repository files.

## Patch list

<!-- PATCHES_START -->
<!-- PATCHES_END -->

## Source and license

This fork follows the upstream [Morphe Patches](https://github.com/MorpheApp/morphe-patches) project and includes its [LICENSE](LICENSE) and [NOTICE](NOTICE). The upstream project is based on prior work by [ReVanced](https://github.com/ReVanced). This repository and its custom changes are provided as a personal, unsupported build.
