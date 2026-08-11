# Portal Remote — UI Kit & Design System Plan

Status: planning document, not yet implemented. Written so a future session (human or
Claude) can execute against it without re-deriving these decisions.

Scope: the Windows tray/desktop app (`server/PortalRemote.Server`, WinForms) and the
Android client (`android/`, Jetpack Compose). Both sides of one product — a phone
remote-controlling a PC — so they should read as one visual language even though they're
built on unrelated UI toolkits.

Motion/interaction rules below are adapted from Emil Kowalski's design-engineering
skills (github.com/emilkowalski/skills) and Apple's HIG-derived interaction model,
applied specifically to this app's two very different usage patterns (see §1).

---

## 1. Product frame — this determines almost everything below

Two surfaces, two frequencies:

- **Desktop tray app**: opened rarely — pair once, glance at status, occasionally
  re-show the QR code. Optimize for **calm and clarity**, not motion. It should look
  considered the one time a user sees it, not entertain them.
- **Android client**: the surface used *continuously* during a session — every
  trackpad drag, key tap, and media button press goes through it. This is
  Emil's "100+ times/day" bucket: **decoration must not slow down or distract from
  input**. The only animation budget here goes to state changes that are rare
  (pairing, connect/disconnect, tab switches), plus tight <100ms feedback on presses.

Current state (for grounding, not to be treated as final):
- Android already uses Material3 with a blue seed (`#2563EB` light / `#60A5FA` dark,
  [Theme.kt](android/app/src/main/java/com/portalremote/ui/theme/Theme.kt)) but no
  type scale, spacing scale, or motion spec beyond Material3 defaults.
- Desktop is stock WinForms — system gray, default fonts, no dark mode, no icon
  language ([QrForm.cs](server/PortalRemote.Server/Tray/QrForm.cs),
  [TrayIcon.cs](server/PortalRemote.Server/Tray/TrayIcon.cs)).

---

## 2. Design principles

1. **Direct manipulation on the trackpad.** 1:1 pointer tracking, momentum on flick,
   rubber-band resistance at any boundary — never a hard stop. This is the one place
   in the app where Apple's gesture model applies almost verbatim.
2. **Silence at high frequency.** Mouse move, scroll, individual key taps: zero
   decorative animation, only instant (<100ms) press feedback. If it fires more than
   a few times a minute, it doesn't get a flourish.
3. **Spatial consistency.** Things exit the way they entered. A screen that slides in
   from the right slides out to the right. The QR window that appears from the tray
   icon should feel anchored to the tray, not centered from nowhere.
4. **Material depth used sparingly, for chrome only.** Translucency/blur on the
   Android top bar and bottom nav is fine (content scrolls under it); never stack two
   translucent surfaces; the tray/QR window on desktop should read as solid, since
   WinForms can't do real blur (see §8).
5. **Craft in the boring parts.** Consistent 8px spacing rhythm, one corner-radius
   scale, real hit targets (≥48dp touch, ≥28px mouse), aligned baselines. This is
   what "realistic" and "modern" actually cash out to — not extra ornament.

---

## 3. Color tokens

