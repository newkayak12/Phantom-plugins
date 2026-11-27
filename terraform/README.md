# Phantom for Terraform

`dev.phantom.terraform` · [한국어](./KOR.md)

Editing support for Terraform configuration (`.tf`) and variable definition (`.tfvars`) files:

- Highlighting of strings, numbers, comments, block types and attribute names, including the expressions inside
  `${...}` and `%{...}` of a string; syntax errors for broken lines, with the rest of the file still parsed.
- Formatting: block bodies, objects and tuples indent by two spaces, and `=` is aligned over consecutive single-line
  attributes, as `terraform fmt` does. Strings, heredocs and comments are kept as written.
- A structure view of blocks and attributes.
- Go to Declaration for `var.`, `local.`, `module.`, `data.` and resource addresses (`aws_instance.web`), and for an
  attribute set in the declaring block (`aws_instance.web.ami`). The declarations are those of all `.tf` files in the
  same directory, as Terraform reads a module. `module.name.output` goes to the `output` block when the module's
  `source` is a local path.
- Find Usages and rename for variables, locals, modules, resources, data sources and outputs.
- A warning for an address with no declaration in the module, and for an output a local module does not declare.
  Iteration names of `for` expressions and `dynamic` blocks, and addresses in `moved` and `removed` blocks, are not
  reported.
- Line comments with `#`, block comments, brace matching.

The plugin reads only the Terraform files of the project and does nothing until one is opened: no startup work, no
network access, no provider or registry downloads, and `terraform` is never run.

Not yet: completion of block types and attributes from provider schemas, attributes that only the provider computes
(such as `id`), modules from a registry or git source, `.tf.json` files and expressions inside heredocs. The plugin
cannot be installed next to the JetBrains Terraform and HCL plugin, since both register the `.tf` file type.

Terraform is a trademark of HashiCorp; this plugin is not affiliated with or endorsed by HashiCorp.
