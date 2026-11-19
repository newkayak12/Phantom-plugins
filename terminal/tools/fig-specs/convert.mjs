// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
//
// Writes the terminal plugin's bundled command specs (src/main/resources/completionSpecs) from the Fig autocomplete
// specs, @withfig/autocomplete (MIT, https://github.com/withfig/autocomplete), at the version pinned in package.json.
//
//   cd terminal/tools/fig-specs && npm ci && node convert.mjs
//
// The output is committed, so the Gradle build needs neither Node nor the network. Run this again after changing the
// pinned version, and commit what it writes.
//
// Output, in the form of dev.phantom.terminal.completion.spec (ShellCommandSpecJson.kt):
//   all_commands.json    every top-level command: names, description and loadSpec (the light specs)
//   <command>.json       its full spec
//   <command>/<sub>.json the subcommand specs that Fig splits off (aws/s3, az/2.53.0/account, ...), loaded when needed
//
// Fig is JavaScript, its specs are TypeScript objects that can hold functions; only what JSON can hold is kept:
//   kept     names, descriptions, display names, insert values, priorities, hidden flags, subcommands, options (required,
//            persistent, separator, repeatable, exclusiveOn, dependsOn), arguments (optional, variadic, command), static
//            suggestions, the filepaths/folders templates (files and folders are suggested), parser directives, and
//            loadSpec references to other spec files
//   dropped  script and custom generators (git branches, docker containers, npm scripts, ...: suggestions that come
//            from running a command), generateSpec (specs built at run time, e.g. from `--help`), loadSpec functions,
//            additionalSuggestions, icons, history/help templates, isScript/isModule arguments, argument defaults,
//            hidden suggestions, and the scoped npx package specs (@vue/cli, ...)
// Versioned specs (az, heroku, ...) are written as their newest version.

import fs from 'node:fs'
import path from 'node:path'
import {fileURLToPath, pathToFileURL} from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const figRoot = path.join(here, 'node_modules', '@withfig', 'autocomplete')
const figBuild = path.join(figRoot, 'build')
const outDir = path.resolve(process.argv[2] ?? path.join(here, '..', '..', 'src', 'main', 'resources', 'completionSpecs'))

const pinned = JSON.parse(fs.readFileSync(path.join(here, 'package.json'), 'utf8')).dependencies['@withfig/autocomplete']
const installed = JSON.parse(fs.readFileSync(path.join(figRoot, 'package.json'), 'utf8')).version
if (pinned !== installed) {
  throw new Error(`@withfig/autocomplete ${installed} is installed, package.json pins ${pinned}: run npm ci`)
}

const stats = {
  scriptGenerators: 0, generateSpec: 0, loadSpecFunctions: 0, unresolvedLoadSpecs: new Set(), additionalSuggestions: 0,
  hiddenSuggestions: 0, failed: [],
}

const index = await import(pathToFileURL(path.join(figBuild, 'index.js')))
const versioned = new Set(index.diffVersionedCompletions)
// A top-level command is a spec file of its own name; names with a slash are subcommand specs, helper modules or
// scoped npx packages
const topLevel = [...new Set([...index.default, ...versioned])].filter(n => !n.includes('/') && !n.startsWith('@')).sort()
const topLevelSet = new Set(topLevel)

// ---- loading Fig specs ----

const importModule = file => import(pathToFileURL(file))

const semver = v => v.split('.').map(Number)
const compareVersions = (a, b) => {
  const x = semver(a), y = semver(b)
  for (let i = 0; i < Math.max(x.length, y.length); i++) if ((x[i] ?? 0) !== (y[i] ?? 0)) return (x[i] ?? 0) - (y[i] ?? 0)
  return 0
}

/** The newest version of a versioned spec: the newest version file with its later versions' diffs applied */
async function loadVersioned(name) {
  const dir = path.join(figBuild, name)
  const versions = fs.readdirSync(dir).map(f => f.match(/^(\d+(?:\.\d+)*)\.js$/)?.[1]).filter(Boolean).sort(compareVersions)
  const newest = versions.at(-1)
  const module = await importModule(path.join(dir, `${newest}.js`))
  const spec = structuredCloneSpec(module.default)
  const diffs = Object.entries(module.versions ?? {}).sort(([a], [b]) => compareVersions(a, b))
  for (const [, diff] of diffs) applyDiff(spec, diff)
  return spec
}

// Specs hold functions, which structuredClone refuses; a diff must not change the module's object for later imports
function structuredCloneSpec(value) {
  if (Array.isArray(value)) return value.map(structuredCloneSpec)
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, structuredCloneSpec(v)]))
  }
  return value
}

