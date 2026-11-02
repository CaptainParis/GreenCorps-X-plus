# Contributing to GreenCorps X+

Thanks for helping out! Small, focused pull requests are the easiest to review and merge.

## Before you start
- Check [open issues and pull requests](https://github.com/qolarr-dev/GreenCorps-X-plus/pulls) so you don't duplicate work.
- For a bigger feature, open an issue first and describe what you want to build.

## Workflow
1. Fork the repo and create a branch: `feature/<short-name>` or `fix/<short-name>`.
2. Build against **Minecraft 1.21.4**. Don't change `minecraft_version`, the Meteor or Xaero pins, or `mod_version`; the maintainer bumps versions when releasing.
3. Add a player-facing bullet under `## Unreleased` in [CHANGELOG.md](CHANGELOG.md).
4. Make sure `./gradlew build` passes, and test in game.
5. Open a pull request using the template.

## Code guidelines
- New modules go in `GreenCorps.CATEGORY` and are registered in `GreenCorps.java`.
- Client-side only: alerts use Meteor's chat helpers, never server chat packets.
- Respect server limits: inventory automation goes through the shared click budget.
- Never send coordinates anywhere, and never commit `local.properties`, jars, secrets or webhook URLs.

## Commit messages
Use [Conventional Commits](https://www.conventionalcommits.org/), e.g. `feat(stasis): add seat-only ESP toggle` or `fix(gateway): restore tracer alpha floor`.

## License
By contributing, you agree that your contributions are licensed under the [MIT License](LICENSE), and that QOLARR may also release them under a different license in future versions of GreenCorps X+.
