# Chazlaunch widgets (OWL)

The home screen is `desktop.owl` — a dark, monospace "dev rice" desktop with a
status bar, app cards and a dock (the dock's ⬇ opens `chaz-pkg`, the Nix
package manager, and ⚙ opens Options). `clock.owl` and `drawer.owl` are smaller
examples.

Widgets are written in **OWL**, the on-device declarative widget language from
[OmaLauncher](https://github.com/SimonSchubert/omalauncher) (`owl/`, MIT © 2026
Simon Schubert and contributors). Chazlaunch ships an independent,
**compatible subset** — a Kotlin parser + reactive runtime + Compose renderer
in `com.chazlaunch.owl` — not a port of the upstream module.

Each `*.owl` file is one widget:

```owl
meta { owl: 1 }                 # language version (optional)

widget "name" {
  size: 6x2                     # columns x rows on the 6-wide home grid
  page: 1                       # home page (optional)
  order: 1                      # placement order (optional)

  source clock = clock          # built-in: clock
  source apps  = apps           # built-in: launcher app list (.name/.command/.icon)
  source up    = cmd("uptime -p") every 60s   # runs in the guest (if a runner is wired)
  state  taps  = 0 persist      # mutable, handler-written value
  let    label = "taps: ${taps}"

  onTap: taps = taps + 1        # widget-level handler

  Column {
    Text   { text: "${clock.time}"; style: title }
    Text   { text: "${label}"; color: accent }
    for app in apps {
      Button { text: "${app.name}"; onTap: run(app.command) }
    }
  }
}
```

## Supported in this subset

- **Headers:** `size`, `page`, `order`.
- **Declarations:** `source` (`clock`, `apps`, `cmd("…") every Ns`), `state …
  [persist]`, `let`.
- **Nodes:** `Column`, `Row`, `Box`, `Grid`/`Flow` (stacked), `Text`, `Button`,
  `Progress`, `Spacer`; `for x in list { }`; `if cond { } else { }`.
- **Layout props:** `grow` (fill the screen), `fill` (fill width), `weight`
  (share of a Row/Column), `align` (`center`/`end`/`top`/`bottom`), `spacing`,
  `padding`, `width`, `height`, `size`.
- **Style props:** `color`, `background`, `radius`, `style`
  (`title`/`muted`/`accent`), `mono` (monospace), `fontSize`, `bold`, `value`
  (Progress). Colors are theme tokens (`accent`, `foreground`, `muted`,
  `surface`, `red`/`green`/`blue`…) **or hex** like `#5EE6C5`.
- **`onTap` works on any node** (cards, rows, boxes), not just `Button`.
- **Expressions:** `"${…}"` interpolation, `+ - * /`, comparisons, `&& || !`,
  `??`, calls `upper/lower/len`, member access (`app.name`, `clock.date`).
- **Actions (handlers):** assignment (`x = x + 1`), `run("cmd")`,
  `terminal("prog")`, `url("…")`.

Richer upstream OWL features (Ring, Sparkline, Shader, Canvas, `http`/`stream`
sources, `map`/`filter`/`sortBy`, animations) aren't implemented yet; unknown
nodes render their children so layouts still survive.