const nameList = n => (Array.isArray(n) ? n : [n]).filter(x => typeof x === 'string' && x.length > 0)

/** A version diff names the subcommands and options it changes; a named one is merged, `remove` takes it out */
function applyDiff(target, diff) {
  for (const [key, value] of Object.entries(diff)) {
    if ((key === 'subcommands' || key === 'options') && Array.isArray(value)) {
      const list = target[key] ?? (target[key] = [])
      for (const change of value) {
        const names = nameList(change.name)
        const i = list.findIndex(item => nameList(item.name).some(n => names.includes(n)))
        if (change.remove) {
          if (i >= 0) list.splice(i, 1)
        }
        else if (i >= 0) applyDiff(list[i], change)
        else list.push(structuredCloneSpec(change))
      }
    }
    else if (key !== 'remove') target[key] = structuredCloneSpec(value)
  }
}

async function loadFig(ref) {
  if (versioned.has(ref)) return loadVersioned(ref)
  const file = path.join(figBuild, `${ref}.js`)
  if (!fs.existsSync(file)) return null
  const spec = (await importModule(file)).default
  return spec && typeof spec === 'object' ? spec : null
}

function figSpecExists(ref) {
  return versioned.has(ref) || fs.existsSync(path.join(figBuild, `${ref}.js`))
}

// ---- conversion ----

const str = v => (typeof v === 'string' && v.length > 0 ? v : undefined)
const yes = v => (v === true ? true : undefined)
const strings = v => {
  const list = (Array.isArray(v) ? v : [v]).filter(x => typeof x === 'string' && x.length > 0)
  return list.length > 0 ? list : undefined
}
const list = v => (v === undefined || v === null ? [] : Array.isArray(v) ? v : [v])
const nonEmpty = a => (a.length > 0 ? a : undefined)
const priority = v => {
  if (typeof v !== 'number' || !Number.isFinite(v)) return undefined
  const p = Math.round(Math.min(100, Math.max(0, v)))
  return p === 50 ? undefined : p
}
const TEMPLATES = new Set(['filepaths', 'folders'])

/**
 * The manager loads a loadSpec reference with the JSON provider of its first path segment, so the reference must start
 * with a top-level command, and Fig must have the file
 */
function resolvableLoadSpec(ref) {
  return topLevelSet.has(ref.split('/')[0]) && figSpecExists(ref)
}

function convertCommand(fig, queue) {
  let spec = fig
  if (spec.loadSpec && typeof spec.loadSpec === 'object') spec = {...spec, ...spec.loadSpec, loadSpec: undefined}
  if (typeof spec.loadSpec === 'function') stats.loadSpecFunctions++
  if (spec.generateSpec) stats.generateSpec++
  if (spec.additionalSuggestions) stats.additionalSuggestions++

  let loadSpec
  if (typeof spec.loadSpec === 'string') {
    if (resolvableLoadSpec(spec.loadSpec)) {
      loadSpec = spec.loadSpec
      queue.add(loadSpec)
    }
    else stats.unresolvedLoadSpecs.add(spec.loadSpec)
  }

  const directives = spec.parserDirectives ?? {}
  const parserDirectives = {
    flagsArePosixNoncompliant: yes(directives.flagsArePosixNoncompliant),
    optionsMustPrecedeArguments: yes(directives.optionsMustPrecedeArguments),
    optionArgSeparators: strings(directives.optionArgSeparators),
  }
  return {
    names: nameList(spec.name),
    displayName: str(spec.displayName),
    description: str(spec.description),
    insertValue: str(spec.insertValue),
    priority: priority(spec.priority),
    isHidden: yes(spec.hidden),
    requiresSubcommand: yes(spec.requiresSubcommand),
    parserDirectives: Object.values(parserDirectives).some(v => v !== undefined) ? parserDirectives : undefined,
    loadSpec,
    subcommands: nonEmpty(list(spec.subcommands).filter(isNamed).map(s => convertCommand(s, queue))),
    options: nonEmpty(list(spec.options).filter(isNamed).map(convertOption)),
    args: nonEmpty(list(spec.args).filter(isObject).map(convertArg)),
  }
}

const isObject = v => v !== null && typeof v === 'object'
const isNamed = v => isObject(v) && nameList(v.name).length > 0

