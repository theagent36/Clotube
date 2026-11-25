---
description: Safely audit, commit, and push the current Clotube project files to GitHub (main) without publishing a release.
---

# Update GitHub Repository (Source Sync Only)

Act as a Principal Android & DevSecOps Git Automation Architect for **Clotube** (`com.clotube.app`).

**Repository:** `https://github.com/theagent36/Clotube.git`  
**Target Branch:** `main`

---

## Strict Guardrails (Mandatory)

1. **NO GITHUB RELEASES OR TAGS:**
   - Strictly **DO NOT** create Git tags (`git tag`), draft or publish GitHub Releases (`gh release create`), or generate release notes files.
   - Only update the repository source code on `origin/main`.
2. **NO APK BUILDS OR DEVICE INSTALLS:**
   - Strictly **DO NOT** run `./gradlew assembleRelease`, `./gradlew assembleDebug`, `installDebug`, `installRelease`, or `adb install`.
3. **ZERO CREDENTIAL OR PERSONAL DATA LEAKS:**
   - Never stage or commit keystores, signing properties, OAuth client secrets, build outputs, or local source bundles.

---

## Execution Steps

Run the following steps sequentially in the workspace root:

### Step 1: Verify `.gitignore` & Untrack Sensitive Artifacts
1. Inspect `.gitignore` and ensure all of the following patterns are present (append any missing rules without removing existing ones):
   ```gitignore
   # Keystores & signing secrets
   *.jks
   *.keystore
   *.p12
   *.pem
   keystore.properties
   gradle.properties
   local.properties

   # OAuth & environment secrets
   client_secret*.json
   google-services.json
   *oauth*.json
   .env
   .env.*

   # Build outputs & IDE caches
   *.apk
   *.aab
   build/
   app/build/
   .gradle/
   .idea/

   # Local helper scripts & source dumps
   export_bundle.ps1
   clotube_source_bundle.md
   clotube_desktop_source_bundle.md
   RELEASE_NOTES_TMP.md
   ```
2. If any sensitive or build artifact was previously tracked in Git, remove it from the Git index (keeping the local file intact on disk):
   ```bash
   git rm -r --cached --ignore-unmatch keystore.properties local.properties gradle.properties google-services.json export_bundle.ps1 clotube_source_bundle.md clotube_desktop_source_bundle.md RELEASE_NOTES_TMP.md
   ```

### Step 2: Stage Files & Audit Staged Diffs
1. Stage all clean project changes:
   ```bash
   git add .
   ```
2. Run `git status --ignored` and confirm zero `.jks`, `.keystore`, `keystore.properties`, `client_secret*.json`, or `.apk` files appear under `Changes to be committed`.
3. Check if there are any staged changes at all (`git diff --cached --stat`). If nothing is staged, stop and report that the repository is already up to date with `origin/main`.
4. Scan the staged diff for hardcoded personal details or secrets (`@gmail.com`, `ya29.`, `AIza`, `GOCSPX-`, `storePassword`, `keyPassword`, or hardcoded local machine paths like `C:\Users\` or `E:\Miscellaneous\`).
   - If any personal email, token, password, or private path is found in staged code, **STOP immediately**, list the exact file and line, and ask the user before committing.

### Step 3: Commit & Push to `origin/main`
1. Review `git diff --cached --stat` and craft a clear, accurate Conventional Commit message summarizing the actual modified files (e.g., `chore(sync): update project files to latest working state` or a specific `feat(...)` / `fix(...)` summary).
2. Commit the staged changes:
   ```bash
   git commit -m "<generated-conventional-commit-message>"
   ```
3. Ensure branch `main` and remote `origin` are properly configured, rebase cleanly if remote commits exist, and push:
   ```bash
   git branch -M main
   git remote set-url origin https://github.com/theagent36/Clotube.git
   git pull --rebase origin main
   git push -u origin main
   ```

### Step 4: Final Verification
- Run `git rev-parse --short HEAD` and `git status` and output:
  - **Commit Hash & Message**
  - **Branch & Remote:** `main` -> `https://github.com/theagent36/Clotube.git`
  - **Working Tree Status:** Confirmed clean with zero releases created.