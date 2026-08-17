<!-- TRELLIS:START -->

# Trellis Instructions

These instructions are for AI assistants working in this project.

Use the `/trellis:start` command when starting a new session to:
- Initialize your developer identity
- Understand current project context
- Read relevant guidelines

Use `@/.trellis/` to learn:
- Development workflow (`workflow.md`)
- Project structure guidelines (`spec/`)
- Developer workspace (`workspace/`)

Keep this managed block so 'trellis update' can refresh the instructions.

<!-- TRELLIS:END -->

## Project Delivery Preference

- For development tasks, the default completion loop is: implement, test, deploy to the project environment, and verify the deployed URL. Do not stop after local tests unless the user explicitly asks not to deploy or deployment is blocked.
