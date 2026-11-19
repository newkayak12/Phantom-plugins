# Phantom Strings

`dev.phantom.strings` · [한국어](./KOR.md)

Text conversions on each selection, or on the identifier at the caret. Alt+M opens them in a popup with speed search;
they are also under **Edit | Strings** and in the editor's context menu. Every caret gets its own conversion.

- **Case:** Switch Case goes camelCase → snake_case → SCREAMING_SNAKE_CASE → kebab-case → PascalCase, skipping a style
  that changes nothing; or convert straight to one of them, dot.case, words with spaces, UPPER or lower case. Words are
  found at case changes, acronyms (`HTTPServer` → `http_server`) and `_ - .` and spaces. Without a selection, the
  identifier at the caret.
- **Escape and encode** (selection): Java/Kotlin string, HTML/XML, URL, Base64, both ways. Input that cannot be decoded
  is left as it was, with a hint.
- **Lines** (selection): sort (natural order, so `a2` before `a10`), sort descending, reverse, shuffle, remove duplicates,
  remove blank lines, trim.

Replaces the StringManipulation plugin (Phantom does not load both).
