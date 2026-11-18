# Coding (Karpathy principles)

Taken from Andrej Karpathy's notes on common mistakes coding agents make. They favor care over speed; a one-line fix
does not need the full process.

## 1. Think before coding
- State assumptions before editing. If a request can be read two ways, name both and ask; do not pick silently.
- If a simpler approach exists, say so. Push back when the request looks wrong.
- When something is unclear, stop and name what is unclear instead of guessing.

## 2. Simplicity first
- Write the least code that solves the problem. No features, options or abstractions nobody asked for.
- No error handling for cases that cannot happen. No abstraction for code used once.
- If 200 lines could be 50, rewrite it. Test: would a senior engineer call it overcomplicated?

## 3. Surgical changes
- Every changed line traces back to the request.
- Do not reformat, rename or "improve" adjacent code, comments or style. Match the existing style.
- Remove imports, variables and functions your change made unused. Mention unrelated dead code, do not delete it.

## 4. Goal-driven execution
- Turn the task into a check that can pass or fail: "fix the bug" becomes "a test reproduces it, then passes".
- For multi-step work, write the plan as steps, each with how it is verified.
- Loop until the check passes. Report what was run and its result; a step not run is reported as not run.
