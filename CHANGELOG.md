# Changelog

The section of the newest version is what the app shows as "What's new" when it offers an update.

## 1.1.0

- **Updates from inside the app.** The gear icon on the start screen opens Settings with one button: "Check for updates" turns into "Update to vX" when a newer release exists. A small pill on the start screen also appears whenever an update is available. Downloads come from this repository's releases only and are verified before installing. The app looks for updates when it opens (switch in Settings).
- **Smoother stream.** Video travels in small slices so sound is never stuck behind a big picture; the sender adapts bitrate and frame rate to the network and to how well the TV's decoder keeps up, and drops stale video instead of building delay. Sound has an adaptive cushion on the TV against crackling.
- **Phone stays silent while streaming**, only the TV plays the sound. The app checks that the stream still carries sound after muting the phone; if a phone's system doesn't allow that, it un-mutes and says so.
- **16:9 option** for landscape phones: Original, Fill 16:9 (crops the edges, no distortion) or Stretch 16:9 (squeezes the picture).
- **Resolution is shown long side first** (for example 2400×1080).
- **Landscape layouts reworked** on phone and TV; the screens rebuild themselves when the phone is rotated.
- Fixes and robustness: receiver refuses senders on another protocol version with a clear message; many more automated tests.

**Both devices need this version**: the protocol changed, so a 1.1.0 phone can't talk to a 1.0.0 TV and vice versa. Install it by hand once on each; later updates come from the app.

## 1.0.0

First version: Send / Receive chooser, screen and sound streaming over the local network, presets, background streaming.
