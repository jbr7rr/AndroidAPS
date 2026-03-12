# Wavez

Fork of AAPS, based on the amazing Tsunami fork: https://github.com/piecycle/tsunami

The goal of this fork is to allow for a as close to FCL experience we can get. Changes are made with that target in mind.
Please note that while these changes work perfect for me, it might not work for you.

## Changes to Tsunami algorithm

- Some code changes to make it easier to maintain
- Tsunami Mode removed, only wave remains
- Activity target for low delta's removed, low delta's (< 4.1mg/dl) now fall back on oref1
- Added SMB minutes specific for Wave mode, uses the same safety measures as SMB cap, will scale better with different profile's then SMB cap
- BG Score Thresholds (at max, full SMB will be delivered, at min no SMB will be delivered) changed from 80-140(min-max) to: target_bg - (target_bg + 30), So this will better scale with (temp) targets
- Setting to use Autosens/dynISF calculations for Wave (EXPERIMENTAL)

## Insulin plugin:

- Added custom PD insulin plugin, in which the settings can be changed, model to see how the parameters effect can be found here: https://colab.research.google.com/drive/1cGITp_b4xxaG3TPetLAMbPJfSEKACK0o
- Optimized settings for Lyumjev 100U used in Medtrum: a0: 51.0 a1: 12.27 b1: 0.05185

## Autosens changes:

Goal of modifications is better UAM detection, so autosens doesn't overcorrect
- Added UAM detection factor setting (keep at default, unless UAM detection doesn't work well for you)
- When UAM is detected for the first time, the previous 20 min is also marked as UAM

## Profile helper

- Added Circadian Default Profile helper, to allow for easy generation of a circadian profile

## Layout changes

- Better size of user automation buttons
- Made large layout graph a bit smaller so its bigger but not as extreme

# AAPS
* Check the wiki: https://wiki.aaps.app
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
