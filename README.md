# BetManager

BetManager is an Android strategy and stake-automation assistant built with Kotlin, Jetpack Compose, Room, and Android's Accessibility Service (`TYPE_ACCESSIBILITY_OVERLAY`).

## Core Architecture
- **Controlled Scanning (`service/ScanScheduler.kt`)**: Runs scans only on Start, before a bet attempt, briefly to confirm acceptance, on a 5-second heartbeat, and on foreground package, orientation, or keyboard changes. System surfaces (Samsung Edge panels, SystemUI, launchers, keyboards) are ignored.
- **AutoTouch Driver (`service/AutoTouchDriver.kt`)**: Centralizes all click, focus, `ACTION_SET_TEXT`, read-back polling, keyboard dismissal, and layout-settle checks. Enabled buttons are tapped via gesture fallback even when a node reports `isClickable = false`.
- **Single Verified Bet Sequence (`data/engine/BetSequence.kt`)**: Validates game phase (`BETTING_OPEN`), checks strategy triggers, aligns the game's built-in Auto Cash Out row, sets and verifies the stake by read-back, settles layout bounds, re-finds the top-panel Bet button, places at most one verified bet per round, and locks the floating BET hand until round completion.
- **Persistence & Strategies (`data/local/`, `data/model/`, `data/engine/StakeCalculator.kt`)**: Preserves round history, strategy rules, custom crash patterns, progression modes (`Double`, `Add amount`, `Keep same`), and safety limits in Room across stops and restarts.
- **4-Tab Interface (`Home`, `Settings`, `Strategy`, `Logs`)** & **Single Floating BET Hand Overlay (`service/FloatingHandsOverlayManager.kt`)**: Clean controls with one draggable BET hand and compact status chip, without static HUD strips or app-controlled cashout hands.

## Setup on Device
1. Open **BetManager** and tap **Enable Accessibility Service**.
2. In Android Accessibility Settings → Installed apps → **BetManager Automation** → turn **ON** (allow restricted settings in App Info if sideloaded on Android 13+).
3. Configure your base stake, progression, Auto Cash Out target, and strategy rules, then press **Start** to show the floating BET control.
