# Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
# Appends the license texts of the npm packages bundled in mermaid.js to mermaid.js.LICENSE.txt.
#
# Terser's mermaid.js.LICENSE.txt holds only the license comments it found in the code (DOMPurify, vuex, ...), not the
# licenses of mermaid, d3, cytoscape, KaTeX and the other bundled packages. The packages are read from the source map,
# their license files from the build's node_modules.
#
# Usage: python3 appendBundledLicenses.py <mermaid.js.map> <node_modules dir> <mermaid.js.LICENSE.txt>

import json
import os
import re
import sys

source_map, node_modules, license_txt = sys.argv[1:4]

package_dirs = set()
for source in json.load(open(source_map))["sources"]:
  # The innermost node_modules entry is the package the source belongs to (d3-sankey/node_modules/d3-shape is d3-shape)
  match = re.search(r"^.*node_modules/((?:@[^/]+/)?[^/]+)", source)
  if match:
    prefix = source[:match.end()]
    package_dirs.add(os.path.join(node_modules, prefix[prefix.index("node_modules/") + len("node_modules/"):]))

sections = []
for package_dir in sorted(package_dirs):
  package = json.load(open(os.path.join(package_dir, "package.json")))
  files = sorted(f for f in os.listdir(package_dir) if re.match(r"(?i)^(licen[cs]e|copying)(\.(md|txt))?$|^LICENSE-", f))
  if not files:
    sys.exit(f"No license file in {package_dir}")
  texts = "\n".join(open(os.path.join(package_dir, f), encoding="utf-8").read().strip() + "\n" for f in files)
  sections.append(f"--- {package['name']} {package['version']} ({package.get('license', 'see below')}) ---\n\n{texts}")

with open(license_txt, "a", encoding="utf-8") as out:
  out.write("\n\nLicenses of the npm packages bundled in mermaid.js\n\n")
  out.write("\n".join(sections))
