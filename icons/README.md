# Phantom Icon Pack

`dev.phantom.iconpack` · [한국어](./KOR.md)

Colored file and folder icons by name and extension, drawn from the [Material Icon Theme](https://github.com/material-extensions/vscode-material-icon-theme) by Philipp Kief (MIT, `src/main/resources/icons/material/LICENSE.md`).

- **Files and folders** get the theme's icons by file name, then extension; source, test and resource roots get their own folder colors. Only the icons of files on screen are loaded.
- **Language logos:** a Kotlin file shows the Kotlin logo and a Java file the Java cup, also where the file is shown as the class it declares: the Project view, the navigation bar, editor tabs, Recent Files and Search Everywhere.
- **Java classes** show Material icons by kind (class, abstract class, interface, enum, record, annotation, exception) in the structure view, completion and wherever a class is listed, with the platform's static/final marks and visibility kept.
- A deleted or not yet fetched file in the VCS views keeps its icon.

Turn the plugin off to get the platform icons back.

Until 0.3.0 this was bundled with Phantom (`phantom-icons`, id `dev.phantom.icons`). If your Phantom still bundles it, turn **Phantom File Icons** off in Settings → Plugins after installing this one.

**0.3.0**: a plugin on the public platform API, outside the Phantom bundle. Java files show the Java cup instead of their class's kind icon, as Kotlin files show the Kotlin logo.

Regenerate the Material icons with `python3 -I tools/generate-material-icons.py ~/.vscode/extensions/pkief.material-icon-theme-<version>`.
