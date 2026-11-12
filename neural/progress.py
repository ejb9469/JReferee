"""Throttled console progress for cache verification and training."""

import os
import sys
import time


def windows_color(stream):
    import ctypes
    from ctypes import wintypes
    import msvcrt

    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel.GetConsoleMode.argtypes = (wintypes.HANDLE, ctypes.POINTER(wintypes.DWORD))
    kernel.GetConsoleMode.restype = wintypes.BOOL
    kernel.SetConsoleMode.argtypes = (wintypes.HANDLE, wintypes.DWORD)
    kernel.SetConsoleMode.restype = wintypes.BOOL
    handle = msvcrt.get_osfhandle(stream.fileno())
    mode = wintypes.DWORD()
    return bool(kernel.GetConsoleMode(handle, ctypes.byref(mode))
                and kernel.SetConsoleMode(handle, mode.value | 0x0004))


def supports_color(stream):
    if not stream.isatty() or "NO_COLOR" in os.environ or os.environ.get("TERM") == "dumb":
        return False
    return os.name != "nt" or windows_color(stream)


def duration(seconds):
    seconds = max(0, int(seconds))
    minutes, seconds = divmod(seconds, 60)
    hours, minutes = divmod(minutes, 60)
    return f"{hours}:{minutes:02d}:{seconds:02d}" if hours else f"{minutes:02d}:{seconds:02d}"


class Progress:
    COLORS = {"INFO": "96", "CACHE": "96", "TRAIN": "94", "VALID": "95",
              "SAVE": "92", "DONE": "92", "WARN": "93", "EPOCH": "1;96"}

    def __init__(self, enabled=False, stream=None):
        self.enabled = enabled
        self.stream = sys.stdout if stream is None else stream
        self.color = enabled and supports_color(self.stream)

    def message(self, kind, text):
        if self.enabled:
            label = f"[{kind}]"
            if self.color:
                label = f"\033[{self.COLORS.get(kind, '96')}m{label}\033[0m"
            print(f"{label} {text}", file=self.stream, flush=True)

    def summary(self, kind, text):
        if self.enabled:
            self.message(kind, text)
        else:
            print(text, file=self.stream, flush=True)

    def start(self, kind, label, total, unit="examples"):
        if not self.enabled:
            return
        if total < 0:
            raise ValueError("Negative progress total")
        self.kind, self.label, self.total, self.unit = kind, label, total, unit
        self.done = 0
        self.started = self.last = time.perf_counter()
        self._render(self.started, "")

    def update(self, done, detail=""):
        if not self.enabled:
            return
        if not self.done <= done <= self.total:
            raise ValueError("Progress count outside the current pass")
        self.done = done
        now = time.perf_counter()
        if now - self.last >= 2.0 and done < self.total:
            self._render(now, detail)
            self.last = now

    def finish(self, detail=""):
        if not self.enabled:
            return
        if self.done != self.total:
            raise ValueError("Cannot finish an incomplete progress pass")
        self._render(time.perf_counter(), detail, complete=True)

    def _render(self, now, detail, complete=False):
        elapsed = now - self.started
        percent = 100.0 * self.done / self.total if self.total else 100.0
        text = f"{self.label}: {self.done:,}/{self.total:,} {self.unit} ({percent:.1f}%)"
        text += f" | elapsed {duration(elapsed)}"
        if self.done and elapsed > 0:
            rate = self.done / elapsed
            text += f" | {rate:,.1f} {self.unit}/s"
            if not complete:
                text += f" | ETA ~{duration((self.total - self.done) / rate)}"
        elif not complete and self.total:
            text += " | ETA --"
        if detail:
            text += f" | {detail}"
        self.message("DONE" if complete else self.kind, text)