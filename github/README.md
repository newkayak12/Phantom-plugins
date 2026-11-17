# Phantom GitHub

`dev.phantom.github` · [한국어](./KOR.md)

GitHub in the IDE: GitHub and GitHub Enterprise accounts (OAuth or token), clone, Share Project on GitHub, pull requests (list, review with comments in the diff and editor, create, merge), gists, Open on GitHub, and the GitHub Actions workflow schemas.

Log In via GitHub (browser login) appears only when a GitHub OAuth client id is set, built in or with
`-Dphantom.github.oauth.client.id`; without one the account menu, the clone dialog and the pull request tool window
offer token login only.

Based on the IntelliJ Community GitHub plugin (`plugins/github`, Apache 2.0), under Phantom's own id. `org.jetbrains.plugins.github`
stays as an alias and the content module names stay the IntelliJ ones, so plugins and bundled modules that depend on them
keep working. Phantom bundles this build instead of the IntelliJ plugin.
