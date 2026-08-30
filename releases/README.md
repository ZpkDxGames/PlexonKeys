# PlexonKeys releases

PlexonKeys is created and maintained by **Tonim (ZpkDxGames)**. GitHub Actions automatically builds and publishes the release assets.

`versions.json` pins each version to its reviewed source commit and expected automated test count. Versioned release notes live beside it. Existing public releases and their assets are never overwritten by the workflow.

The **Publish PlexonKeys releases** workflow runs when the release manifest, notes, publisher script, or workflow changes on `main`. It can also be run manually from GitHub Actions. Both builds must pass before any publishing job starts. Only the publishing jobs request repository write access.

Each release contains its installable `PlexonKeys-VERSION.jar` and a `SHA256SUMS.txt` checksum. Assets are uploaded to a draft, checked against GitHub's SHA-256 digests, and then published. The manifest explicitly identifies the latest release.

For a future version, finish and verify its source, pin the complete commit SHA, add its release notes and test count to `versions.json`, and update the latest flag. Do not retag or replace an existing public release; publish a new version instead.