function convertOption(fig) {
  const separator = typeof fig.requiresSeparator === 'string' ? str(fig.requiresSeparator)
                    : fig.requiresSeparator === true || fig.requiresEquals === true ? '=' : undefined
  const repeatTimes = fig.isRepeatable === true ? 0
                      : typeof fig.isRepeatable === 'number' && fig.isRepeatable > 1 ? Math.round(fig.isRepeatable) : undefined
  return {
    names: nameList(fig.name),
    displayName: str(fig.displayName),
    description: str(fig.description),
    insertValue: str(fig.insertValue),
    priority: priority(fig.priority),
    isHidden: yes(fig.hidden),
    isRequired: yes(fig.isRequired),
    isPersistent: yes(fig.isPersistent),
    separator,
    repeatTimes,
    exclusiveOn: strings(fig.exclusiveOn),
    dependsOn: strings(fig.dependsOn),
    args: nonEmpty(list(fig.args).filter(isObject).map(convertArg)),
  }
}

function convertArg(fig) {
  const templates = new Set(list(fig.template).filter(t => TEMPLATES.has(t)))
  for (const generator of list(fig.generators).filter(isObject)) {
    const generatorTemplates = list(generator.template).filter(t => TEMPLATES.has(t))
    generatorTemplates.forEach(t => templates.add(t))
    if (generatorTemplates.length === 0) stats.scriptGenerators++
  }
  const suggestions = []
  for (const s of list(fig.suggestions)) {
    if (typeof s === 'string' && s.length > 0) suggestions.push({names: [s]})
    else if (isNamed(s)) {
      if (s.hidden) {
        stats.hiddenSuggestions++
        continue
      }
      suggestions.push({
        names: nameList(s.name),
        displayName: str(s.displayName),
        description: str(s.description),
        insertValue: str(s.insertValue),
        priority: priority(s.priority),
      })
    }
  }
  return {
    name: str(fig.name),
    isOptional: yes(fig.isOptional),
    isVariadic: yes(fig.isVariadic),
    optionsCanBreakVariadicArg: fig.optionsCanBreakVariadicArg === false ? false : undefined,
    isCommand: yes(fig.isCommand),
    suggestions: nonEmpty(suggestions),
    templates: nonEmpty([...templates].sort()),
  }
}

// undefined fields are left out by JSON.stringify
const toJson = value => JSON.stringify(value)

// ---- writing ----

fs.rmSync(outDir, {recursive: true, force: true})
fs.mkdirSync(outDir, {recursive: true})

function write(ref, value) {
  const file = path.join(outDir, `${ref}.json`)
  fs.mkdirSync(path.dirname(file), {recursive: true})
  fs.writeFileSync(file, toJson(value) + '\n')
}

const lightSpecs = []
const queue = new Set()
for (const name of topLevel) {
  try {
    const fig = await loadFig(name)
    if (!fig) continue
    const spec = convertCommand(fig, queue)
    const names = [name, ...spec.names.filter(n => n !== name)]
    spec.names = names
    spec.loadSpec = undefined  // the full spec of a top-level command is itself
    write(name, spec)
    lightSpecs.push({names, description: spec.description, loadSpec: name})
  }
  catch (e) {
    stats.failed.push(`${name}: ${e.message}`)
  }
}

// Subcommand specs, and the specs they in turn refer to
const written = new Set(topLevel)
let subcommandSpecs = 0
while (true) {
  const pending = [...queue].filter(ref => !written.has(ref))
  if (pending.length === 0) break
  for (const ref of pending) {
    written.add(ref)
    try {
      const fig = await loadFig(ref)
      if (!fig) {
        stats.failed.push(`${ref}: not a spec`)
        continue
      }
      const spec = convertCommand(fig, queue)
      if (spec.loadSpec === ref) spec.loadSpec = undefined
      write(ref, spec)
      subcommandSpecs++
    }
    catch (e) {
      stats.failed.push(`${ref}: ${e.message}`)
    }
  }
}

// A loadSpec whose file could not be written would make the manager log an error: none may be left
const missing = [...queue].filter(ref => !fs.existsSync(path.join(outDir, `${ref}.json`)))
if (missing.length > 0) throw new Error(`loadSpec references without a file: ${missing.join(', ')}`)

write('all_commands', lightSpecs)

console.log(`@withfig/autocomplete ${installed} -> ${path.relative(process.cwd(), outDir) || outDir}`)
console.log(`  ${lightSpecs.length} commands, ${subcommandSpecs} subcommand spec files`)
console.log(`  dropped: ${stats.scriptGenerators} script/custom generators, ${stats.generateSpec} generateSpec, ` +
            `${stats.loadSpecFunctions} loadSpec functions, ${stats.additionalSuggestions} additionalSuggestions, ` +
            `${stats.hiddenSuggestions} hidden suggestions, ${stats.unresolvedLoadSpecs.size} loadSpec references outside the specs`)
if (stats.failed.length > 0) {
  console.log(`  failed (${stats.failed.length}):\n    ${stats.failed.join('\n    ')}`)
  process.exitCode = 1
}
