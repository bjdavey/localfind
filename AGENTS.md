# AGENTS.md

Instructions for AI assistants working on this repository. Read this before every task.

## 1. Simplicity First (Most Important)

- **KISS** - Keep It Simple. Choose the simplest solution that works.
- **YAGNI** - You Aren't Gonna Need It. Build only what the current task needs. No "future-proofing".
- **No over-engineering.** No extra layers, abstractions, interfaces, patterns, or libraries unless the task truly requires them.
- **Prefer fewer files, fewer dependencies, and less code.**
- If two solutions work, pick the one that is easier to read and delete.
- When unsure, ask instead of adding complexity.

## 2. One TODO Item at a Time

- **Do NOT attempt to implement the entire system.** Work only on a single item from [TODO.md](TODO.md).
- Implement only the current item, nothing more. Do not start the next item.
- New ideas go into the **Backlog** section of [TODO.md](TODO.md), not into code.

## 3. Workflow per TODO Item

1. **Implement:** Write only what is needed for the current item.
2. **Unit test:** If it contains pure business/math logic, add a simple Unit test, no heavy mocks.
3. **Verify:** Run tests locally (for example ./gradlew test) then verify on device/emulator to verify end-to-end.
4. **Complete:** Mark `[x]` in [TODO.md](TODO.md) and stop.

## 4. Ask Before Acting

- Ask the user before making architecture or tech-stack decisions.
- Ask before adding any new dependency.
- Ask when requirements are unclear.

## 5. Project Rules

- **100% offline.** No network calls, no cloud APIs, no telemetry, no network permissions.
- Tech choices are defined in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). Do not invent them.
- Product scope is defined in [docs/PRD.md](docs/PRD.md). Do not add features outside it.
- Keep documentation short and up to date when code changes.
