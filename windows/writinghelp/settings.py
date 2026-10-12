"""Settings in %APPDATA%\\WritingHelp\\settings.json. The API key is encrypted with Windows DPAPI,
so only this Windows user account can read it back."""

import base64
import ctypes
import ctypes.wintypes as wt
import json
import os
import sys
import winreg

from .prompts import DEFAULT_MAIN_LANGUAGE, DEFAULT_MODEL_ID, DEFAULT_SECOND_LANGUAGE

APP_DIR = os.path.join(os.environ.get("APPDATA", os.path.expanduser("~")), "WritingHelp")
SETTINGS_PATH = os.path.join(APP_DIR, "settings.json")
RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
RUN_VALUE = "WritingHelp"


class _Blob(ctypes.Structure):
    _fields_ = [("cbData", wt.DWORD), ("pbData", ctypes.POINTER(ctypes.c_char))]


_crypt32 = ctypes.WinDLL("crypt32")
_kernel32 = ctypes.WinDLL("kernel32")
_kernel32.LocalFree.argtypes = [ctypes.c_void_p]
for _fn in (_crypt32.CryptProtectData, _crypt32.CryptUnprotectData):
    _fn.argtypes = [
        ctypes.POINTER(_Blob), ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p,
        ctypes.c_void_p, wt.DWORD, ctypes.POINTER(_Blob),
    ]
    _fn.restype = wt.BOOL


def _dpapi(data: bytes, protect: bool) -> bytes:
    buffer = ctypes.create_string_buffer(data, len(data))
    blob_in = _Blob(len(data), ctypes.cast(buffer, ctypes.POINTER(ctypes.c_char)))
    blob_out = _Blob()
    fn = _crypt32.CryptProtectData if protect else _crypt32.CryptUnprotectData
    if not fn(ctypes.byref(blob_in), None, None, None, None, 0, ctypes.byref(blob_out)):
        raise OSError("DPAPI call failed")
    try:
        return ctypes.string_at(blob_out.pbData, blob_out.cbData)
    finally:
        _kernel32.LocalFree(blob_out.pbData)


class Settings:
    def __init__(self):
        self._data = {}
        try:
            with open(SETTINGS_PATH, encoding="utf-8") as f:
                self._data = json.load(f)
        except (OSError, ValueError):
            pass

    def _save(self):
        os.makedirs(APP_DIR, exist_ok=True)
        tmp = SETTINGS_PATH + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(self._data, f, indent=2)
        os.replace(tmp, SETTINGS_PATH)

    def _get(self, key, default):
        return self._data.get(key, default)

    def _set(self, key, value):
        self._data[key] = value
        self._save()

    @property
    def api_key(self) -> str:
        stored = self._data.get("api_key")
        if not stored:
            return ""
        try:
            return _dpapi(base64.b64decode(stored), protect=False).decode("utf-8")
        except (OSError, ValueError):
            return ""

    @api_key.setter
    def api_key(self, value: str):
        value = value.strip()
        self._set("api_key", base64.b64encode(_dpapi(value.encode("utf-8"), True)).decode() if value else "")

    model = property(lambda s: s._get("model", DEFAULT_MODEL_ID), lambda s, v: s._set("model", v))
    main_language = property(
        lambda s: s._get("main_language", DEFAULT_MAIN_LANGUAGE), lambda s, v: s._set("main_language", v)
    )
    second_language = property(
        lambda s: s._get("second_language", DEFAULT_SECOND_LANGUAGE),
        lambda s, v: s._set("second_language", v),
    )
    hotkey_enabled = property(
        lambda s: s._get("hotkey_enabled", True), lambda s, v: s._set("hotkey_enabled", v)
    )
    ctrl_click_enabled = property(
        lambda s: s._get("ctrl_click_enabled", True), lambda s, v: s._set("ctrl_click_enabled", v)
    )
    welcome_shown = property(
        lambda s: s._get("welcome_shown", False), lambda s, v: s._set("welcome_shown", v)
    )

    # Start with Windows lives in the registry rather than the JSON, so it can't drift out of sync.
    @property
    def start_with_windows(self) -> bool:
        try:
            with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY) as key:
                winreg.QueryValueEx(key, RUN_VALUE)
                return True
        except OSError:
            return False

    @start_with_windows.setter
    def start_with_windows(self, enabled: bool):
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_SET_VALUE) as key:
            if enabled:
                winreg.SetValueEx(key, RUN_VALUE, 0, winreg.REG_SZ, _launch_command())
            else:
                try:
                    winreg.DeleteValue(key, RUN_VALUE)
                except OSError:
                    pass


def _launch_command() -> str:
    if getattr(sys, "frozen", False):
        return f'"{sys.executable}"'
    pythonw = os.path.join(os.path.dirname(sys.executable), "pythonw.exe")
    script = os.path.abspath(sys.argv[0])
    return f'"{pythonw}" "{script}"'
