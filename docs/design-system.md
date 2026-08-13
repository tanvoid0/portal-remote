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
  language (the tray plus a single QR window, since replaced by
  [MainForm.cs](server/PortalRemote.Server/Tray/MainForm.cs) — see §12 —
  alongside [TrayIcon.cs](server/PortalRemote.Server/Tray/TrayIcon.cs)).

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
| `bg` | `#EAEEF6` | `#080C18` | screen background / window canvas |
| `surface` | `#FFFFFF` | `#101725` | cards, sheets, the QR panel |
| `surface-raised` | `#FFFFFF` + shadow-sm | `#18202F` | nav bar, top bar |
| `surface-muted` | `#DDE4F0` | `#1F2839` | **sunken** fills: key faces, the trackpad, input boxes, the PC's chat bubble |
| `border` | `#D2DAE8` | `#2A3446` | dividers, card edges — decorative hairlines |
| `border-strong` | `#74809A` | `#64748B` | the boundary of a *control*: inputs, outlined buttons, the trackpad |
| `text-primary` | `#0E1524` | `#EEF2F8` | |
| `text-secondary` | `#55607A` | `#9AA7BD` | hints, timestamps |
| `success` (connected) | `#166534` | `#4ADE80` | connection status |
| `danger` (disconnected/error) | `#DC2626` | `#F87171` | connection status, destructive actions |
| `warning` | `#92400E` | `#FBBF24` | reconnecting / "wait" states |
| `violet` (`tertiary`) | `#6D28D9` | `#A78BFA` | the one non-accent hue — badges, accent illustration |

Status color is a first-class token, not an afterthought — connection state (paired /
connecting / disconnected) is the one piece of state the user checks constantly on
both the tray icon and the Android top bar, so it needs one consistent color pair used
identically in both codebases.

**The neutrals carry the accent's hue.** Every grey above is a blue-tinted slate at
3–8% saturation rather than a pure/zinc grey. A neutral ramp with no hue in it next to
a saturated blue accent reads as two unrelated palettes stacked, which is the specific
thing "plain" describes. The `tertiary` violet exists for the same reason: a palette
with exactly one hue has nowhere to go. It is deliberately *not* wired to chips or the
nav pill — "selected" is one color across the app, and that color is the accent.

**Three revisions this table has been through, and why (keep them):**

1. **Light mode had one surface value, not four.** `surface`, `surface-raised` and
   every Material `surfaceContainer*` slot were all `#FFFFFF` on a `#FAFAFA` page. Cards,
   the trackpad, key faces, selected chips and outlined buttons were therefore white
   shapes on white, distinguishable only by a `#E4E4E7` hairline at 1.2:1 — the
   user-visible fault this revision fixes. `bg` is a step deeper and tinted, and
   `surface-muted` is the sunken fill that was simply missing.
2. **`border` split in two.** One token was doing two jobs with opposite requirements: a
   divider wants to be quiet, a control boundary has to clear WCAG 1.4.11's 3:1 or the
   control has no visible extent. They map onto Material's existing `outlineVariant`
   (divider) / `outline` (control) pair, which already means exactly this.
3. **Light `success` and `warning` are 800-weight now** (`#166534` / `#92400E`, from
   `#16A34A` / `#D97706`). The old values measured 3.0–3.3:1 on light surfaces, which
   §9 had to write a standing "never set small text in these" constraint around —
   inevitably violated the first time someone needed a green line of text. Both clear
   4.5:1 on every light surface now and the constraint is gone.

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

**Every component has a face and an edge.** One step on the neutral ramp is a step a
phone screen in daylight — or a cheap monitor at 40% brightness — loses completely, so
nothing in this app is distinguished by fill alone:

| Component | Fill | Edge |
|---|---|---|
| Card, panel, sheet | `surface-raised` | `border` hairline |
| Input, key face, the trackpad | `surface-muted` | `border-strong` (3:1) |
| Input, focused | `surface-muted` | `accent`, 2dp |
| Primary button, selected chip | `accent` / `accent`-tinted container | none — the fill *is* the contrast |

On Android the first row is `portalCardColors()` + `portalCardBorder()` from
`ui/theme/Surfaces.kt`, so a card can't be built with only half of it. The same file
has `Modifier.accentGlow`: a drop shadow tinted with the accent instead of black,
because black shadow on a near-black surface is nothing at all — which is why dark UIs
usually abandon elevation and lose the depth cue with it. Reserved for surfaces that
are *doing* something (the nav capsule); on anything at rest it's the ornament §2 rule
5 argues against.

---

## 6. Motion system

Durations and easing, adapted from Emil's tables to this app's components:

