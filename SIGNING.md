# Setting Up Release Signing

This only matters for **release builds** (the ones distributed to users).
Debug builds (for testing) sign themselves automatically — no setup needed.

---

## Step 1 — Generate a keystore file

A keystore is like a password-protected signature file that proves the APK came from you.
You only do this once.

Open a terminal and run this command (Java must be installed — Android Studio includes it):

```bash
keytool -genkey -v \
  -keystore loopymse-release.jks \
  -alias loopymse \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000
```

It will ask you a series of questions:
- **Enter keystore password:** Choose a strong password and remember it
- **Re-enter:** Same password again
- **What is your first and last name?** Your name (or anything)
- **What is your organizational unit?** Press Enter to skip
- **What is your organization?** Press Enter to skip
- **What is your city?** Press Enter to skip
- **What is your state?** Press Enter to skip
- **What is your two-letter country code?** Your country (e.g. `US`, `GB`)
- **Is this correct?** Type `yes`
- **Enter key password for loopymse:** Press Enter to use same password as keystore

This creates a file called `loopymse-release.jks` in your current directory.

> ⚠️ **Back this file up somewhere safe.** If you lose it you can never update your app.
> Do NOT commit it to GitHub.

---

## Step 2 — Convert the keystore to base64

GitHub Secrets can only store text, not binary files. Convert it:

**Mac/Linux:**
```bash
base64 -i loopymse-release.jks | tr -d '\n' > keystore_base64.txt
```

**Windows (PowerShell):**
```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("loopymse-release.jks")) | Out-File keystore_base64.txt
```

Open `keystore_base64.txt` — it's a long string of random-looking characters. Copy all of it.

---

## Step 3 — Add secrets to your GitHub repository

1. Go to your repository on **github.com**
2. Click **Settings** (top tab)
3. In the left sidebar click **Secrets and variables → Actions**
4. Click **New repository secret** for each of these:

| Secret name | What to paste |
|---|---|
| `KEYSTORE_BASE64` | The entire contents of `keystore_base64.txt` |
| `STORE_PASSWORD` | The keystore password you chose in Step 1 |
| `KEY_ALIAS` | `loopymse` (the alias you used) |
| `KEY_PASSWORD` | Same as the keystore password (unless you set a different one) |

---

## Step 4 — Trigger a release build

1. Go to your repo on GitHub
2. Click **Releases** (right sidebar)
3. Click **Create a new release**
4. In "Choose a tag" type `v1.0.0` and click **Create new tag**
5. Give it a title like "Loopy-MseDroid v1.0.0"
6. Click **Publish release**

GitHub Actions automatically:
- Builds a signed release APK
- Attaches it to the release as a downloadable file
- Anyone can download and install it directly

---

## Debug vs Release — what's the difference?

| | Debug | Release |
|---|---|---|
| How to get it | Every push to main → Actions tab → Artifacts | Create a GitHub Release |
| Signed | Auto (debug key) | Yes (your keystore) |
| Installable by others | Only if they enable "unknown sources" | Yes, fully |
| App ID | `com.loopymse.droid.debug` | `com.loopymse.droid` |
| Size | Larger (no optimization) | Smaller (minified) |

For testing during development, debug builds are fine.
For sharing with others, use a release build.
