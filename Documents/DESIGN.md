<!-- SEED: established with the user before implementation; re-run $impeccable document once there's code to capture the actual tokens and components. -->

---
name: AutoQE Studio
description: Autonomous Java test-generation workspace — diagnostic clarity meets technical documentation.
colors:
  surface-base: "#0d1117"
  surface-raised: "#161b22"
  surface-panel: "#1c2333"
  accent-cyan: "#22d3ee"
  accent-cyan-muted: "#0e7490"
  text-primary: "#e6edf3"
  text-secondary: "#8b949e"
  text-muted: "#484f58"
  border-subtle: "#30363d"
  border-emphasis: "#484f58"
  status-pass: "#3fb950"
  status-fail: "#f85149"
  status-warn: "#d29922"
  status-healing: "#a371f7"
typography:
  display:
    fontFamily: "JetBrains Mono, Fira Code, Cascadia Code, monospace"
    fontSize: "1.5rem"
    fontWeight: 600
    lineHeight: 1.25
    letterSpacing: "-0.01em"
  headline:
    fontFamily: "Inter, system-ui, -apple-system, sans-serif"
    fontSize: "1.125rem"
    fontWeight: 600
    lineHeight: 1.4
    letterSpacing: "-0.01em"
  title:
    fontFamily: "Inter, system-ui, -apple-system, sans-serif"
    fontSize: "0.875rem"
    fontWeight: 500
    lineHeight: 1.5
    letterSpacing: "0"
  body:
    fontFamily: "Inter, system-ui, -apple-system, sans-serif"
    fontSize: "0.875rem"
    fontWeight: 400
    lineHeight: 1.6
    letterSpacing: "0"
  label:
    fontFamily: "JetBrains Mono, Fira Code, monospace"
    fontSize: "0.75rem"
    fontWeight: 400
    lineHeight: 1.4
    letterSpacing: "0.02em"
rounded:
  none: "0px"
  sm: "4px"
  md: "6px"
  lg: "10px"
  pill: "9999px"
spacing:
  xs: "4px"
  sm: "8px"
  md: "16px"
  lg: "24px"
  xl: "32px"
  "2xl": "48px"
components:
  button-primary:
    backgroundColor: "{colors.accent-cyan}"
    textColor: "{colors.surface-base}"
    rounded: "{rounded.md}"
    padding: "8px 20px"
  button-primary-hover:
    backgroundColor: "#06b6d4"
  button-ghost:
    backgroundColor: "transparent"
    textColor: "{colors.text-secondary}"
    rounded: "{rounded.md}"
    padding: "8px 16px"
  button-ghost-hover:
    backgroundColor: "{colors.surface-panel}"
    textColor: "{colors.text-primary}"
  status-badge-pass:
    backgroundColor: "rgba(63, 185, 80, 0.12)"
    textColor: "{colors.status-pass}"
    rounded: "{rounded.pill}"
    padding: "2px 10px"
  status-badge-fail:
    backgroundColor: "rgba(248, 81, 73, 0.12)"
    textColor: "{colors.status-fail}"
    rounded: "{rounded.pill}"
    padding: "2px 10px"
  status-badge-healing:
    backgroundColor: "rgba(163, 113, 247, 0.12)"
    textColor: "{colors.status-healing}"
    rounded: "{rounded.pill}"
    padding: "2px 10px"
  card:
    backgroundColor: "{colors.surface-raised}"
    rounded: "{rounded.lg}"
    padding: "{spacing.lg}"
  input:
    backgroundColor: "{colors.surface-base}"
    textColor: "{colors.text-primary}"
    rounded: "{rounded.md}"
    padding: "8px 12px"
---

# Design System: AutoQE Studio

## Overview

**Creative North Star: "The Lab Notebook"**

AutoQE Studio is an operator's workspace for technical users who trust signals over decoration. The design draws from the visual language of scientific documentation and diagnostic tooling — the structured grids of a lab printout, the monochromatic restraint of instrument screens, the colored annotations that mark findings with clinical precision. Everything is dark, dense, and deliberate. Color is reserved for meaning.

