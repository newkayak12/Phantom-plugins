# Startup cost

Taken from the Phantom repository (`docs/phantom/DEVELOPMENT.md` 1.5). Phantom's static gate fails when the core adds a
startup activity; plugins are held to the same standard by review.

- A plugin works only when the project uses its technology; detect that cheaply (file type, a library in the project
  model) before doing anything else.
- No new `postStartupActivity`, `backgroundPostStartupActivity` or application-level preloading unless the feature
  cannot work without it; the PR says why and what it costs.
- No EDT work and no network on the startup or project-open path. Long work runs in the background and updates
  incrementally instead of rescanning.
