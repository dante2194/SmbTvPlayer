# SMB TV Player

Android TV app that scans your LAN for SMB/CIFS servers, opens them automatically
and plays your videos with an ExoPlayer-based player (D-pad / remote friendly).

* Auto discovery (TCP 445 sweep of local subnets) — last used / only server opens by itself
* Guest/anonymous login first, login dialog + saved credentials when required
* Streams directly over SMB with seeking (no copying)
* Remote: OK = play/pause, ←/→ = seek (hold to accelerate), ↑/↓ = controls,
  CH+/CH− or Next/Prev = next/previous file, BACK = hide controls / exit
* Audio & subtitle track pickers, external .srt/.vtt/.ass, speed, aspect modes, resume position