| Interaction | Duration | Easing | Notes |
|---|---|---|---|
| Button/nav-item press feedback | 100–120ms | ease-out | scale to 0.97, not 0.95 (small touch targets shouldn't shrink much) |
| Tab switch (bottom nav) — content | 150ms | ease-out (cross-fade) | no slide — instant enough it reads as switching, not navigating |
| Tab switch (bottom nav) — the pill | spring, damping 0.72 / stiffness medium-low | — | the selected slot widens and its label slides in; the only chrome in the shell that moves, and the only animation in the app that is a layout pass on purpose — see §7 |
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

## 6a. Haptics (Android)

Motion's counterpart on a control surface. The phone is being used to work a machine
the user is *not* looking at the phone for: the eyes are on the PC, so the tick is
often the only confirmation that a tap became a click over there. Same frequency
argument as §1 — which is what keeps the list short.

Implemented in `ui/theme/Haptics.kt` on `View.performHapticFeedback` (no `VIBRATE`
permission, the platform picks the waveform for the device's actuator, and the system
touch-feedback setting is already honoured), provided app-wide as `LocalHaptics` from
the `haptics` preference (§12, on by default).

| Strength | Constant (API 34 / fallback) | Fires on |
|---|---|---|
| `tick()` | `SEGMENT_TICK` / `CLOCK_TICK` | scroll notch (rail, two-finger, mirror), drag release, tab switch, entering the scroll rail |
| `tap()` | `KEYBOARD_TAP` | a click sent to the PC, a key, a transport button — on pointer-**down**, not on click resolve |
| `press()` | `LONG_PRESS` | a gesture changing meaning under a still finger: hold becomes click-and-drag |
| `confirm()` / `reject()` | `CONFIRM` / `REJECT` (API 30; `VIRTUAL_KEY` / `LONG_PRESS` below) | a PC accepted the pairing / the connection failed |

Rules:
- **Never confirm something that didn't happen.** `WsClient.send` is best-effort and
  drops every message while the socket is down, but the surfaces below it still tick,
  tint and echo on their own. With the eyes on the PC that is the one lie this app can
  tell, so `RemoteScreen` gates every outbound message on the connection and answers a
  dropped one with `reject()` instead — rate-limited to once a second, since a 120Hz
  move stream would otherwise be a rattle rather than an answer.
- **Discrete events only.** Pointer moves, pan and pinch get nothing — the actuator
  can't follow a 120Hz gesture stream, and trying turns precision work into a rattle.
- **One tick per notch**, so scrolling has detents like a real wheel. Never per frame.
- **Down, not up**, for anything that behaves like a key (same reasoning as §7's
  instant key tint).
- **No haptic on the mirror's own frames or on IME typing** — the soft keyboard
  already buzzes, and doubling it reads as a stutter.
- Gesture handlers that fire haptics take the `Haptics` instance as a
  `Modifier.pointerInput` key: it's a new instance when the preference flips, so the
  handler restarts and the change lands immediately.

---

## 7. Component inventory

### Android (Compose)
- **PairScreen** — ordered by what it costs the user, not by what's technically
  primary: the remembered PC as a one-tap card, then the PCs discovered on the LAN
  as tappable cards, then the two fallbacks (QR scan, typed address) behind text
  buttons. The camera is no longer the default surface, so the camera permission is
  requested on entering scan mode rather than on launch — a permission prompt in
  front of a screen that doesn't need one reads as overreach.
  - **QrScannerView** — camera viewfinder with a scan-target frame; success state
    uses the "pairing success" spring above.
  - **Address entry** — an address is only ever digits, so the keyboard is only ever
    digits: four octet boxes plus a port, `KeyboardType.Number`, focus hopping on its
    own at three digits (or on a typed `.`) the way an OTP field does, backspace on an
    empty box stepping back. Hand-rolled on `BasicTextField` — the stock
    `OutlinedTextField`'s minimum width and internal padding are both wider than a
    three-digit box and neither is settable — at 56dp tall, 8px radius, `surface-muted`
    + `border-strong` per §5, going to a 2dp `accent` edge on focus. It has to say two
    things a stock field says for free: that it is a control at all, and which of the
    five boxes has the caret. Validity gates the Connect button instead of an error
    message.
  - **Waiting for approval** — tapping a discovered PC asks it for a token, which
    someone has to allow on the PC. That pause gets a card that names the PC and says
    what to look for there; an unexplained spinner is the fastest way to make this
    flow feel broken.
- **RemoteScreen shell** — a 44dp title row (surface-raised token; status dot, device
  name, settings gear) + a floating 60dp nav capsule (tab-switch cross-fade above).
  Both are deliberately below their Material defaults — see §13.
  - **The nav bar is hand-rolled, not `NavigationBar`.** The stock bar is an 80dp
    opaque slab pinned to the bottom edge with a permanent label under every icon, and
    its selection indicator fades out on the old item and in on the new one rather than
    moving between them — two blinks where the eye expects one movement.
  - **It floats**: 12dp side margins, 10dp above the bottom edge, radius = half its
    height, and a `shadow-md` tinted with the accent (`Modifier.accentGlow`, §5) plus a
    `border` hairline — a white capsule over a white list has no edge of its own, and a
    black shadow under it in dark mode has nothing to darken. The margin is chrome §13 has to pay for, and it buys the
    shape — a capsule flush with the bottom edge is just a rectangle with two rounded
    corners — plus content passing *beside* it, which is what sells the glass below as
    glass rather than as a grey band.
  - **Only the selected tab says its name.** Its slot widens (weight `1 + 1.5 × near`)
    and the label slides in beside the icon inside an accent pill at 16% alpha; the
    others stay icons. This is §13's "drop the four labels" taken one step further
    rather than undone — the width four labels used to cost is spent on the one word
    that is currently true. Unselected labels stay on `contentDescription`, and
    `Modifier.selectable(role = Role.Tab)` gives TalkBack the selected state the stock
    item provided.
  - **The width animation is a deliberate exception to §6's transform-only rule.** The
    point of the pill is that it *makes room* for the word; a scaled-up capsule with
    squashed text inside is the cheap imitation of that. It is four items relaid out
    for ~300ms on a rare interaction, which is the whole budget §1 allows chrome. The
    label is only composed past `near > 0.5`, where the slot is already wide enough to
    hold it — measured into a 55dp slot it would shove the icon out of its own pill on
    the way in.
  - **Every item reads its state off the indicator's position, not off which tab is
    selected** — `near = 1 - |slot - index|`, clamped, driving slot width, tint and the
    label's fade. That is what makes a two-tab jump stagger: the one in between swells
    and brightens as the selection passes over it, then settles. One animated number
    for the whole bar, no per-item animation to fall out of sync with the thing causing
    it. Ripple is off: the pill and the press-scale are already the feedback for a tap.
  - **The bar is frosted where there's something behind it.** On Files and Share the
    list runs *under* the bar and the bar draws a blurred copy of it (48px, plus a 0.78
    `surface-raised` tint) — §2 rule 4's "content scrolls under it", finally true. On
    Control and the mirror the content still stops above the bar and the bar is solid:
    a trackpad whose bottom 56dp is chrome has a dead strip, and the mirror is a picture
    that shouldn't be cropped for decoration.
  - Mechanically: the shell records its content into a `GraphicsLayer` (only while a
    glass tab is up, so the trackpad and the mirror — the two surfaces that redraw
    constantly — never pay for it), and the bar redraws that layer inside a node whose
    `graphicsLayer { renderEffect = BlurEffect(...) }` frosts it. The blur must live on
    a *sibling* node, not on the bar itself, or it takes the tab icons with it. API 31+;
    below that the bar is simply solid. The two list screens take the bar height as a
    `bottomInset` param and re-apply it to their `LazyColumn`'s `contentPadding` and to
    anything anchored at the bottom — Files' FAB, both screens' snackbars, Share's
    composer. Rows scroll under the glass, but nothing you have to *hit* or read ends up
    behind it.
  - **A dead session is not a dropped one, and the dot has three states.** A rejected
    token used to drop the whole shell back to PairScreen — losing the folder Files was
    showing and anything in flight, and replacing the screen under a moving finger with
    no statement of what happened. The shell now stays up, the dot goes `danger` rather
    than `warning` (amber says "wait" for something that is never coming back), and one
    dialog names the reason and offers the only action that fixes it. Dismissible: the
    screen behind still holds what the user was doing, the title row keeps the reason,
    and Settings already has the same way out.
  - **The shell's tab is `rememberSaveable`**, like ControlScreen's mode. The reasoning
    that once left it out — that losing your place matters more one level in — doesn't
    survive contact with the mirror, where a rotation is the most likely moment to
    rotate at all and lands you back on the trackpad.
  - **Reconnecting is stated wherever the title row isn't.** The dot and the
    "Reconnecting…" label cover every tab except the mirror in full screen, which drops
    the shell — and a stalled mirror looks exactly like a mirror between frames. That
    one case gets a pill at the top of the frame, in the same 35% black as the
    floating controls beside it. Nowhere else: a second copy of what the title row
    already says is chrome saying one thing twice.
  - The whole thing collapses to `snap()` under the system "remove animations" setting.
    §6/§9 only name the pairing spring and the status morph, but a bouncing pill is the
    most obviously decorative motion in the shell, so it's gated with them.
- **ControlScreen** — the four hand-driven surfaces (trackpad, keyboard, media, TV
  remote) under one `PrimaryTabRow`, behind the shell's single Control tab. They are
  one activity, not four — you point at a thing, type into it, then turn the volume
  down — and a bottom-nav trip between each of those was making the phone read as
  four apps. Same tab-switch cross-fade as the shell, so the two levels behave
  identically; the mode is `rememberSaveable` (the shell's own tab is not) because
  losing your place on rotation matters more one level in.
- **TrackpadScreen** — direct-manipulation surface; tap = click feedback (100ms scale
  pulse), drag = 1:1, no animation on the move events themselves. `surface-muted` face
  with a `border-strong` edge per §5: it is the largest control in the app and used to
  have no boundary at all, which in light mode meant the surface a finger is supposed to
  aim at was defined by nothing. The gesture legend
  in the middle of the pad is onboarding, not chrome: it disappears on the first touch
  and does not come back for the life of the composition.
  - **Scroll rail** — a 44dp strip down the right edge, because two-finger scroll is
    the one gesture on this screen nothing on screen suggested. It is drawn (hairline
    divider, 4% wash, a centered up/down glyph) rather than interactive: the pad's
    single gesture handler owns it, and the strip only has to say "this edge is
    different" without competing with the surface it sits on. Two-finger scroll
    anywhere still works — the rail is the discoverable path, not the only one.
  - **Mode is decided by where the finger landed, once.** A move that wanders into the
    strip stays a move; a rail drag that wanders out keeps scrolling. Anything else
    means the cursor stops dead mid-swipe near the right edge, which is the exact
    failure a fixed scroll zone invites. A tap on the rail is *not* a click, for the
    same reason: clicking the thing you grab to scroll with is a surprise.
  - Active state is a fade of the wash + glyph alpha only (§6's transform/alpha rule),
    at press-feedback duration — the rail brightens under the finger and nothing moves.
  - **Multi-finger gestures mirror a physical precision trackpad**, because that is the
    thing this screen is pretending to be and every mapping a user already knows is one
    less thing to teach. Two fingers: vertical scrolls, horizontal goes back/forward.
    Three fingers: sideways switches virtual desktop, up is Task View, down shows the
    desktop, and a tap reloads. All of it rides the existing `combo` message and the
    `browser_back`/`browser_forward` VKs the server already had — no protocol change.
  - **One rule for direction: you go the way you swipe.** Left is back and the desktop
    to the left; right is forward and the desktop to the right. That agrees with
    Windows' own trackpad desktop switching *and* with the way the back/forward arrows
    point. The competing "content follows finger" model (phone edge-swipe, macOS
    spaces) is defensible on its own but inverts one of those two, and a control
    surface that disagrees with the machine it drives is worse than either convention.
    Pinned by `SwipeShortcutTest` — a mapping table like this inverts silently, and the
    result shows up on the other screen where nothing can flag it.
  - **Axis lock, then fire once.** Two fingers never travel straight, so a multi-finger
    gesture accumulates 24px of centroid travel before committing to an axis (the
    swallowed travel is replayed into the scroll, or every scroll opens with a dead
    zone), then fires its shortcut once at 90px however far the fingers carry on —
    the way a physical pad pages back once per swipe. A gesture that changes finger
    count restarts the lock: fingers land milliseconds apart, so a three-finger swipe
    is briefly a two-finger one, and without the reset its stray head picks the axis.
  - Haptics follow `Haptics.kt`'s rule — `tick()` per scroll notch and on entering the
    rail, `press()` when a swipe commits, since a committed swipe is invisible on this
    screen and the confirmation is otherwise entirely on the PC.
  - **Scrolling lives in one place: `WheelScroll` (`ui/WheelScroll.kt`)**, shared by the
    trackpad's rail, its two-finger scroll, and the mirror's. Finger pixels in, whole
    wheel notches out, carrying the sub-notch remainder; the caller supplies scroll
    direction and the ×120 wheel delta, since natural-scroll is the user's preference,
    not the wheel's. It was two copies of the same accumulator against the same
    constant, which is how two surfaces end up scrolling at subtly different speeds.
    Pinned by `WheelScrollTest` — losing sub-notch travel is invisible until someone
    scrolls slowly.
  - **Momentum** (§2 rule 1's "momentum on flick", finally built). A release above
    300px/s hands the measured velocity to an `exponentialDecay` `Animatable` whose
    deltas feed the same notch path a finger does; any touch cancels it, the way a hand
    stops a spinning wheel. Two deliberate asymmetries with the finger path: velocity is
    capped at 6000px/s (a hard flick otherwise turns into a burst of wheel messages on
    the socket), and **coasting is silent** — a 4000px/s fling is ~60 notches a second,
    which is a rattle rather than feedback, so the detents belong to the finger only.
    Friction is a preference (`MomentumLevel`: Off / Short / Standard / Long) rather
    than a constant, because the right throw depends on what is being scrolled — a long
    document wants carry, a code editor wants it to stop where it was put.
    Rubber-band resistance (`Motion.rubberBandResistance`) stays unused: the phone has
    no idea where the remote document ends, so there is no boundary to resist at.
  - **Fine control is a gain curve, not a mode.** `precisionGain()` scales the pointer
    by 0.35× when the finger is barely moving (<0.05px/ms) up to 1.0× at travel speed
    (1.2px/ms) — the acceleration curve every desktop OS applies to a mouse. Chosen
    over a precision *button* or a second rail because the whole two- and three-finger
    space is already spoken for, and because the correct amount of chrome for a feature
    that should feel like physics is none. A finger covers ~130 desktop px on a 3440px
    monitor at phone width, so without this the smallest movement a hand can make still
    overshoots a 16px target. Two consequences worth knowing: the deltas it produces
    are sub-pixel, so the gesture loop carries the rounding remainder between events
    (truncate, keep the fraction) or slow movement would round to zero and the pointer
    would simply refuse to move; and the curve is pinned by `PrecisionGainTest`, since
    "fast travel is still 1.0×" is the property that keeps this from feeling sluggish
    and it can't be checked from a screenshot.
  - **The gesture list lives in Settings, and `TrackpadGestures` is where it's written
    down.** The legend on the pad is onboarding and is gone for good after the first
    touch, so everything past two fingers — three-finger desktops, task view, show
    desktop, three-finger reload — was recorded only in this file, which is not a
    document a user can open. The list is declared in `TrackpadScreen.kt` beside the
    handlers that implement it, for the same reason `echoFor` derives its captions from
    the keys it sends: a mapping kept in another file goes stale the first time this one
    changes.
  - **Gesture echo — a deliberate, bounded exception to §2.** A 40dp icon plus a word
    ("Back", "Next desktop", "Scroll", "Dragging") fades into the middle of the pad for
    450ms whenever a gesture *resolves*, and a ring expands from the touch point on a
    click. §2 rule 2 forbids decoration at high frequency and this is a real cost, so
    the exception is drawn at one line: **nothing animates while a finger is being
    tracked.** Pointer movement, the 1:1 drag, the pan of a swipe before it commits —
    all still completely silent. What animates is the discrete outcome, which fires at
    most a few times a second and is the one thing on this screen the user cannot
    otherwise verify without looking at the PC. If this ever starts feeling busy, the
    scroll echo is the first to go: it re-stamps per notch and so is the only one that
    can be continuously on screen.
  - The echo's caption is derived from the key combo the gesture already sends
    (`echoFor`), not passed alongside it, so a gesture added later cannot ship with a
    caption claiming something the PC isn't doing.
  - Both effects are alpha/scale/radius only per §6, and both collapse to `snap()`
    under the system "remove animations" setting — this is decoration, which is
    exactly what that setting is for, so it is gated even though §6 only names the
    pairing spring and the status morph.
- **KeyboardScreen** — key press = instant visual state (background tint on
  `pointerInput` down, not on click, so it matches physical key latency) + release.
  - **A key has a face** (§5): `surface-muted` under a `border-strong` outline. It was
    transparent-on-transparent, i.e. every key in the app — here and on the TV remote,
    which shares `KeyButton` — was defined by a hairline that in light mode was white on
    white. The press tint now sits *on top of* the face rather than replacing it, so a
    held key reads as lit rather than as suddenly existing.
  - **The special-key row wraps, it does not scroll.** As a `LazyRow` about half of the
    ten keys sat off the right edge with nothing on screen admitting it — no gradient,
    no guaranteed half-item — so Ctrl+C/V/Z did not exist for anyone who never happened
    to drag it. A `FlowRow` spends vertical space this screen has and removes the
    problem rather than signposting it. Same class of fault as the trackpad's
    three-finger gestures (see `TrackpadGestures`): a capability nothing on screen
    mentions is a capability nobody has.
- **MediaScreen** — transport buttons get the standard 100–120ms press feedback only.
  The now playing card above them is the one place in the app where something moves
  without being touched, and it is content rather than decoration: the progress bar
  recomputes on a 250ms tick (a live value, sampled — not an animation with a curve),
  and the title/artist lines use `basicMarquee()` because a truncated track name is
  the one string on this screen whose end matters as much as its start. Cover art
  swaps with no cross-fade, matching §1's rule for this bucket — the art changing
  *is* the notification that the track did. While the scrubber is being dragged the
  finger's position wins over the incoming one, per §2's direct manipulation rule.
  The "Opening on the PC" line under the cast box is an acknowledgement and expires
  after 4s: it used to clear only when the field was typed in again, so it sat there
  claiming to describe the present long after it stopped doing so.
  - **Speaker card** — a `Switch` in a standard §5 card at the bottom of the screen,
    under the volume buttons rather than up beside the cast picker: it answers the same
    question those buttons do (where the PC's sound comes out), and it is the opposite
    direction from casting, which sends something from here to there. Its subtitle is
    the whole design: it carries the one fact that will otherwise surprise people —
    that this *copies* the PC's output instead of replacing it — and then becomes a
    live status line ("Playing · 48kHz stereo", or the reason it is retrying). No
    animation, no meter: audio playing is not something this screen needs to also
    draw, and a level meter on a screen whose job is input is exactly §1's ornament.
- **TvRemoteScreen** — the couch mode: a D-pad on a 240dp circle with OK in the
  middle, power/back/Start/menu above it, transport + volume below, then chip rows
  for the odds and ends (Esc, Alt+Tab, Task view, Close…) and F1–F12. Everything
  here is a discrete press with its result on the *other* screen — no tracking, no
  gestures — which is the point: it's the one surface usable without looking at the
  phone, so the shape of the cross has to carry the layout.
  - **Power is five modes, not one**, because "power" on a PC isn't one thing:
    screen off, lock, sleep, restart, shut down. It sits apart from the navigation
    cluster in the `errorContainer` token, and the two that lose unsaved work take a
    second confirm — a mis-tap here costs whatever was open on a machine in another
    room. Screen off is first because it's the one a couch actually wants: the PC
    keeps playing and the monitor stops lighting the room, and any input undoes it.
    Wire values are pinned against the server by `PowerModeTest`.
  - **The arrows auto-repeat on hold** — 400ms then ~16/sec, matching Windows' own
    keyboard repeat, because walking down a long list from the couch is otherwise
    forty separate taps. One haptic on the press and none during the repeat, per
    §6a: continuous output doesn't get continuous feedback.
  - Reuses `KeyboardScreen`'s `KeyButton` and `MediaScreen`'s `TransportButton`
    rather than restating press-scale + haptics, so a change to the feedback spec
    lands on every button in the app at once.
- **BrowserScreen** — the second nav tab, and the largest screen in the app. Phase 4e;
  its mechanics (WebView config, adblock, popup blocking, the media sniffer, private
  profiles) are in [phase4-casting.md](phase4-casting.md) §13 and are not restated
  here. What matters for this document is that it is the one screen that **does not
  get §13's chrome budget applied to it**: a browser without a visible address bar,
  back/forward and a tab count is a viewport, and every one of those is a control the
  user reaches for mid-page rather than once a session. §13's argument is that chrome
  competing with the control surface is overhead — here the chrome *is* part of the
  control surface.
  - **The cast button is the reason the screen exists**, so it is the only thing in the
    bar carrying a `Badge`: the count of media found on this page, disabled at zero.
    It is the one piece of state on this screen the page itself cannot show.
  - **Every secondary surface is a `ModalBottomSheet`** — tabs, found media, bookmarks,
    history, privacy — never a nav destination. The page has to stay alive and playing
    underneath; navigating away from a video to look at a bookmark list and coming back
    to a reloaded page is the failure this whole tab exists to avoid. Same reasoning as
    §12's settings-over-the-shell, one level down.
  - **A site going fullscreen takes the shell with it**, via `onShowCustomView` drawn
    over the whole `Box`. This is the second screen that drops the shell entirely and
    it is the same rule as the mirror's (§7's ScreenScreen): when the content *is* a
    screen, a frame around it is a picture of a screen in a frame.
  - **The WebView is a real Android View drawn above Compose content**, which makes one
    layout mistake invisible in the accessibility tree and total in the pixels: given
    `fillMaxSize` inside a `Column` it claims the full height and covers the address
    bar outright, while every node stays present and testable. It takes `weight(1f)`.
    Worth keeping in mind for any Compose/View mix added later.
  - **Tabs are keyed by id** so switching swaps the whole `WebView` rather than reusing
    one and reloading into it, and the session lives above the shell's crossfade — a
    browser that reloads every page because you glanced at the trackpad is not a
    browser anyone would use.
  - **Empty states state the state** (§11 rule 2), three of them, and the interesting
    one is the private tab: on a WebView too old for `MULTI_PROFILE` it says out loud
    that sites you're signed into will still recognise you, rather than implying an
    isolation it can't deliver. A privacy surface that overstates itself is worse than
    one that isn't there.
  - **No press-scale and no haptics on this screen**, deliberately and unusually for
    this app: the content is a web page with its own feedback, and the app's tick on
    top of it is a second thing happening for one tap. §6a's rule is that a haptic
    marks an event *the PC received* — a scroll inside a WebView isn't one.
  - Known drift, not yet reconciled: its sheets pad 24dp horizontally where the rest of
    the app uses 16dp (§5), and it carries its own private `SettingRow` distinct from
    `SettingsScreen`'s. Both are file-private so nothing breaks; both are the kind of
    thing that makes the two surfaces read as different products up close.
- **FilesScreen** — list rows with a standard Material3 list item spec. The determinate
  linear indicator this section originally specified turned out to be on the wrong half
  of the feature: **downloads** go through the system `DownloadManager` and get a real
  progress notification for free, while the **upload** — the one this app runs itself —
  had no indication at all, so a 500MB video looked like a screen that had stopped
  responding. It has the bar now, fed by a byte count through `streamRequestBody`, and
  determinate for the reason first written here: this is a known number of bytes over a
  LAN, and a shimmer would misreport it as unknown-duration. It falls back to
  indeterminate only when the content provider won't say how big the file is.
  - **The Upload button is disabled while one is in flight.** The system picker will
    hand back the same file again without complaint, and two copies on the PC is a
    worse outcome than waiting.
  - **The folder can be pulled to refresh, and a failed listing offers a retry.** The
    list is a snapshot from whenever the folder was opened, and the PC changes it
    without telling anyone — something arrives from Share, or the PC saves there itself.
    The error state mattered more: it was the one state on this screen with no way out,
    since nothing else re-runs the listing and at the root there isn't even a folder to
    step into and back from. The empty state is inside the refresh box too and scrolls
    for it, because an empty folder is the most likely one to want re-checking.
- **ShareScreen** — a **conversation between the two devices**, and deliberately the
  *secondary* surface for its own feature: the primary way in is the system share
  sheet (`ACTION_SEND`), because opening this app to share something into it would
  defeat the point. Two views over one history, switched by a pair of `FilterChip`s at
  the top — chips because this is chrome over one feature, the job the mirror's chips
  do, not a second level of navigation under the shell's own tabs. Notifications are
  the one piece of chrome that fires when the user isn't looking: heads-up importance,
  and the payload is already on the clipboard before it appears — the notification is
  a receipt, not a step. A share that hasn't gone across yet reads "Waiting for your
  PC" in `text-secondary`, never "Failed" in `danger`: it is queued and will be re-sent
  on the next reconnect, and colouring it as a failure would tell the user to do
  something about a state the app is already handling. See
  [phase5-share.md](phase5-share.md).
  - **Chat** — the default view. Bubbles sided by device (this phone right in
    `primaryContainer`, the PC left in `surfaceVariant`) with the squared-off corner
    pointing at its own side: direction is carried by position and colour, which the
    eye reads without stopping, so no bubble spells out who sent it. A hand-off
    between two devices in front of you *is* a conversation, and a thread is the shape
    people already read fluently for "who sent what, in what order". The list is
    `reverseLayout` over the model's newest-first order rather than a re-sorted copy —
    that puts the newest at the bottom, opens the view there, and pushes an arriving
    share in from the bottom edge, none of which is true of a list merely sorted the
    other way. File and image entries render as icon-plus-name, not as a sentence.
  - **Composer** — pinned under the thread, and the only place in the app where the
    frosted nav bar has something opaque behind it: a text field you can see through
    the glass and can't reliably hit is worse than one that stops above it, so the
    composer's surface runs to the screen edge and its controls take the `bottomInset`
    as padding *inside* that surface. One field for everything typed, because
    `ShareKind.forText` already decides link vs note on both ends of the wire — asking
    the user to declare it would be asking for something already known. Attachments
    are an attach-icon menu (Image → the system photo picker, one uri and no media
    permission to answer; File → `GetContent`). Above the field, a **clipboard
    suggestion** row: the clipboard is invisible state, so it shows what it would send
    — the first two lines, the kind's icon, or a decoded thumbnail when what's copied
    is an image — and disappears once there's a draft, since at that point it isn't
    what the user is about to send. Everything here ends at the same two calls the
    share sheet makes; there is no second code path for sending. Sends carry
    `haptics.tap()` per §6a.
  - **Library** — the same entries as rows (the Files list spec: icon-per-kind per
    §11's recognition rule, incoming tinted `accent` and outgoing `text-secondary`),
    with a search field, a scrolling row of kind filters (All/Links/Images/Files/Notes)
    and a newest/oldest sort chip that flips rather than opening a menu — there are
    exactly two orders, and a menu to pick between two things is a menu too many. This
    is the view for "where did that PDF go", which a thread answers only with
    scrolling. Its rows are what runs *under* the frosted bar, taking the bar's height
    as `bottomInset` and re-applying it as `contentPadding` — content padding, not a
    `Modifier.padding`, because translucency needs something moving behind it while the
    last row still ends above it. The snackbar clears the bar outright, for the same
    reason. Filtering is a plain list operation: the history is capped in the model, so
    an index would be an index over a list that fits in a screenful of RAM.
- **ScreenScreen** (mirror) — belongs to §1's high-frequency bucket, the strictest
  case in the app: **zero animation on the surface itself**. The frame is the
  content, it refreshes 8–15× a second on its own, and any transition layered on
  top would fight it — no cross-fade between frames, no press feedback on the
  image, no animated zoom (the pinch tracks the fingers 1:1, per §2's direct
  manipulation rule and §6's "trackpad drag = none"). Chrome is one row of
  Material3 `FilterChip`s below the frame (monitor picker, quality preset, and a
  zoom-reset chip that appears only while zoomed) — chips, not a settings sheet,
  because every one of them is a thing you change *while watching* the result.
  Background is flat black rather than a surface token: it reads as the letterbox
  around a picture, not as a card the picture sits on.
  - **Full screen** is a chip on that row, and it takes the shell with it: title row,
    nav bar and the chip row itself all go, leaving only the frame. The controls come
    back through a 40dp button at 35% black in the top corner, which opens the *same*
    chip row over the bottom of the frame — one component, two placements, so a chip
    added later can't exist in one mode and not the other. Deliberately not a
    tap-anywhere reveal: a tap on this screen is a click on the PC, and spending that
    gesture on chrome would break the screen for the thing it's for. Back exits.
  - **Zoom and pan are the answer to "I can't read that"**: pinch to zoom to 4×,
    two fingers to pan once zoomed (at 1× the same drag scrolls the desktop instead,
    since there's nothing off-screen to pan to). Both track the fingers 1:1 with no
    animation, per the rule above. Neither is guessable from a picture of a desktop,
    so one line of hint sits over the frame until the first touch, then never returns.
    Zoom magnifies the JPEG that arrived — it does not ask the server for more detail,
    so a 960px "Smooth" stream at 4× is 240px of real pixels. Sharper zoom means a
    crop parameter on `/screen/mjpeg`; see §14.
  - **Scrolls in both axes**, unlike the trackpad, and shares its `WheelScroll` — so
    momentum and notch distance are identical on both surfaces. The pad spends its
    horizontal two-finger gesture on back/forward and can't have this; nothing on the
    mirror competes for a sideways drag at 1×, and a wide timeline or spreadsheet is
    exactly what someone points the mirror at. Horizontal wheel needed no protocol or
    server work — `Protocol.scroll` already carried `dx` and the server already mapped
    it to `MOUSEEVENTF_HWHEEL`; only the client had never sent it.

### Desktop (WinForms)
- **TrayIcon** — three icon states: idle (bare mark, `text-secondary`), connected
  (mark knocked out of an accent tile), error (same, `danger` tile). Reuse the same
  status colors as Android §3. Implemented per §11: one silhouette across all three,
  so the state reads as *color* at 16px rather than as a different shape.
- **TokenDialog** — the modal, in the token palette. `MessageBox` does not follow an
  app's light/dark choice, so in dark mode every prompt this app raised opened as a slab
  of light chrome in the middle of the one surface §12 calls designed — and those
  prompts are the destructive one (rotate the token) and the security gate (allow a
  phone to pair), i.e. the two the user should read most carefully. Deliberately thin:
  an owner-centred form, a measured wrapping message, one or two `TokenButton`s.
  - **Two-button dialogs default to the cancel button** — Enter and Esc both decline,
    confirming takes a click. Every two-button caller is destructive or a security gate,
    and the stock box already defaulted the pairing prompt to No; keeping that was the
    point, not a detail. One-button dialogs have nothing to get wrong, so Enter dismisses.
  - `destructive: true` paints the confirming button `danger` instead of `accent` —
    "Open" and "every paired phone stops working" should not be told apart by label alone.
- **MainForm** — the one "designed" surface (originally a QR-only window; widened
  into the app's main view in §12): `surface` background, 12px corner radius via
  `DWMWA_WINDOW_CORNER_PREFERENCE` (Win11+), heading using the type scale in §4, QR
  code framed in a `surface-raised` card with 12px padding, "Copy address" button
  gets press feedback (owner-drawn, `OnMouseDown` background tint — WinForms buttons
  don't support this natively).

---

## 8. Platform technical notes

**Compose / Android**
- Add `Motion.kt` alongside the existing `Theme.kt` with named `spring<Float>()` /
  duration constants from §6, so screens reference `Motion.pressSpring` etc. instead
  of inlining numbers.
- **Every Material3 `ColorScheme` slot must be set explicitly**, not just the ones §3
  names. `lightColorScheme()`/`darkColorScheme()` default each unspecified slot to
  Material's own baseline palette — a fixed purple with no relation to the `primary`
  passed in — so an unset slot doesn't inherit the accent, it silently ships an
  off-brand hue that no token table covers and no contrast audit checks. That is what
  the chat bubble (`primaryContainer`), the Snackbar (`inverseSurface`) and the TV
  remote's power button (`errorContainer`) each drew as until they were filled in.
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

**The audit is a test now, not a table.** `ContrastTest`
(`app/src/test/java/com/portalremote/ui/ContrastTest.kt`) computes WCAG 2.1 relative
luminance over the actual token values in `Theme.kt` and asserts every pair the app
can draw: body text on all four surface tokens, each `on-` color against its own
container, accent and status colors as text, and `outline`/`border-strong` at the 3:1
floor 1.4.11 sets for a control's boundary. It also asserts that surfaces are
*distinguishable from each other* — which is not a WCAG rule (1.4.11 exempts a
boundary that isn't needed to identify the component) but is precisely the defect the
§3 revision fixed.

Hand-computed ratios in a document are correct on the day they're written and silently
wrong two palette revisions later; a token drifts by one hex digit and no screenshot
review notices. Running it is `./gradlew :app:testDebugUnitTest --tests
"com.portalremote.ui.ContrastTest"`. Two of the current values were *chosen* by it —
light `success` and `warning` each failed at 700-weight against the newly tinted `bg`
and went to 800.

The constraint this section used to carry — "don't set small text in light `success`
or `warning`" — is retired: both clear 4.5:1 everywhere now, and the test fails if a
future edit takes them back under.

Desktop's palette is the same table in `Theme/Palette.cs` and is **not** covered by
that test; it's a hand-kept mirror, so a token changed on one side has to be changed on
the other (both files say so at the top).

Touch/click-target audit: Android's hand-rolled controls (`TrackpadScreen`'s
`ClickButton` at 56dp, `MediaScreen`'s `TransportButton` at 48–72dp,
`KeyboardScreen`'s shared `KeyButton` — explicitly given `defaultMinSize(48.dp,
48.dp)` since its 4dp-padded arrow-key variant would otherwise land under the
minimum) all meet the 48dp floor; stock Material3 components (`Button`,
`NavigationBarItem`, `ListItem`, and `SettingsScreen`'s `Switch`/`Slider`/
`FilterChip`) meet it by default. Desktop's owner-drawn `TokenButton` is 34px tall
everywhere it appears in `MainForm` — primary ("Copy address") and secondary
("Change", "Open", "Rotate pairing token") alike — clearing the 28px floor.

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
7. **Brand mark & iconography.** ✅ Added after the six above — see §11.
8. **Settings surfaces.** ✅ The desktop window and the Android settings screen —
   see §12. Last, deliberately: both are inventories of things the rest of the
   system already established, so building them earlier would have meant guessing
   at tokens and component specs that hadn't settled.
9. **Pairing without typing.** ✅ LAN discovery, an OTP-style digit-box address
   field, and the remembered PC surfaced as a card — see §7's PairScreen entry.
   After the settings pass, because the address field borrows the token set §5
   fixed and the whole screen only earns its reordering once there's something
   better than a camera to put first.
10. **Full screen & chrome budget.** ✅ Hidden system bars, a slimmer title row and
    an icon-only nav bar — see §13. Last, because it trims what the previous nine
    phases established rather than deciding anything new.
11. **Quick share.** ✅ A sixth nav tab, a system share-sheet entry point, and the
    first notifications the app has posted on either platform — see §7's ShareScreen
    entry and [phase5-share.md](phase5-share.md). It arrived as a sixth nav item on a
    bar built for five, which is what made the case for folding trackpad/keyboard/
    media into one Control tab rather than continuing to widen the bar.
12. **Telling the truth about the connection.** ✅ Gating every outbound message on the
    socket and answering a dropped one with `reject()` (§6a), stating a reconnect on the
    mirror in full screen, keeping the shell alive through a rejected token, and putting
    the trackpad's full gesture list in Settings — see §6a and §7. Late and small on
    purpose: each one is a place the finished system was quietly saying something that
    wasn't so, which is only findable once there's a finished system to use.
13. **The states nobody was in yet.** ✅ A second pass in the same spirit, over the
    screens phase 12 didn't reach: Files gained pull-to-refresh, a retry on the one
    dead-end error state, and the upload progress §7 had promised to the wrong half of
    the feature; the keyboard's special keys stopped hiding off the right edge; the
    desktop stopped naming this PC as the phone, started saying when a phone was
    refused, replaced `MessageBox` with `TokenDialog`, and had every absolute
    `TableLayoutPanel` size scaled so a 150% display stops clipping its captions.
14. **The palette, second pass.** ✅ Tinted neutrals, a `surface-muted` fill and a
    `border`/`border-strong` split, every Material `ColorScheme` slot filled in, light
    `success`/`warning` taken to 800-weight, and §5's face-and-edge rule applied to
    cards, inputs, key faces, the trackpad and the nav capsule on both platforms — see
    §3, §5 and §9. Last, and it touches everything the previous thirteen phases built,
    because the fault was in the tokens rather than in any one screen: light mode had a
    single white for `surface`, `surface-raised` and every container slot, so every
    component that was supposed to sit *on* a card was invisible against it. §9's
    hand-computed audit table became `ContrastTest` in the same pass — the one artifact
    here that stops this from happening a third time.

---

## 11. Brand mark & iconography

### The mark

A display with a phone-shaped opening punched through it. The product is a phone
that reaches into a PC, so the phone is drawn as the *way in* rather than as a
second device sitting next to the first. The opening is a real hole, not a
lighter-colored shape: that is what makes the mark work in a single tint, as an
Android monochrome/themed icon, and as a tray glyph tinted by connection state.

Drawn on a **24-unit grid**, one geometry, four coordinated implementations:

| Where | File | Notes |
|---|---|---|
| Android launcher | `res/drawable/ic_launcher_foreground.xml` | grid × 2.6, centered in the 108-unit viewport — inside the 66-unit adaptive-icon safe zone |
| Android in-app | `res/drawable/ic_portal_mark.xml` | 24dp, untinted black; every use site is a Compose `Icon()`, which applies its own tint |
| Desktop, all surfaces | `Theme/BrandMark.cs` | GDI+ path, scaled into any `RectangleF`; tray icons, the QR window icon and the QR window's lockup all come from it |
| Windows exe icon | `app.ico` (16/32/48/256) | the one baked asset; generated from the same numbers, since `<ApplicationIcon>` needs a file at build time |

Geometry, in grid units: display `1.5,3 21×15 r3`; opening `9.5,6 5×9 r1.5`
(even-odd, so it is a hole); stand neck `10.5,17.5 3×3`; stand base
`7,20 10×2.5 r1.25`. **The stand must be a separate path from the display** — it
overlaps the display body, and inside the even-odd path that overlap would punch a
second hole instead of merging. Both implementations note this; keep it if either
is redrawn.

Lockup: mark + "Portal Remote" in the §4 heading weight, gap = mark width ÷ ~2.5,
optically centered as one group (measure the text, don't hard-code an origin — the
desktop font resolves differently on Win10 vs Win11, per §4).

App-icon tile (launcher background, `app.ico`, tray connected/error): accent-filled
rounded square, corner radius 22% of the tile, mark inset 12%. The bare mark — no
tile — is used for tray idle and for anything drawn on an app surface.

### Icon usage

Material Symbols (via `material-icons-extended`, already a dependency) everywhere on
Android; no second icon set, and no hand-drawn glyphs outside the brand mark itself.

Five rules that decide where an icon earns its place:

1. **Icons carry recognition, not decoration.** Files list rows use extension-derived
   type icons (`iconForFile` in `FilesScreen.kt`) because a column of identical
   document glyphs makes every row look the same — the one screen in the app showing
   content the user has to pick out at a glance. The test is whether the glyph is
   *findable faster than the word is readable*, which is true far more often than this
   rule was first read to mean: a `PrimaryTabRow` of four modes, a scrolling row of
   nine chips from three unrelated groups, a settings screen that is one long column
   of near-identical rows, and a power menu whose five entries are near-synonyms are
   all cases where the label alone makes the user read rather than scan.
2. **A set is iconned whole or not at all.** A row where six of nine items have a
   glyph reads as three items being odd, not as six being labelled — the missing ones
   look broken rather than plain. If one member of a menu, chip row or tab row earns
   an icon, every member takes one; if one genuinely can't have a sensible glyph, the
   whole set stays text. (The browser's overflow menu and its tab list were both
   caught by this.)
3. **One idea, one glyph, everywhere.** `LinkOff` means "this pairing goes away" on
   PairScreen, in Settings and in the dead-session dialog; the mirror's quality
   presets carry the same three glyphs on the mirror and in Settings; a Share kind
   filter uses the icon its own rows use. Where that's mechanical, the glyph rides on
   the enum (`MirrorPreset.icon`, `PowerMode.icon`, `ControlMode.icon`) so a new
   entry can't ship without one.
4. **Still no icon where the label is already the answer.** `Connect`, `Copy address`,
   `Left`/`Right` on the trackpad (one `Mouse` glyph on both buttons distinguishes
   nothing), `Volume` above three volume buttons, and the TV remote's key chips
   (`Esc`, `Page ↑`) — the word *is* the key's name. The keyboard's special-key row is
   the near miss that goes the other way: `⌫`, `↵` and copy/paste/undo are glyphs the
   user already knows from the physical keyboard and from every app, so there the
   icon is the convention and the label is the gloss.
5. **An empty screen states the state.** Zero/error states pair a 48dp icon with a
   title and one line of what to do next (`EmptyState` in `FilesScreen.kt`). A bare
   sentence centered in a blank screen reads as a glitch rather than as a state.

Sizes come from the component, never from `Icon`'s 24dp default: `ButtonDefaults
.IconSize`/`IconSpacing` inside buttons, `FilterChipDefaults.IconSize` /
`AssistChipDefaults.IconSize` in chips (`ChipIcon` in `ScreenScreen.kt` and
`AssistChipIcon` in `BrowserScreen.kt` wrap those two), 18dp for a leading glyph on a
section header or a tab label. A decorative glyph beside its own label takes
`contentDescription = null` — the label is already the accessible name, and repeating
it makes TalkBack say everything twice.

### Desktop iconography — `Theme/Glyphs.cs`

The desktop's answer to `material-icons-extended` is the icon font Windows already
ships: **Segoe Fluent Icons** on Win11, **Segoe MDL2 Assets** on Win10, which carries
the same codepoints for everything used here. No bundled asset, no package, and
nothing to redraw when a glyph changes — so §11's "no hand-drawn glyphs outside the
brand mark" holds, since none of these are drawn by us. `Glyphs.Available` is false
when neither font resolves and every call site falls back to its label alone rather
than printing a column of tofu boxes.

- **`TokenButton.Glyph`** draws a glyph left of the label, measured and centered *as
  one group* — centering the label and hanging the glyph off its left edge puts the
  pair half a glyph off-centre, which shows the moment two buttons of different label
  lengths sit side by side. Its em is derived from the label's measured height rather
  than from a constant, so it survives the 150% case §12 had to fix by hand.
- **The tray menu takes bitmaps**, since `ToolStripMenuItem.Image` can't take a font.
  `Glyphs.Render` makes them at the menu's own `ImageScalingSize` in the palette's
  `text-primary`. "Open Portal Remote" is the exception that gets `BrandMark` instead
  of a glyph: that item opens *this app*, and the mark is already the thing being
  right-clicked to reach the menu.
- **Codepoints are picked by rendering them, not from a remembered table.** Both fonts
  carry near-duplicates a few codepoints apart (`E8B7` folder vs `E838` folder-fill,
  `E8C8` copy vs `E8B8` contact-card) and memory lands on the wrong one often enough
  that it isn't worth trusting. §11 rule 1 also has to be re-checked *at 16px*: the
  tray's "Send clipboard to phone" started on a clipboard glyph, which at menu size is
  two stacked rectangles and so is the Copy directly beneath it. It uses the same send
  glyph as the window's composer now — which is also what it does.

---

## 12. Settings surfaces

Both platforms grew a settings surface late (phase 8). They are deliberately not
symmetrical: the desktop's settings are about *this PC as a server* (what port,
what folder, which token), the phone's are about *how this phone drives it*
(pointer speed, scroll direction). Nothing is duplicated across the two.

### Desktop — `Tray/MainForm.cs`

Replaces the QR-only window. A tray app whose only window is a QR code makes
every other question ("is anything connected?", "where is the shared folder?",
"what port is this?") a config-file question, so the window is now the app's
main view and the tray menu shrank to Open / Copy address / Open shared folder /
Exit.

Two columns, no scrolling, at a fixed minimum size:

- **Left — the conversation, with pairing behind it.** The share thread: the same
  bubbles the phone's ShareScreen draws (incoming left in `surface-muted`,
  outgoing right in `accent` — one `Theme/Bubble.cs` shared with the assistant
  window), a composer with Send and Attach, and file drop anywhere on the thread.
  "Pair a phone" swaps the whole column for the QR, the address in mono, the
  same-Wi-Fi/firewall hint and "Copy address"; "Messages" swaps back.
- **Right — this PC.** A device card (one row per known phone: phone glyph, name,
  and a dot in the same `success` / `text-secondary` pair and vocabulary as the
  Android top bar in §7), then port, shared folder, cast receiver, assistant
  state, start-with-Windows, and token rotation.

Decisions worth keeping if this is rewritten:

- **Which column is up is a fact about the PC, not a default.** The window opens
  on the thread once `ServerConfig.Paired` is true and on the QR while it is
  false — a paired PC whose main view is "scan to pair" is answering a question
  the user already answered. `Paired` is set by `ControlEndpoint` on the first
  authorized socket, *not* where the token is handed out: a phone that scanned
  the QR took the token off the screen and never asked this PC for it. Rotating
  the token swaps back to the QR, since nothing can reach this PC until a phone
  scans it.
- **The desktop had no way to send, only to receive.** `Ctrl+Alt+V` pushed the
  clipboard and a balloon announced arrivals, and that was the whole surface —
  everything that had passed between the two machines was in a notification that
  had already gone. The thread is that history made visible, from
  `ShareHub.History` (in memory, last 50, same as the phone). Clicking a bubble
  does what the balloon did: a link opens, a note goes back on the clipboard, a
  file is *revealed* in Explorer and never launched.
- **A composer that cannot deliver says so before the button is pressed.** With
  no phone connected, Send and Attach are disabled and the hint says why, rather
  than swallowing the message — `TokenButton` draws its own disabled face, since
  an owner-drawn button ignores `Enabled` unless it is drawn in.
- **The assistant row is status, not conversation.** Ready / Not running / Not
  set up, the reason, and a Check that skips `AiHealth`'s backoff. The
  conversation itself is its own window (`Tray/AssistantForm.cs`) — this column
  is an inventory of what this PC is doing, and a chat is not an inventory item.
  Opening the window is passive: it probes within the backoff, so a dead port is
  never polled every few seconds by a window somebody left open.

- **The window is the canvas, the cards on it are surfaces.** It painted itself
  `surface` — the same token as the cards it holds — so in light mode the QR card and
  the status card were white panels on a white window with a hairline between them. The
  form is `bg` now; text fields are `surface-muted`, since a field is a sunken control
  and not a card (§5).
- **Every setting saves on change.** No Save button, no dirty state, nothing to
  discard — the settings are few and individually harmless.
- **The port is the exception that needed a mechanism.** It only binds at
  startup, so `ServerConfig.RunningPort` (`[JsonIgnore]`, pinned in `Load()`)
  holds what the server is *actually* listening on and everything the phone
  dials is built from it. Editing the port writes the file and nothing else.
  Handing out an address nothing is listening on would be worse than showing a
  stale one.
- **Secondary buttons are outlined, not filled** (`TokenButton.Secondary`). Four
  accent-filled buttons on one surface is four primaries, i.e. none.
- **The card lists phones, not a connection.** It said "Phone connected" over an
  address, which answers "is something attached" and not "which of my phones".
  Each known phone now has a row — glyph, its own name, and either
  `Connected · <address>` or `Last seen <when>` — with the connected ones sorted
  up. **Phones that are asleep keep their rows**: "which phones are mine" and
  "which one is awake" are different questions, and the second is not much use
  without the first. The name arrives in an `X-Portal-Device` header on the
  control socket (`data/DeviceName.kt` — the user's device name, the model as a
  fallback) and is persisted in `ServerConfig.Devices`, keyed by name rather than
  address, since the router hands out a different address often enough that
  keying on it would fill the list with ghosts of one phone. A client that sends
  no name is tracked as connected but never listed: a permanent "unknown device"
  row is worse than no row. Capped at four rows, or an old pairing list would
  push the settings off the window.
- **The card is also where a refusal is stated.** The caption under "Phone
  connected" used to be `Environment.MachineName` — the name of *this* PC, under a
  headline about the phone. A rejected token
  outranks the connection state for three seconds and turns the card `danger`:
  it is the direct consequence of the rotate button a few rows below it, and
  until then the only thing that reacted was a 16px tray icon nobody is looking
  at while they are looking at this window. In the card rather than a dialog,
  because nothing is being asked of the user (§1's calm budget).
- **Absolute `TableLayoutPanel` styles must be scaled by hand.** WinForms scales a
  control's own bounds under `AutoScaleMode.Font`, but leaves `SizeType.Absolute`
  row and column styles exactly as written — and the csproj asks for `PerMonitorV2`,
  so this is real scaling rather than a bitmap stretch. At 150% every font in this
  window grew and every row height did not, clipping all three two-line captions.
  Every absolute value in `MainForm` goes through `LogicalToDeviceUnits`. Scaled
  once at construction: dragging the window between monitors at different scales
  will not re-lay it out.
- **Motion stays at §1's "calm" budget**: the 180ms fade-in on show, nothing
  else. The status dot does *not* morph like Android's — WinForms has no
  compositor, and a `Timer`-driven color tween would be the one janky thing on
  an otherwise static window.
- **First run opens the window** (`ServerConfig.IsFirstRun`) instead of showing a
  balloon. A brand-new install has exactly one useful next step and it is in
  there.

### Android — `ui/SettingsScreen.kt`

Reached from the top-bar gear, and drawn *over* the remote shell rather than
replacing it — navigating away would tear down every tab's state (the folder
Files is showing, an upload in flight), which is the same state the single
`RemoteScreen` call site in `PortalRemoteApp` exists to protect across a
reconnect. `BackHandler` closes it.

- **What lives here vs. on the screen it affects.** Anything you change *while
  watching the result* stays where it is — the mirror's monitor and quality
  chips (§7) are still on the mirror. Settings holds the preferences you set
  once (pointer speed, scroll direction, fine control, scroll momentum, keep-awake,
  haptics) and the mirror's *default* preset, which the chips then write back to.
- **Feel preferences ship on.** Fine control defaults on and momentum defaults to
  `STANDARD`, which breaks §12's usual "defaults reproduce the old behaviour" rule on
  purpose: both are corrections to how the pad felt, and a correction nobody finds is
  not one. Fine control is a switch; momentum is a chip row (Off / Short / Standard /
  Long) — a slider would imply a precision nobody can feel, and "off" belongs in the
  same control as the amount rather than as a second switch guarding it.
- **The haptics switch fires the real haptic as it turns on** (§6a), through a
  locally-constructed always-on `Haptics` — the ambient one is still the old,
  possibly silent instance at the moment of the tap, and "what does this feel
  like" is the only question that row has.
- **Defaults reproduce the previous hard-coded behaviour exactly**
  (`AppSettings` in `data/Prefs.kt`), so an upgrade changes nothing until a
  control is touched.
- **Gesture handlers must key on the settings they read.**
  `Modifier.pointerInput(...)` captures its lambda once; both `TrackpadScreen`
  and `ScreenScreen` pass `pointerSpeed`/`naturalScroll` as keys, or a change
  wouldn't apply until the handler happened to restart.
- **Forgetting a PC is confirmed and lives here**, not behind a one-tap top-bar
  icon — it drops the token, and the only way back is the QR code. It also no
  longer wipes the preferences: `Prefs.clear()` removes the host keys only.

---

## 13. Full screen & chrome budget (Android)

The phone is a control surface held for the length of a session, and the thing it
is controlling is on the *other* screen — so anything on this one that isn't the
control surface is overhead. Roughly 200dp of an 800dp phone used to be chrome
(status bar + 64dp top bar + 80dp labelled nav bar + gesture bar). That is now
about 100dp.

- **System bars are hidden, not removed.** `MainActivity` calls
  `WindowInsetsControllerCompat.hide(systemBars())` with
  `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`: an edge swipe brings the clock and the
  back gesture back as a transient overlay, without a layout shift mid-drag — which
  is the whole reason for the transient behaviour rather than a sticky toggle.
- **Cutouts are still respected.** With the bars gone, `WindowInsets.systemBars` is
  0 and the Material defaults collapse with it, so every top bar in the app asks for
  `WindowInsets.safeDrawing.only(Top)` and every `Scaffold` for the horizontal side
  of it. On a phone with no cutout that costs nothing; on one with a notch it is the
  difference between a title and a title under the camera.
- **Chrome states, it doesn't narrate.** The title row shows "Reconnecting…" and
  nothing when connected — the §3 `success` dot already says that, and a permanent
  label saying the normal thing is the cheapest clutter to delete. Same reasoning
  drops the four nav labels: the icons are unambiguous, and the labels move to
  `contentDescription` so TalkBack still names each tab.
- **The merge pays for its own tab row.** Folding trackpad/keyboard/media/remote
  into one Control tab (§7's ControlScreen) adds a 48dp `PrimaryTabRow` and takes
  two icons out of the bottom bar. Net chrome is roughly flat; what it actually buys
  is that switching between the four things you do with your hands no longer travels
  through the bottom of the screen. The tab row is labelled where the nav bar isn't,
  because "Trackpad" vs "Remote" is a distinction icons alone don't make.
- **Screen padding is 10dp, not 16dp**, on the input screens (trackpad, keyboard) —
  still on §5's scale, one step down. Media keeps a looser 16dp because it's a
  centered cluster of buttons with space to spare, not a surface fighting for room.
- **The mirror goes further and drops the shell entirely** — see §7's ScreenScreen
  entry. It's the only screen that does, because it's the only one whose content is
  itself a screen.

---

## 14. Open: sharper zoom on the mirror (not built)

Zoom on the mirror is client-side — it scales the JPEG that already arrived. That is
right for framing ("show me the top-left quarter") and wrong for reading ("what does
that error say"), because at 4× on the Smooth preset there are only 240 source pixels
across.

The fix, if it's ever worth it: a normalized crop rect on `/screen/mjpeg`
(`cx,cy,cw,ch`), passed down to `ScreenCapture.Jpeg` as a sub-rectangle of the
monitor bounds — the capture is already a `CopyFromScreen` of an arbitrary
`Rectangle`, so it's a handful of lines each side. The same width budget then buys
4× the detail inside the zoomed region.

The reason it isn't built: changing the crop means a new URL, i.e. tearing down and
restarting the MJPEG response. That's fine at the *end* of a pinch and unusable
*during* a pan, so it needs the crop committed on gesture-end and the client
continuing to scale locally in between — a real piece of state machinery for a
problem that only shows up past ~2× on the lowest preset. Switching to the Sharp or
Max preset is the current answer.
