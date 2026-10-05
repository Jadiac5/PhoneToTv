# Changelog

The section of the newest version is what the app shows as "What's new" when it offers an update.

## 1.1.2

- **A TV in Receive mode stays reachable.** After updating to 1.1.1 some phones got "Can't reach <TV>. Is PhoneStream open on it in Receive mode?" while the TV was showing Receive mode (restarting the TV helped, and it could come back after a few connections). The TV now retries its network port when it is still held by the previous copy of the app, never gets stuck "busy" if the video or sound player fails while a stream ends, and checks every few seconds that it still answers on its own port: if it doesn't, it opens a fresh one by itself. If it really can't listen, it says "Not ready" and why. The root cause could not be reproduced here, so please tell me if you still see the message.
- **Clearer connection errors on the phone**: the message now names the address it tried and the reason the system gave (for example "Connection refused" or "Network is unreachable"), and distinguishes "no answer" from "answered but did not reply".
- **Settings, "Picture and sound in step"**: the buttons now read "Picture earlier" and "Picture later", and the text says which to press (the sound itself is never changed).

Install it on both phone and TV (the protocol is unchanged, so the in-app update works).

## 1.1.1

- **No more stutter-then-fast-forward.** The TV used to show every frame the moment it was decoded, so when Wi-Fi or a big frame (lots of motion on screen) held the stream up for a moment, the picture froze and then played everything that had piled up at high speed. Frames now carry the phone's capture time and the TV plays them at that pace, with a small adaptive buffer (it grows at once when the network hiccups and shrinks slowly). If the TV is ever far behind, the picture jumps ahead instead of racing through the backlog.
- **Picture and sound in step.** The sound reached the speaker later than the picture reached the screen (cushion, speaker buffer, audio driver), so for example the sound kept going for a moment after you paused a video. The TV now measures when each piece of sound is really heard and shows the matching picture at that moment. The sender also time-stamps the sound more evenly.
- **Settings, "Picture and sound in step"** (on the TV): Earlier/Later buttons to fine-tune for a soundbar or Bluetooth speaker, which add delay the app can't measure.
- The sender reacts earlier to a slowing network (lowers bitrate sooner).
- Cost: the picture now waits for the sound, which adds roughly 0.1 s of overall delay compared with 1.1.0.

Install it on both phone and TV (the protocol is unchanged, so the in-app update works). The sender-side changes only take effect once the phone is updated too.

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
