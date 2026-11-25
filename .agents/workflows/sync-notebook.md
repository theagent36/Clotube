---
description: Bundle Clotube Android source code into clotube_source_bundle.md and replace it in the Clotube Gemini Notebook.
---

Role: Principal Android Build & Automation Engineer.
Task: Export all Kotlin source files (`.kt`), Gradle Kotlin scripts (`.kts`), and `AndroidManifest.xml` from `app/src/main` into a single, complete Markdown bundle named `clotube_source_bundle.md` in the project root directory, then replace the old source in the `Clotube` Gemini Notebook.

### Phase 1: Generate & Verify Source Bundle
1. Traversal:
   - Recursively scan `app/src/main` (along with root `build.gradle.kts` and `app/build.gradle.kts`) for all `.kt`, `.kts`, and `AndroidManifest.xml` files.
   - Do NOT omit file contents or leave placeholder stubs.
2. Structure:
   - Start the file with `# Clotube Android Source Code Bundle`.
   - Prefix every file with `## File: <relative_path>`.
   - Wrap the full source of each file in a language-appropriate markdown code block fence.
3. Execution & Verification:
   - Run a script in the workspace terminal to generate `clotube_source_bundle.md`.
   - Verify that the resulting file size is greater than 100 KB and contains the full class implementations of `MainActivity.kt`, `VideoViewModel.kt`, and `ClotubeBootScreen.kt`.
   - Output the file size in KB and total file count in the terminal before proceeding.

### Phase 2: Sync to Gemini Notebook ("Clotube")
Using the `gemini-notebook-mcp` tool, perform the following steps in my Gemini Notebook named `Clotube`:
1. List all existing sources in the `Clotube` notebook.
2. If any existing source is named `clotube_source_bundle.md`, delete it.
3. Upload the newly generated local file `clotube_source_bundle.md` from the workspace root as a new source.
4. List the notebook's sources again to confirm `clotube_source_bundle.md` is uploaded and indexed.