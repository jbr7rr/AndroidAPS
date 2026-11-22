# Wavez (AAPS 4.0)

Fork of AAPS 4.0, carrying forward the Wavez features originally based on the Tsunami fork: https://github.com/piecycle/tsunami

The goal of this fork is to allow for an as close to FCL experience as we can get. Changes are made with that target in mind.
Please note that while these changes work well for me, they might not work for you.

## Features on top of AAPS 4.0

### Wave mode & SMB settings (OpenAPS SMB)
- Wave activity controller ported into OpenAPS SMB (`WaveEngine` + `WaveSnapshot`):
  - Only Wave mode remains (no Tsunami mode); low deltas (`< 4.1 mg/dL`) fall back to standard oref1.
  - Configurable active time window (`wave_start`..`wave_end`, inclusive).
  - SMB size limiter supports either a fixed SMB cap or Wave-specific minutes of basal (with last-bolus cap reduction).
  - BG score scales between `target_bg` and `target_bg + 30 mg/dL` so it adapts to (temp) targets.
  - Experimental option to use Autosens/DynamicISF adjusted sensitivity (`wave_use_adjusted_sens`).
- Global **SMB size factor** setting (`ApsSmbFactor`, default `100%`, range `50%–100%`).

### PD insulin models
- Real `InsulinModel.Pd` extension on `ICfg` (persisted in JSON, Room DB 37, and Nightscout/AAPSClient treatment payloads):
  - **Custom PD** preset (`a0 = 51.0`, `a1 = 12.27`, `b1 = 0.05185`, `concentrationScalesPeak = false`, fixed 8 h curve). Model explorer: https://colab.research.google.com/drive/1cGITp_b4xxaG3TPetLAMbPJfSEKACK0o
  - **Lyumjev PD** preset (`a0 = 61.33`, `a1 = 12.27`, `b1 = 0.05185`, `concentrationScalesPeak = true`, fixed 8 h curve) — U100/U200 is selected via the insulin concentration setting.
  - Editor UI supports switching between Bilinear and PD models, editing PD parameters with bounds validation, toggling `concentrationScalesPeak`, and displays a `PD` badge.

### Autosens UAM changes
- Configurable **UAM detection factor** (`autosens_uam_factor`, default `2.0`) under Absorption → Advanced Settings.
- When UAM is detected for the first time, the previous 20 minutes are also marked as UAM so Autosens does not overcorrect.

### Profile helper & HardLimits
- **Circadian Default Profile** helper in Profile Helper (unit-aware in both mg/dL and mmol/L).
- `HardLimits.LIMIT_MAX_BG` lower bound relaxed from `90 mg/dL` to `80 mg/dL` so profile high targets down to `80 mg/dL` are accepted without clamping.

## Migration notes (Wavez 3.4 → 4.0)

Accepted behavior changes compared to Wavez 3.4:
1. **SMB low-temp formula:** adopts upstream 4.0's corrected `basal * (30 - durationReq) / 30` and `SmbIntervalGate`.
2. **SMB size factor:** explicit MasterOnly setting defaulting to `100%` (preserving 3.4 full-size SMBs); `50%` is opt-in.
3. **Wave bypasses:** SMB-disable and `eventualBG < min_bg` bypasses remain active while Wave is active; predictive LGS (`bg < threshold || minGuardBG < threshold`) remains active, preserving exact 3.4 runtime behavior.
4. **PD duration & DIA:** PD curves always use `durationMinutes = 480` (8 h), IOB query windows/arrays cover at least 8 h, and PD DIA is locked read-only at 8 h so a shorter profile DIA cannot hide the PD tail.
5. **v3 upgrade & history backfill:** importing a 3.4 backup migrates Wave, UAM, SMB-factor, and PD preferences automatically (defaulting missing `a0` to `51.0`). Profile switches backfill PD by label; boluses within the last 8 h receive the active PD model; older boluses stay bilinear. Invalid PD models stay visible on profile switches/catalogue but are refused by APS dosing paths.
6. **UAM safety guards:** previous-20-minute UAM marking is bounds-checked at the start of the table, and invalid stored UAM factors fall back to `2.0`.
7. **Database version 37:** Room schema is bumped `36 → 37` in `WavezDatabaseMigrations.kt` to add the `insulinModel` column. Keep a 3.4 APK and full backup before upgrading; Room will not downgrade from 37 back to upstream 36 without clearing app data.

---

# AAPS
* Check the wiki: https://wiki.aaps.app
* Want to contribute? Please read [CONTRIBUTING.md](CONTRIBUTING.md) first — note that AI-generated pull requests are not welcome.
*  Everyone who’s been looping with AAPS needs to fill out the form after 3 days of looping  https://docs.google.com/forms/d/14KcMjlINPMJHVt28MDRupa4sz4DDIooI4SrW0P3HSN8/viewform?c=0&w=1

[![Support Server](https://img.shields.io/discord/629952586895851530.svg?label=Discord&logo=Discord&colorB=7289da&style=for-the-badge)](https://discord.gg/4fQUWHZ4Mw)

[![CircleCI](https://circleci.com/gh/nightscout/AndroidAPS/tree/master.svg?style=svg)](https://circleci.com/gh/nightscout/AndroidAPS/tree/master)
[![Crowdin](https://d322cqt584bo4o.cloudfront.net/androidaps/localized.svg)](https://translations.aaps.app/project/androidaps)
[![Documentation Status](https://readthedocs.org/projects/androidaps/badge/?version=latest)](https://wiki.aaps.app/en/latest/?badge=latest)
[![codecov](https://codecov.io/gh/nightscout/AndroidAPS/branch/master/graph/badge.svg?token=EmklfIV6bH)](https://codecov.io/gh/nightscout/AndroidAPS)

DEV: 
[![CircleCI](https://circleci.com/gh/nightscout/AndroidAPS/tree/dev.svg?style=svg)](https://circleci.com/gh/nightscout/AndroidAPS/tree/dev)
[![codecov](https://codecov.io/gh/nightscout/AndroidAPS/branch/dev/graph/badge.svg?token=EmklfIV6bH)](https://codecov.io/gh/nightscout/AndroidAPS/tree/dev)

<img src="https://cdn.iconscout.com/icon/free/png-256/bitcoin-384-920569.png" srcset="https://cdn.iconscout.com/icon/free/png-512/bitcoin-384-920569.png 2x" alt="Bitcoin Icon" width="100">

3KawK8aQe48478s6fxJ8Ms6VTWkwjgr9f2
