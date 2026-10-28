# Phantom Icon Pack

`dev.phantom.iconpack` · [한국어](./KOR.md)

Colored file and folder icons by name and extension, drawn from the [Material Icon Theme](https://github.com/material-extensions/vscode-material-icon-theme) by Philipp Kief (MIT, `src/main/resources/icons/material/LICENSE.md`).

- **Files and folders** get the theme's icons by file name, then extension; source, test and resource roots get their own folder colors. Only the icons of files on screen are loaded.
- **Classes by kind:** Java and Kotlin classes show Material icons told apart by shape: class (circle), abstract or sealed class (dashed circle), interface (rounded square), enum (hexagon), annotation (@), record or data class (card), exception (warning triangle, Java), object (diamond, Kotlin); Kotlin's carry the Kotlin mark in the corner. In the structure view, completion and wherever a class is listed; Java classes keep the platform's static/final marks, and both their visibility.
- **Files as their class:** a Java file, and a Kotlin file that declares one class and nothing else, shows its class's kind where the file is shown: the Project view, the navigation bar, editor tabs, Recent Files and Search Everywhere. Other Kotlin files show the Kotlin logo, a Java file without a class the Java cup.
- A deleted or not yet fetched file in the VCS views keeps its icon.

Turn the plugin off to get the platform icons back.

Until 0.3.0 this was bundled with Phantom (`phantom-icons`, id `dev.phantom.icons`). If your Phantom still bundles it, turn **Phantom File Icons** off in Settings → Plugins after installing this one.

**0.3.2**: classes are told apart by shape, not color, and Kotlin joins Java. New kind icons (circle, rounded square, hexagon, @, card, triangle, diamond), Kotlin classes with the Kotlin mark, and files shown by their class's kind instead of the Java cup in a kind's color (0.3.1) or the Kotlin logo.

**0.3.1**: a Java file's cup takes the color of the class it declares, the color of that kind's icon in the structure view, so interfaces, annotations, enums and the rest tell apart in the Project view, editor tabs and the navigation bar. A file with several classes takes the one named as the file.

**0.3.0**: a plugin on the public platform API, outside the Phantom bundle. Java files show the Java cup instead of their class's kind icon, as Kotlin files show the Kotlin logo.

Regenerate the Material icons with `python3 -I tools/generate-material-icons.py ~/.vscode/extensions/pkief.material-icon-theme-<version>`.
