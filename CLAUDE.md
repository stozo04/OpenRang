# CLAUDE.md — OpenRang Operating Instructions

## Owner

Steven Gates · gates.steven@gmail.com · GitHub: [stozo04](https://github.com/stozo04/OpenRang)
Solo developer. Android/Kotlin. Comfortable making architecture decisions, reviewing code, and shipping production-quality UI.

Tools: Android Studio, Git/GitHub, Supabase, Google services (Gmail, Calendar, Drive).

## What is OpenRang

Open-source Android camera app for creating custom, speed-controlled video loops ("Boomerangs"). Unlike proprietary apps with rigid speed configs, OpenRang gives users real-time playback speed control with 100% on-device processing.

Apache 2.0 licensed. Early-stage — concept spike through gallery feature complete, core "loop" generation still ahead.

## How to Work With Me

### PRD-first — always

Before building anything non-trivial: write a PRD covering problem statement, success criteria, scope, constraints, implementation plan, and open questions. Get sign-off before writing code. Check what already exists before proposing custom work.

**Always reference `PRD-mission-control.md` at the project root for the authoritative architecture and component specs before making structural changes.**

### Pushback — required

Interrogate vague requests. Disagree when something is off. Flag contradictions before acting — never silently overwrite prior decisions. No sycophancy. If a request conflicts with the existing architecture or a prior decision, call it out and explain the tension before proceeding.

### Reversibility protocol

Before anything destructive (deleting files, overwriting code, sending communications in my name, financial actions, mass operations):

1. Show the plan.
2. Flag what is irreversible.
3. Wait for explicit "proceed."

### Note-taking

Capture context, decisions, and open threads continuously. Checkpoint before switching domains or when a conversation runs long. If I say "things changed," re-interview me — don't assume prior context still holds.

### Working style

Show reasoning, not just conclusions. I value breadth and rigor equally — cast a wide net, do it well. Skip filler. Default tone: rigorous, direct, no fluff. Cover things properly but don't pad responses.

### Subfolder rules

When operating in a specific subfolder that has its own CLAUDE.md, respect that folder's voice and approach. The root CLAUDE.md (this file) provides defaults; subfolder overrides take precedence.

## Architecture Snapshot

### Tech Stack

| Layer | Technology | Version |
|-------|-----------|--------|
| Language | Kotlin | 1.9.22 |
| UI | Jetpack Compose | BOM 2024.02.02 |
| Camera | AndroidX CameraX | 1.3.1 |
| Media | AndroidX Media3 (ExoPlayer, Transformer) | 1.3.0 |
| Build | Gradle 8.3.2, AGP 8.3.2 | — |
| Target | compileSdk 34, minSdk 26, targetSdk 34 | — |

### Source Layout

```
com.openrang.app/
├── camera/
│   └── CameraManager.kt         # CameraX lifecycle, recording, lens toggle
├── ui/
│   ├── OpenRangUiState.kt       # Sealed interface: state machine
│   ├── OpenRangViewModel.kt     # MVVM hub: state, video storage, navigation
│   ├── CameraScreen.kt          # Live viewfinder, shutter, home button
│   ├── OnboardingScreen.kt      # 3-page carousel, animations, ArrowIcons
│   ├── PreviewScreen.kt         # Full-screen looping ExoPlayer preview
│   └── GalleryScreen.kt         # 3-col grid, thumbnails, delete, video overlay
├── MainActivity.kt              # Permissions, state routing, theme
└── (planned)
    └── media/
        └── VideoProcessor.kt    # Media3 Transformer: reversal, concat, speed burn-in
```

### State Machine

```
Onboarding → CheckingPermissions → ReadyToCapture ↔ Recording
                                  ↕                      ↓
                               Gallery            LoopingPreview
                                  ↕
                            ReadyToCapture
```

States are modeled as a sealed interface (`OpenRangUiState`) and driven by `MutableStateFlow<OpenRangUiState>` in the ViewModel. Navigation is conditional composable routing in `MainActivity.kt`.

### Design System

| Token | Value | Usage |
|-------|-------|-------|
| `NeonCoral` | `#FF5252` | Primary accent, shutter, gradients |
| `NeonPurple` | `#7C4DFF` | Secondary accent, gradients |
| `GlassWhite` | `#33FFFFFF` | Glassmorphic backgrounds |
| `GlassWhiteBorder` | `#4DFFFFFF` | Glassmorphic borders |
| `DeepCharcoal` | `#CC1A1A1D` | Translucent overlay bars |

Theme: dark-only (`darkColorScheme`). Aesthetic: glassmorphic vaporwave. All color constants are top-level vals in `CameraScreen.kt` and shared across screens via same-package visibility.

Onboarding has its own private palette: `DeepIndigo`, `DarkPlum`, `VoidBlack`, `FrostedGlass`, `FrostedGlassBorder`.

### Storage Pipeline

Videos are saved to `context.filesDir/videos/clip_<timestamp>.mp4`. Thumbnails (JPEG, 90% quality) are extracted at the 0ms mark via `MediaMetadataRetriever` and cached at `context.filesDir/thumbnails/clip_<timestamp>.jpg`. On-demand thumbnail extraction runs as a fallback if the cache is missing.

### Testing Strategy

| Type | Location | Framework | Coverage |
|------|----------|-----------|----------|
| Unit tests | `app/src/test/` | JUnit 4 + MockK + Coroutines Test | ViewModel state transitions, capture lifecycle, gallery ops |
| UI tests | `app/src/androidTest/` | Compose UI Test + AndroidJUnit4 | Navigation centering regression (OnboardingNavigationTest) |

`MainDispatcherRule` overrides `Dispatchers.Main` for coroutine testing.

### Key Engineering Decisions

- **OnboardingNavigation is extracted as an `internal` composable** — never inline it back into the Column. `ColumnScope.AnimatedVisibility` uses slide animations that cause buttons to jump. See `OnboardingNavigationTest` for the regression guard.
- **Page data is a data class** (`OnboardingPage`) — titles, drawables, and glow colors are a list, not scattered `when` branches.
- **Video storage uses `filesDir` not `cacheDir`** — gallery videos are persistent, not temporary.
- **Thumbnail caching is eager + lazy fallback** — extracted at save time, re-extracted on demand if missing.

### Git Workflow

Branch naming: `feature/<short-description>`. PR template at `.github/pull_request_template.md` with type labels, test checklist, and self-review gate. Current active branch: `feature/gallery-home-grid`.

## Reference Documents

| Document | Purpose |
|----------|--------|
| `PRD-mission-control.md` | **Authoritative architecture and component specs.** Read before any structural change. |
| `docs/active/IMPLEMENTATION_PLAN.md` | Phase-by-phase technical blueprint for the core "Loop" feature. |
| `README.md` | Public-facing project overview. |
| `.github/pull_request_template.md` | PR checklist and conventions. |

## What's Built vs. What's Ahead

### Complete

- 3-page onboarding carousel with animated transitions
- CameraX viewfinder with 1.5s burst capture + auto-stop timer
- Front/back camera toggle
- Persistent video storage + JPEG thumbnail caching
- Looping preview (ExoPlayer, `REPEAT_MODE_ALL`)
- Gallery grid with delete and full-screen playback overlay
- Home/gallery navigation from camera screen
- Unit tests (16) + UI regression tests (6)

### Remaining (per IMPLEMENTATION_PLAN.md)

- **Phase 3:** Loop generation — Media3 Transformer reversal + concatenation
- **Phase 4:** Dynamic speed slider (0.5x–3.0x) on preview
- **Phase 5:** Export with speed burn-in to device gallery
- **Future:** Share flow, audio toggle, custom duration, filters/effects
