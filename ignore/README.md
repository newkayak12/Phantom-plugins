# Phantom Ignore Files

`dev.phantom.ignore` · [한국어](./KOR.md)

- `.dockerignore`, `.npmignore`, `.helmignore`, `.prettierignore`, `.eslintignore`, `.stylelintignore`, `.gcloudignore`
  and `.vscodeignore` are edited like `.gitignore`: highlighting, path completion and navigation, comment toggling, and
  the duplicate entry check. They use the platform's own ignore language, which `.gitignore` already has without a plugin.
- **New | Ignore File from Template…** on a directory: pick the file (`.gitignore` or one of the above) and templates
  (Java, Kotlin, Gradle, Maven, IntelliJ, VS Code, macOS, Windows, Linux, Node, Python, Go, Rust, Docker). Each is
  appended under a `### Name ###` header, without the lines the file already has. The templates are adapted from
  [github/gitignore](https://github.com/github/gitignore) (CC0).

Replaces the .ignore plugin (Phantom does not load both).