Keep the existing blue as the brand accent (it's already in production-adjacent code);
build a full scale around it instead of introducing a new palette.

| Token | Light | Dark | Use |
|---|---|---|---|
| `accent` | `#2563EB` | `#60A5FA` | primary actions, active nav item, focus ring |
| `accent-pressed` | `#1D4ED8` | `#3B82F6` | pressed/active state |
| `bg` | `#FAFAFA` | `#0F1420` | screen background (dark already defined) |
| `surface` | `#FFFFFF` | `#161B27` | cards, sheets, the QR panel |
| `surface-raised` | `#FFFFFF` + shadow-sm | `#1E2534` | nav bar, top bar |
| `border` | `#E4E4E7` | `#262D3D` | dividers, input outlines |
| `text-primary` | `#18181B` | `#F4F4F5` | |
| `text-secondary` | `#71717A` | `#A1A1AA` | hints, timestamps |
| `success` (connected) | `#16A34A` | `#4ADE80` | connection status |
| `danger` (disconnected/error) | `#DC2626` | `#F87171` | connection status, destructive actions |
| `warning` | `#D97706` | `#FBBF24` | graphical only in light mode — icons/dots, not text; see note below |

Status color is a first-class token, not an afterthought — connection state (paired /
connecting / disconnected) is the one piece of state the user checks constantly on
both the tray icon and the Android top bar, so it needs one consistent color pair used
identically in both codebases.

**`warning` note (added after the §9 contrast audit):** this table originally
suggested `warning` for the QrForm "same Wi-Fi required" hint. Implemented as
`text-secondary` instead — light-mode `warning` (`#D97706`) measures 3.05:1 against
`bg`/`surface`, clearing WCAG AA's 3:1 graphical/large-text threshold but failing
the 4.5:1 threshold that small body/caption text needs. `warning` is correct for
graphical uses (icons, dots, large/bold text) but not for the kind of small hint
text this suggestion was written for. It's currently unused in both codebases —
reach for it when a graphical (non-text) warning indicator comes up, not for
running text, unless paired with a darker text-safe shade introduced at that point.

---

## 4. Typography

- **Android**: default to the system font (Roboto / Google Sans depending on OS), but
  define an explicit scale — don't rely on Material3 defaults untouched:
  - Display (pairing success, empty states): 28sp / line-height 34sp / tracking 0
  - Title (screen headers): 20sp / 26sp / 0
  - Body: 15sp / 22sp / 0
  - Label (nav items, buttons): 13sp / 16sp / +0.1sp (small text gets slightly
    *positive* tracking for legibility, per Apple's size-specific tracking rule)
- **Desktop**: `Segoe UI Variable` if present (Win11), fall back to `Segoe UI`. Define
  three weights only: heading (11pt semibold — matches current QrForm heading),
  body (9.5–10pt regular), caption (9pt, `text-secondary`).

---

## 5. Spacing, radius, elevation

- **Spacing scale**: 4 / 8 / 12 / 16 / 24 / 32 (px on desktop, dp on Android). Pick
  from this set only — no arbitrary values.
- **Corner radius**: 8px small controls (buttons, chips), 12px cards/sheets, 20px
  bottom-sheet-style surfaces. One scale shared by both platforms.
- **Elevation**: 2-step shadow scale (`shadow-sm` for resting cards, `shadow-md` for
  anything overlaying content — QR panel, modals). Bigger surface = stronger shadow,
  per Apple's materials guidance.

---

## 6. Motion system

Durations and easing, adapted from Emil's tables to this app's components:

| Interaction | Duration | Easing | Notes |
|---|---|---|---|
| Button/nav-item press feedback | 100–120ms | ease-out | scale to 0.97, not 0.95 (small touch targets shouldn't shrink much) |
| Tab switch (bottom nav) | 150ms | ease-out (cross-fade) | no slide — instant enough it reads as switching, not navigating |
| QR panel appear (desktop) | 180ms | ease-out | scale from 0.96 + fade, origin = tray icon corner |
| Connect/disconnect status change | 200ms | ease-in-out | color + icon morph, never abrupt |
| Pairing success | 300–400ms | spring, damping 0.8 | the one moment worth a little bounce — rare, celebratory |
| Trackpad drag | none (direct) | — | 1:1 transform, no easing; see below |
| Mouse move / scroll / key tap | 0ms | — | zero animation, per §1 rule 2 |

Rules:
- **Only animate `transform`/`alpha` equivalents.** On Compose, that's
  `Modifier.graphicsLayer { scaleX/scaleY/alpha }`, not size/padding animations —
  avoids layout passes.
- **Never `ease-in`** on anything UI-initiated (delays the response exactly when the
  user is watching).
- **Trackpad is a spring, not a tween.** Use `Animatable` with
  `spring(dampingRatio = 1f)` for any programmatic repositioning (e.g. snapping after
  a two-finger gesture), and rubber-band resistance
  (`overshoot × k / (1 + k × |overshoot|)`, k≈0.55) if the trackpad surface ever
  clamps movement at an edge.
- **Reduced motion**: Android has no direct `prefers-reduced-motion`, but respect
  `Settings.Global.ANIMATOR_DURATION_SCALE == 0` (system "remove animations"
  accessibility setting) — when it's 0, skip the pairing-success spring and status
  color-morph duration, keep instant state changes.

---

## 7. Component inventory

### Android (Compose)
- **PairScreen / QrScannerView** — camera viewfinder with a scan-target frame; success
  state uses the "pairing success" spring above.
- **RemoteScreen shell** — `TopAppBar` (surface-raised token, shows connection status
  as a small colored dot + label next to the device name) + `NavigationBar` (4 items,
  tab-switch cross-fade above).
- **TrackpadScreen** — direct-manipulation surface; tap = click feedback (100ms scale
  pulse), drag = 1:1, no animation on the move events themselves.
- **KeyboardScreen** — key press = instant visual state (background tint on
  `pointerInput` down, not on click, so it matches physical key latency) + release.
- **MediaScreen** — transport buttons get the standard 100–120ms press feedback only.
- **FilesScreen** — list rows with a standard Material3 list item spec; download
  progress uses a determinate linear indicator, no indeterminate shimmer (this is a
  local network transfer, shimmer would misrepresent it as unknown-duration).

### Desktop (WinForms)
- **TrayIcon** — three icon states: idle (outline), connected (filled, accent color),
  error (filled, danger color). Reuse the same status colors as Android §3.
- **QrForm** — restyle as the one "designed" surface: `surface` background, 12px
  corner radius via `DWMWA_WINDOW_CORNER_PREFERENCE` (Win11+), heading using the type
  scale in §4, QR code framed in a `surface-raised` card with 12px padding, "Copy
  address" button gets press feedback (owner-drawn, `OnMouseDown` background tint —
  WinForms buttons don't support this natively).

---

## 8. Platform technical notes

**Compose / Android**
- Add `Motion.kt` alongside the existing `Theme.kt` with named `spring<Float>()` /
  duration constants from §6, so screens reference `Motion.pressSpring` etc. instead
  of inlining numbers.
- Extend `Theme.kt`'s color scheme with the full token table in §3 (currently only
  `primary`/`secondary`/`background`/`surface` are set).
- Use `Modifier.pointerInput` + `VelocityTracker` for the trackpad, not
  `draggable()`'s default — need raw velocity for any future flick-to-scroll gesture.

**WinForms / desktop**
- WinForms has no compositor — no real blur, no interruptible spring animations.
  Keep desktop motion minimal (per §1) so this limitation doesn't show: the QR panel
  fade-in can be done with a `Timer`-driven opacity loop (`AllowTransparency` +
  layered window), everything else should be static.
- If a fuller "modern realistic" desktop shell (blur, real spring animation) becomes
  a priority later, that's a WPF or WinUI 3 rewrite of the settings/QR window, not
  something to force into WinForms — flag that as a separate decision, don't attempt
  it as part of this token/component pass.
- Rounded corners: `DwmSetWindowAttribute` with `DWMWA_WINDOW_CORNER_PREFERENCE`
  (Windows 11 only — no-op/ignore on Win10, don't branch UI around it).

---

## 9. Accessibility

- Android touch targets: minimum 48dp, no exceptions on the trackpad's own controls
  (buttons overlaid on it, not the trackpad surface itself).
- Desktop click targets: minimum 28px tall for any owner-drawn button.
- Status color pairs (`success`/`danger`) must hit WCAG AA against their background in
  both light and dark — verify the dark-mode `#4ADE80`/`#F87171` pair against
  `#0F1420` when implemented.
- Respect the Android system "remove animations" setting per §6.

### Audit results (post-implementation)

Contrast ratios computed against the actual §3 hex values (WCAG 2.1 relative
luminance formula):

| Pair | Ratio | AA text (4.5:1) | AA large/graphical (3:1) |
|---|---|---|---|
| `success` dark on `bg` dark (`#4ADE80`/`#0F1420`) | 10.56:1 | Pass | Pass |
| `danger` dark on `bg` dark (`#F87171`/`#0F1420`) | 6.65:1 | Pass | Pass |
| `danger` light on `bg`/`surface` light | 4.63–4.83:1 | Pass | Pass |
| `success` light on `bg`/`surface` light (`#16A34A`) | 3.16–3.30:1 | **Fail** | Pass |
| `warning` light on `bg` light (`#D97706`) | 3.05:1 | **Fail** | Pass |
| `accent`, `text-primary`, `text-secondary` (both themes) | 4.63:1+ | Pass | Pass |

The dark `success`/`danger` pair this section originally flagged as unverified
clears AA comfortably. Two pairs the original table didn't flag turned out to
matter: **light-mode `success` and `warning` fail the 4.5:1 text-contrast
threshold**, though both clear the 3:1 graphical/large-text one. Current usage is
graphical-only — the connection-status dot (Android `RemoteScreen`/desktop
`TrayIcon`) and the pairing-success checkmark icon — so nothing today violates AA.
**Constraint for future work: don't set small body/label text in `success` or
`warning` in light mode** without a darker shade; they're fine for dots, icons, and
large/bold text.

Touch/click-target audit: Android's hand-rolled controls (`TrackpadScreen`'s
`ClickButton` at 56dp, `MediaScreen`'s `TransportButton` at 48–72dp,
`KeyboardScreen`'s shared `KeyButton` — explicitly given `defaultMinSize(48.dp,
48.dp)` since its 4dp-padded arrow-key variant would otherwise land under the
minimum) all meet the 48dp floor; stock Material3 components (`Button`,
`NavigationBarItem`, `ListItem`) meet it by default. Desktop's only owner-drawn
control, `TokenButton` ("Copy address" in `QrForm`), is 34px tall, clearing the
28px floor.

Reduced motion: `Motion.reducedMotionEnabled()` gates the two animations §6 calls
out by name — the pairing-success spring and the status color-morph — in
`PairScreen`'s scan-success overlay/status banner and `RemoteScreen`'s status dot,
swapping in `snap()` when the system "remove animations" setting is on. Per-tap
feedback (press-scale, tab cross-fade) is left ungated: §6 only names those two
animations, and their durations are already short enough (100–200ms) not to be a
motion-sickness concern on their own.

---

## 10. Implementation phases

Suggested order for whoever (human or Claude) executes this — tokens before
components, shared surfaces before one-off screens. All six phases below are done.

1. **Tokens.** ✅ Android: expand `Theme.kt` color scheme + add `Type.kt`/`Motion.kt`.
   Desktop: add a small `Theme/Palette.cs` + `Theme/Fonts.cs` with the values from §3–4.
2. **Desktop shell.** ✅ Restyle `QrForm.cs` and the tray icon states — this is the only
   desktop surface, low effort, high visibility.
3. **Android shared chrome.** ✅ `RemoteScreen`'s top bar + bottom nav using the new
   tokens and tab-switch cross-fade.
4. **Android per-screen pass.** ✅ Trackpad (direct manipulation + press feedback),
   Keyboard (key press states), Media (button feedback), Files (list spec).
5. **Pairing flow polish.** ✅ QR scan success spring, connect/disconnect status morph —
   last, because it's the lowest-frequency interaction and easiest to get wrong if
   the rest of the token system isn't settled yet.
6. **Accessibility pass.** ✅ Contrast check on final colors, reduced-motion gate,
   touch-target audit — see §9's "Audit results" for findings.
