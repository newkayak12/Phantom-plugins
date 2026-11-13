# Phantom Java Internationalization

`dev.phantom.java-i18n` · [한국어](./KOR.md)

Internationalization for Java: inspections for hard-coded strings, unresolved property keys, wrong message format
parameter counts, inconsistent resource bundles, duplicate string literals, dialog title capitalization, unsupported and
non-Basic-Latin characters and suspicious locales; I18nize (move a string literal into a properties file); folding of
`getMessage("key")` calls to the message text with Edit Property Value; and references, find usages, rename and inline
between Java code and properties keys.

Based on the IntelliJ Community Java Internationalization plugin (`plugins/java-i18n`, Apache 2.0), under Phantom's own
id. `com.intellij.java-i18n` stays as an alias and its extension points keep their `com.intellij.java-i18n.*` names, so
plugins that depend on it keep working. Phantom bundles this build instead of the IntelliJ plugin.