The interface is built around a simple hierarchy: structure first, then status, then action. The dark surface base (#0d1117) is not a stylistic choice — it is the resting state of a screen that spends most of its life running background processes. Surfaces rise from the base through subtle lightness steps, never through decoration. The single cyan accent carries both interaction and the system's primary diagnostic voice. Four semantic status colors — green (pass), red (fail), amber (healing/warn), violet (retry/healing) — do exactly one job each.

Type is a two-face system: Inter for prose and UI labels, JetBrains Mono for all code-adjacent content. The monospaced face is not cosmetic — it signals "this is machine output" wherever it appears, from status labels to generated test snippets. The pairing creates a natural information hierarchy without weight tricks.

**Key Characteristics:**
- Dark-mode-only, high-density operator UI
- Single cyan accent; four semantic status signals; neutral base
- Two-face type system (Inter + JetBrains Mono); mono everywhere code or status is present
- Ghost borders — control surfaces are almost invisible until interacted with
- Flat base surfaces, subtle shadow on raised panels and modals
- Compressed spacing scale; information-dense without crowding

## Colors

A nearly monochromatic dark neutral ramp with one diagnostic accent (cyan) and four hard-semantic status signals. Nothing else.

### Primary

- **Diagnostic Cyan** (`#22d3ee`): The singular interactive accent. Used on primary CTAs ("Generate Tests"), active states, links, and selected items. Its rarity is the system's trust signal.
- **Muted Teal** (`#0e7490`): The subdued form of the accent. Focus rings, hover states on ghost elements, and progress indicators.

### Neutral

- **Deep Void** (`#0d1117`): Page and app shell base. The canvas everything else sits on.
- **Raised Surface** (`#161b22`): Primary card and panel background. The first lightness step above the void.
- **Panel Surface** (`#1c2333`): Secondary panels, sidebars, popovers, dialogs.
- **Bone White** (`#e6edf3`): Primary readable text. All headings, primary body copy, code output.
- **Weathered Ash** (`#8b949e`): Secondary text — labels, descriptions, placeholder text, secondary metadata.
- **Faded Charcoal** (`#484f58`): Disabled text and tertiary labels. Barely-there state.
- **Hairline Border** (`#30363d`): Default borders and dividers. Defines surface edges without competing.
- **Emphasis Border** (`#484f58`): Focused or active borders, table headers, emphasized separators.

### Status (Semantic — not decorative)

- **Test Pass Green** (`#3fb950`): PASSED status badge, passing test indicators, success toasts.
- **Failure Red** (`#f85149`): FAILED status, compile error indicators, error toasts.
- **Healing Violet** (`#a371f7`): Self-healing retry in progress; FAILED_AFTER_HEALING state.
- **Warning Amber** (`#d29922`): DRY_RUN, PRE_COMPILE_FAILED, rate limit warnings.

### Named Rules

**The One Signal Rule.** Cyan (#22d3ee) is the only decorative accent in the system. It appears on interactive controls and active selections — nothing else. Applying it to illustration, dividers, or visual structure dilutes its authority and confuses what is clickable.

**The Hard-Semantic Rule.** Green, red, violet, and amber are locked to their one semantic role. Red is never a stylistic accent. Green is never a brand color. Mixing roles — a green hover state, a red brand moment — corrupts the diagnostic vocabulary.

## Typography

**Display / Mono Font:** JetBrains Mono (fallback: Fira Code, Cascadia Code, `monospace`)
**UI / Body Font:** Inter (fallback: `system-ui, -apple-system, sans-serif`)

**Character:** The pairing reads like a technical document — Inter handles prose with quiet authority; JetBrains Mono signals machine output and code with unambiguous precision. There is no display face with editorial personality; the personality lives in density and contrast, not in letterforms.

### Hierarchy

- **Display** (JetBrains Mono, 600, 1.5rem, lh 1.25): Top-level UI labels — product name, section headers on empty states. Mono makes even section titles feel like terminal output.
- **Headline** (Inter, 600, 1.125rem, lh 1.4): Panel titles, modal headings, card group labels.
- **Title** (Inter, 500, 0.875rem, lh 1.5): Component labels, named sections within panels, fieldset legends.
- **Body** (Inter, 400, 0.875rem, lh 1.6): Primary prose — descriptions, log output formatted as text, diagnostic messages. Max 80ch.
- **Label** (JetBrains Mono, 400, 0.75rem, ls +0.02em): Status badges, metric values, file paths, pipeline step names, version numbers. Anything that is machine-generated or machine-measured.

### Named Rules

**The Mono-for-Machine Rule.** If the content was generated or measured by the system — a class name, a status label, a test count, a file path, a Maven log line — it is set in JetBrains Mono. If a human wrote it as prose, it is set in Inter.

## Layout

The application shell is a single full-viewport dark canvas divided into two primary regions: a narrow left navigation rail and a wide main content area. The main area is subdivided into a top bar (service health + global actions), a bento grid (metrics and class selection), and a lower split workbench (source vs. generated test, agent progress, terminal stream).

The spacing scale is compressed: base unit 4px, standard rhythm 8px and 16px. Panels use 24px internal padding. The grid is fluid, not fixed — column counts reduce on narrower breakpoints with the split workbench collapsing to a tabbed single-column view below ~900px viewport width.

Container widths are uncapped at the app-shell level; the design fills available width on a developer's monitor (commonly 1440–2560px wide).

## Elevation & Depth

The system is flat by default. Surface color alone distinguishes hierarchy — Deep Void → Raised Surface → Panel Surface — with no shadow at rest. Shadows appear only for modals (dialogs, popovers) and floating elements.

### Shadow Vocabulary

- **Modal shadow** (`0 16px 48px rgba(0,0,0,0.6), 0 4px 16px rgba(0,0,0,0.4)`): Dialogs, the directives popover, and the command palette. Maximum emphasis.
- **Panel lift** (`0 4px 12px rgba(0,0,0,0.3)`): Cards that are temporarily elevated — an active drag, a hovered bento card with action.

### Named Rules

**The Flat-By-Default Rule.** Surfaces are flat at rest. A shadow is a state, not a style. Rest state carries color separation only; a shadow on a resting card is decoration disguised as depth.

## Shapes

The form language is gently curved but not rounded. Buttons, inputs, and cards use a consistent `md` radius (6px), giving the interface a controlled-instrument feel rather than a bubbly consumer-app feel. The `sm` radius (4px) appears on status badges and chips. Pills (`9999px`) are reserved for status badges and small metric chips only.

Borders are thin (1px solid) and use `#30363d` by default, stepping up to `#484f58` on focus or active states. No decorative borders or rules that don't serve a functional separation role.

**The No-Decoration Border Rule.** A border signals a surface edge or an interactive state — never visual structure. Adding a border to a section heading, an icon, or a label group because it looks organized is forbidden.

## Components

### Buttons

Restrained and nearly invisible until needed.

- **Shape:** Gently curved (6px radius). No shadow at rest.
- **Primary (Generate / Ingest CTA):** Cyan fill (#22d3ee), deep-void text, 8px / 20px padding, 500 weight Inter label. Hover: slightly darkened (#06b6d4) with a subtle scale transform (1.01). Focus: 2px muted-teal outline offset 2px.
- **Ghost / Secondary:** Transparent fill, `#8b949e` text, hairline border on hover only. Hover background: panel surface (#1c2333). Label weight stays 400. Used for Dry Run, Copy, secondary actions.
- **Icon button:** Transparent, 32×32px, icon only, no border at rest. Hover: surface-panel fill.

### Status Badges

Compact, mono-typed, color-coded against a tinted background.

- **Shape:** Pill (9999px radius), 2px / 10px padding.
- **PASSED:** Muted green background (rgba 63,185,80 @12%), green (#3fb950) text in JetBrains Mono.
- **FAILED / FAILED_AFTER_HEALING:** Red background / red text; violet background / violet text respectively.
- **DRY_RUN / PRE_COMPILE_FAILED:** Amber background / amber text.
- **All labels:** CAPS, 0.75rem JetBrains Mono, letter-spacing +0.02em.

### Cards / Bento Panels

- **Corner Style:** 10px radius (lg).
- **Background:** Raised surface (#161b22) over base void.
- **Shadow:** None at rest; panel-lift shadow on hover.
- **Border:** Hairline (1px solid #30363d).
- **Internal Padding:** 24px (lg).
- **Hover state:** Border steps to emphasis (#484f58), subtle surface-panel fill bleed at the top edge.

### Inputs / Fields

- **Style:** Surface-base fill, 1px solid hairline border, 6px radius. Monospaced when the value is a path, classname, or machine token; Inter for prose inputs.
- **Focus:** Border steps to muted-teal (#0e7490), 1px solid. No glow. A clean, precise state change.
- **Disabled:** Faded charcoal text (#484f58), no fill change, no border change.
- **Error:** Failure red border (#f85149), no fill change.

### Navigation Rail

Narrow left rail (~56px collapsed, ~200px expanded). Dark panel surface background (#1c2333). Icons only in collapsed state. Icon + label on expand. Active route: cyan (#22d3ee) icon and label, raised-surface background slab. Hover: surface-panel fill.

### Terminal / Log Stream

The live Maven output and SSE stream panel reads as a genuine terminal — monospace throughout (JetBrains Mono, 0.8125rem), #1e2433 background slightly off the panel color, text in weathered ash (#8b949e) for past lines, bone white (#e6edf3) for the current/live line. No word-wrap on log lines; horizontal scroll where needed. A subtle scanline of a lighter-tone line highlights the most recent entry. No border — the surface color difference defines the terminal block.

### Agent Squad Cards

The pipeline progress indicators. Each agent card shows a state (idle / running / done / failed) through a left-side accent strip: neutral (idle), cyan (running), green (done), red/violet (failed/healing). The card interior uses raised surface; the accent strip is the single color signal. Agent name in Inter title weight; status label in JetBrains Mono label.

## Do's and Don'ts

### Do:
- **Do** use JetBrains Mono for all machine-generated or machine-measured content: class names, file paths, status labels, metric values, log lines, generated code.
- **Do** use cyan (#22d3ee) exclusively for primary interactive elements and active/selected states.
- **Do** reserve green, red, amber, and violet for their locked semantic roles (pass, fail, warn, healing).
- **Do** keep surfaces flat at rest; elevate only modals and actively-floating elements with a shadow.
- **Do** start ghost elements with no border at rest; introduce hairline borders only on hover or focus.
- **Do** apply 6px radius to all interactive controls and cards; 4px for badges and chips; pill only for status badges.

### Don't:
- **Don't** use cyan as a decorative accent on dividers, icons, or non-interactive structure — its rarity is the signal.
- **Don't** apply shadows to resting card surfaces; flat means flat until state changes.
- **Don't** mix Inter and JetBrains Mono arbitrarily — the face choice is always driven by whether the content is human prose or machine output.
- **Don't** use color for visual structure that borders, spacing, or surface-level contrast can express.
- **Don't** introduce a third typeface or a display serif — the system's personality lives in density and precision, not in expressive letterforms.
- **Don't** use rounded corners larger than 10px anywhere in the application; nothing should feel bubbly or consumer-facing.
