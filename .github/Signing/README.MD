# Release signing

The release APK is signed with a keystore that is **never committed**. It lives
in GitHub Secrets and is written to disk only for the duration of a CI run.

`app/build.gradle.kts` reads four **environment variables**:

| variable | what it is |
|---|---|
| `RELEASE_STORE_FILE` | path to the `.jks` (set to `release.keystore` in CI) |
| `RELEASE_STORE_PASSWORD` | keystore password |
| `RELEASE_KEY_ALIAS` | key alias |
| `RELEASE_KEY_PASSWORD` | key password |

If `RELEASE_STORE_FILE` is missing or the file does not exist, the release
build stays **unsigned** and the workflow still produces a debug APK.

## One-time setup

### 1. Generate the keystore

Run the **Build signing key** workflow from the Actions tab, or run
`generate-keystore.sh` on a computer:

    ./generate-keystore.sh pulsehorizon

You get `release.keystore` and `release.keystore.base64`.

### 2. Add four repository secrets

Settings -> Secrets and variables -> Actions -> New repository secret:

    RELEASE_KEYSTORE_BASE64     contents of release.keystore.base64
    RELEASE_STORE_PASSWORD      the store password you chose
    RELEASE_KEY_ALIAS           the alias you chose
    RELEASE_KEY_PASSWORD        the key password you chose

### 3. Keep the keystore safe

**This file is your app's identity.** Anyone holding it can publish an update
that Android accepts as yours.

- keep it off GitHub
- keep a backup somewhere you will still have it in five years
- if you lose it you cannot update an app already published under it

## What CI does

1. writes `release.keystore` from `RELEASE_KEYSTORE_BASE64`
2. exports the four variables into `$GITHUB_ENV`
3. runs `./gradlew assembleRelease`
4. uploads `app/build/outputs/apk/release/*.apk`

## Do not

- commit `release.keystore`, `*.jks`, or `keystore.properties`
- print the base64 into a workflow log
- leave the `release-keystore` artifact in storage after downloading it
