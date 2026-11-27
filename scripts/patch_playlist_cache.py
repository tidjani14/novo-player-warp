#!/usr/bin/env python3
"""Make NOVO reuse its on-device Realm playlist until credentials or Reload change."""
from __future__ import annotations

import sys
from pathlib import Path


TARGET = Path(sys.argv[1]) / "smali/com/shadeed/ibopro/apps/BaseActivity.smali"

# Each method already checks whether its Realm result set is non-empty. Its
# previous second check compared the current time to the configured expiry,
# causing large playlists to be downloaded again when the period elapsed.
# HomeActivity.reloadPortal() deliberately sets LastPlaylistDate to zero for a
# manual reload, while login code clears Realm when the account key changes.
# Keep those two invalidation paths and make the non-empty Realm cache win.
REWRITES = {
    "getChannelModels": (
        "cmp-long v2, v0, v8",
        "const-wide/16 v8, 0x1\n    cmp-long v2, v8, v6",
    ),
    "getEpisodeModels": (
        "cmp-long v3, v1, v5",
        "const-wide/16 v5, 0x1\n    cmp-long v3, v5, v3",
    ),
    "getLiveStreams": (
        "cmp-long v2, v0, v4",
        "const-wide/16 v4, 0x1\n    cmp-long v2, v4, v2",
    ),
    "getMovieModels": (
        "cmp-long v2, v0, v8",
        "const-wide/16 v8, 0x1\n    cmp-long v2, v8, v6",
    ),
    "getSeriesStreams": (
        "cmp-long v0, v2, v6",
        "const-wide/16 v6, 0x1\n    cmp-long v0, v6, v4",
    ),
    "getVodStreams": (
        "cmp-long v2, v0, v4",
        "const-wide/16 v4, 0x1\n    cmp-long v2, v4, v2",
    ),
}


def patch_method(source: str, name: str, old: str, new: str) -> tuple[str, bool]:
    marker = f".method private {name}()V"
    start = source.find(marker)
    if start < 0:
        raise SystemExit(f"Missing method: {name}")
    end = source.find(".end method", start)
    if end < 0:
        raise SystemExit(f"Unterminated method: {name}")
    end += len(".end method")
    method = source[start:end]
    if new in method:
        return source, False
    count = method.count(old)
    if count != 1:
        raise SystemExit(f"Expected one expiry check in {name}, found {count}")
    method = method.replace(old, new, 1)
    return source[:start] + method + source[end:], True


def main() -> None:
    if not TARGET.is_file():
        raise SystemExit(f"Missing Smali file: {TARGET}")
    source = TARGET.read_text(encoding="utf-8")
    changed = 0
    for name, (old, new) in REWRITES.items():
        source, did_change = patch_method(source, name, old, new)
        changed += int(did_change)
    TARGET.write_text(source, encoding="utf-8")
    print(f"Playlist cache-first patch ready ({changed} methods changed).")


if __name__ == "__main__":
    main()
