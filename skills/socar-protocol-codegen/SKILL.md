---
name: socar-protocol-codegen
description: Generate or update socar-android-library API2 Kotlin data classes from a named socar-protocol branch. Use when a Korean or English request mentions protocol model/data class generation, API2 model updates, or running tools/api2-model/generate.kts for a branch.
---

# SOCAR Protocol Codegen

Use the MCP tool whose unqualified name is `generate_protocol_data_classes`. The MCP server owns generation, Git isolation, change classification, stash creation, conflict handling, and verification.

- Require a `socar-protocol` branch name. Ask only for the branch when it is missing.
- Pass `dryRun: true` when the user asks to preview or inspect changes without modifying the workspace.
- Leave `verify` enabled unless the user explicitly asks to skip verification.
- Call the tool once and report its structured result: resolved commits, target-only files, stash name/OID, conflicts, verification, and cleanup.
- Do not edit `generate.kts`, run the generator through a shell, create a second stash, commit, push, pop/drop a stash, or reproduce the MCP workflow manually.
- If the MCP tool is unavailable, stop and explain that the protocol-codegen MCP must be installed or enabled. Do not silently fall back to direct Git or shell operations.
