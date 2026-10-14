# CLAUDE.md

## How to work in this repo

- **Opus is the orchestrator/architect only.** It plans, decides, breaks work into tasks, reviews results and talks to the user. It does not write the implementation itself.
- **Implementation goes to subagents** via the Agent tool:
  - `model: "sonnet"` for writing code, refactors, code review.
  - `model: "haiku"` for simple mechanical work: watching CI (`gh run watch`), fetching logs, small lookups.
- Give each agent a self-contained prompt (files, intended behavior, constraints such as backwards compatibility of `entries.txt`/prefs/CSV), and have it report back. Opus verifies before reporting to the user.
- There is no Android SDK in the cloud container; CI (`Build APK` workflow) is the compile check after each push.
