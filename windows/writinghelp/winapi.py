"""The Win32 pieces: the global hotkey, the Ctrl+right-click mouse hook, the clipboard, simulated
Ctrl+C / Ctrl+V, and moving focus between windows. Everything goes through ctypes, so there are
no extra dependencies."""

import ctypes
import ctypes.wintypes as wt
import threading
import time

user32 = ctypes.WinDLL("user32", use_last_error=True)
kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)

LRESULT = ctypes.c_ssize_t
ULONG_PTR = ctypes.c_size_t

VK_SHIFT, VK_CONTROL, VK_MENU, VK_LWIN, VK_RWIN, VK_SPACE = 0x10, 0x11, 0x12, 0x5B, 0x5C, 0x20
VK_C, VK_V = 0x43, 0x56
MOD_CONTROL, MOD_SHIFT, MOD_NOREPEAT = 0x0002, 0x0004, 0x4000
WM_HOTKEY, WM_QUIT, WM_APP = 0x0312, 0x0012, 0x8000
WM_RBUTTONDOWN, WM_RBUTTONUP = 0x0204, 0x0205
WH_MOUSE_LL = 14
LLMHF_INJECTED = 0x01
CF_UNICODETEXT = 13
GMEM_MOVEABLE = 0x0002
INPUT_KEYBOARD = 1
KEYEVENTF_KEYUP = 0x0002
HOTKEY_ID = 1
HOTKEY_LABEL = "Ctrl+Shift+Space"


class KEYBDINPUT(ctypes.Structure):
    _fields_ = [("wVk", wt.WORD), ("wScan", wt.WORD), ("dwFlags", wt.DWORD),
                ("time", wt.DWORD), ("dwExtraInfo", ULONG_PTR)]


class MOUSEINPUT(ctypes.Structure):
    _fields_ = [("dx", wt.LONG), ("dy", wt.LONG), ("mouseData", wt.DWORD),
                ("dwFlags", wt.DWORD), ("time", wt.DWORD), ("dwExtraInfo", ULONG_PTR)]


class HARDWAREINPUT(ctypes.Structure):
    _fields_ = [("uMsg", wt.DWORD), ("wParamL", wt.WORD), ("wParamH", wt.WORD)]


class _InputUnion(ctypes.Union):
    _fields_ = [("ki", KEYBDINPUT), ("mi", MOUSEINPUT), ("hi", HARDWAREINPUT)]


class INPUT(ctypes.Structure):
    _fields_ = [("type", wt.DWORD), ("u", _InputUnion)]


class MSLLHOOKSTRUCT(ctypes.Structure):
    _fields_ = [("pt", wt.POINT), ("mouseData", wt.DWORD), ("flags", wt.DWORD),
                ("time", wt.DWORD), ("dwExtraInfo", ULONG_PTR)]


HOOKPROC = ctypes.WINFUNCTYPE(LRESULT, ctypes.c_int, wt.WPARAM, wt.LPARAM)

user32.SendInput.argtypes = [wt.UINT, ctypes.POINTER(INPUT), ctypes.c_int]
user32.SendInput.restype = wt.UINT
user32.GetAsyncKeyState.argtypes = [ctypes.c_int]
user32.GetAsyncKeyState.restype = ctypes.c_short
user32.SetWindowsHookExW.argtypes = [ctypes.c_int, HOOKPROC, wt.HINSTANCE, wt.DWORD]
user32.SetWindowsHookExW.restype = wt.HHOOK
user32.CallNextHookEx.argtypes = [wt.HHOOK, ctypes.c_int, wt.WPARAM, wt.LPARAM]
user32.CallNextHookEx.restype = LRESULT
user32.UnhookWindowsHookEx.argtypes = [wt.HHOOK]
user32.RegisterHotKey.argtypes = [wt.HWND, ctypes.c_int, wt.UINT, wt.UINT]
user32.UnregisterHotKey.argtypes = [wt.HWND, ctypes.c_int]
user32.GetMessageW.argtypes = [ctypes.POINTER(wt.MSG), wt.HWND, wt.UINT, wt.UINT]
user32.GetMessageW.restype = wt.BOOL
user32.PostThreadMessageW.argtypes = [wt.DWORD, wt.UINT, wt.WPARAM, wt.LPARAM]
user32.OpenClipboard.argtypes = [wt.HWND]
user32.GetClipboardData.argtypes = [wt.UINT]
user32.GetClipboardData.restype = wt.HANDLE
user32.SetClipboardData.argtypes = [wt.UINT, wt.HANDLE]
user32.SetClipboardData.restype = wt.HANDLE
user32.GetClipboardSequenceNumber.restype = wt.DWORD
user32.RegisterClipboardFormatW.argtypes = [wt.LPCWSTR]
user32.RegisterClipboardFormatW.restype = wt.UINT
user32.GetForegroundWindow.restype = wt.HWND
user32.SetForegroundWindow.argtypes = [wt.HWND]
user32.BringWindowToTop.argtypes = [wt.HWND]
user32.IsWindow.argtypes = [wt.HWND]
user32.GetWindowThreadProcessId.argtypes = [wt.HWND, ctypes.POINTER(wt.DWORD)]
user32.GetWindowThreadProcessId.restype = wt.DWORD
user32.AttachThreadInput.argtypes = [wt.DWORD, wt.DWORD, wt.BOOL]
kernel32.GetCurrentThreadId.restype = wt.DWORD
kernel32.GetModuleHandleW.argtypes = [wt.LPCWSTR]
kernel32.GetModuleHandleW.restype = wt.HMODULE
kernel32.GlobalAlloc.argtypes = [wt.UINT, ctypes.c_size_t]
kernel32.GlobalAlloc.restype = wt.HGLOBAL
kernel32.GlobalLock.argtypes = [wt.HGLOBAL]
kernel32.GlobalLock.restype = ctypes.c_void_p
kernel32.GlobalUnlock.argtypes = [wt.HGLOBAL]
kernel32.GlobalFree.argtypes = [wt.HGLOBAL]
kernel32.CreateMutexW.argtypes = [ctypes.c_void_p, wt.BOOL, wt.LPCWSTR]
kernel32.CreateMutexW.restype = wt.HANDLE

# Tells Windows' clipboard history (Win+V) and cloud clipboard to skip what we put there.
_EXCLUDE_FROM_HISTORY = user32.RegisterClipboardFormatW("ExcludeClipboardContentFromMonitorProcessing")


def is_key_down(vk: int) -> bool:
    return bool(user32.GetAsyncKeyState(vk) & 0x8000)


# ---------- keyboard ----------

def _key(vk: int, up: bool) -> INPUT:
    event = INPUT(type=INPUT_KEYBOARD)
    event.u.ki = KEYBDINPUT(wVk=vk, dwFlags=KEYEVENTF_KEYUP if up else 0)
    return event


def _send(events):
    array = (INPUT * len(events))(*events)
    user32.SendInput(len(events), array, ctypes.sizeof(INPUT))


def release_modifiers(timeout: float = 0.6):
    """Waits for the user to let go of the trigger keys, so our Ctrl+C isn't read as e.g.
    Ctrl+Shift+C. Anything still held after the timeout gets a synthetic key-up."""
    keys = (VK_SHIFT, VK_CONTROL, VK_MENU, VK_LWIN, VK_RWIN, VK_SPACE)
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if not any(is_key_down(k) for k in keys):
            return
        time.sleep(0.01)
    _send([_key(k, True) for k in keys if is_key_down(k)])


def press_ctrl(vk: int):
    _send([_key(VK_CONTROL, False), _key(vk, False), _key(vk, True), _key(VK_CONTROL, True)])


# ---------- clipboard ----------

user32.CreateWindowExW.argtypes = [
    wt.DWORD, wt.LPCWSTR, wt.LPCWSTR, wt.DWORD, ctypes.c_int, ctypes.c_int, ctypes.c_int,
    ctypes.c_int, wt.HWND, wt.HMENU, wt.HINSTANCE, wt.LPVOID,
]
user32.CreateWindowExW.restype = wt.HWND
HWND_MESSAGE = wt.HWND(-3)
_clipboard_owner = None


def clipboard_owner():
    """SetClipboardData fails unless the clipboard was opened with a window, so we keep a
    hidden message-only one around for that."""
    global _clipboard_owner
    if not _clipboard_owner:
        _clipboard_owner = user32.CreateWindowExW(
            0, "STATIC", "WritingHelpClipboard", 0, 0, 0, 0, 0, HWND_MESSAGE, None, None, None
        )
    return _clipboard_owner


def _open_clipboard() -> bool:
    owner = clipboard_owner()
    for _ in range(20):  # another app may be holding it for a moment
        if user32.OpenClipboard(owner):
            return True
        time.sleep(0.01)
    return False


def get_clipboard_text():
    if not _open_clipboard():
        return None
    try:
        handle = user32.GetClipboardData(CF_UNICODETEXT)
        if not handle:
            return None
        pointer = kernel32.GlobalLock(handle)
        if not pointer:
            return None
        try:
            return ctypes.wstring_at(pointer)
        finally:
            kernel32.GlobalUnlock(handle)
    finally:
        user32.CloseClipboard()


def _global_bytes(data: bytes):
    handle = kernel32.GlobalAlloc(GMEM_MOVEABLE, len(data))
    pointer = kernel32.GlobalLock(handle)
    ctypes.memmove(pointer, data, len(data))
    kernel32.GlobalUnlock(handle)
    return handle


def set_clipboard_text(text: str, keep_out_of_history: bool = True):
    if not _open_clipboard():
        return
    try:
        user32.EmptyClipboard()
        handle = _global_bytes(text.encode("utf-16-le") + b"\0\0")
        if not user32.SetClipboardData(CF_UNICODETEXT, handle):
            kernel32.GlobalFree(handle)
        if keep_out_of_history and _EXCLUDE_FROM_HISTORY:
            marker = _global_bytes(b"\0\0\0\0")
            if not user32.SetClipboardData(_EXCLUDE_FROM_HISTORY, marker):
                kernel32.GlobalFree(marker)
    finally:
        user32.CloseClipboard()


def copy_selection(timeout: float = 0.7):
    """Copies the selection in the focused app and returns (selected_text, previous_clipboard_text).
    selected_text is None when nothing got copied. The clipboard is put back right away."""
    previous = get_clipboard_text()
    release_modifiers()
    sequence = user32.GetClipboardSequenceNumber()
    press_ctrl(VK_C)
    deadline = time.monotonic() + timeout
    selected = None
    while time.monotonic() < deadline:
        if user32.GetClipboardSequenceNumber() != sequence:
            time.sleep(0.03)  # some apps write the clipboard in several steps
            selected = get_clipboard_text()
            break
        time.sleep(0.01)
    if selected is not None and previous is not None:
        set_clipboard_text(previous, keep_out_of_history=False)
    return selected, previous


def paste_into(hwnd, text: str, previous):
    """Focuses hwnd and pastes text over its selection, then restores the previous clipboard text."""
    focus_window(hwnd)
    time.sleep(0.15)
    set_clipboard_text(text)
    press_ctrl(VK_V)
    time.sleep(0.5)  # give the app time to read the clipboard before we put the old text back
    if previous is not None:
        set_clipboard_text(previous, keep_out_of_history=False)


# ---------- windows ----------

def foreground_window():
    return user32.GetForegroundWindow()


def focus_window(hwnd):
    """SetForegroundWindow, borrowing the current foreground thread's input state so Windows'
    focus-stealing rules let it through."""
    if not hwnd or not user32.IsWindow(hwnd):
        return
    current = kernel32.GetCurrentThreadId()
    foreground_thread = user32.GetWindowThreadProcessId(user32.GetForegroundWindow(), None)
    attached = foreground_thread and foreground_thread != current and user32.AttachThreadInput(
        current, foreground_thread, True
    )
    try:
        user32.BringWindowToTop(hwnd)
        user32.SetForegroundWindow(hwnd)
    finally:
        if attached:
            user32.AttachThreadInput(current, foreground_thread, False)


def claim_single_instance(name: str = "Local\\WritingHelp.SingleInstance") -> bool:
    """False when another copy of the app is already running."""
    global _mutex
    _mutex = kernel32.CreateMutexW(None, False, name)
    return ctypes.get_last_error() != 183  # ERROR_ALREADY_EXISTS


_mutex = None


# ---------- triggers ----------

class TriggerListener(threading.Thread):
    """Runs the low-level mouse hook and the global hotkey on their own thread with a message
    loop. on_trigger(source) is called from this thread, so it must hand off quickly (a slow
    mouse hook stalls the mouse for the whole system)."""

    _APPLY = WM_APP + 1

    def __init__(self, on_trigger, on_hotkey_failed):
        super().__init__(daemon=True, name="WritingHelpTriggers")
        self._on_trigger = on_trigger
        self._on_hotkey_failed = on_hotkey_failed
        self._proc = HOOKPROC(self._mouse_proc)
        self._thread_id = None
        self._ready = threading.Event()
        self._swallow_up = False
        self._hotkey_registered = False
        self.hotkey_enabled = False
        self.ctrl_click_enabled = False

    def configure(self, hotkey_enabled: bool, ctrl_click_enabled: bool):
        self.hotkey_enabled = hotkey_enabled
        self.ctrl_click_enabled = ctrl_click_enabled
        self._ready.wait(2)
        if self._thread_id:
            user32.PostThreadMessageW(self._thread_id, self._APPLY, 0, 0)

    def stop(self):
        if self._thread_id:
            user32.PostThreadMessageW(self._thread_id, WM_QUIT, 0, 0)

    def _apply_hotkey(self):
        if self.hotkey_enabled and not self._hotkey_registered:
            self._hotkey_registered = bool(
                user32.RegisterHotKey(None, HOTKEY_ID, MOD_CONTROL | MOD_SHIFT | MOD_NOREPEAT, VK_SPACE)
            )
            if not self._hotkey_registered:
                self._on_hotkey_failed()
        elif not self.hotkey_enabled and self._hotkey_registered:
            user32.UnregisterHotKey(None, HOTKEY_ID)
            self._hotkey_registered = False

    def _mouse_proc(self, code, wparam, lparam):
        if code == 0 and self.ctrl_click_enabled:
            info = ctypes.cast(lparam, ctypes.POINTER(MSLLHOOKSTRUCT)).contents
            if not info.flags & LLMHF_INJECTED:
                if (wparam == WM_RBUTTONDOWN and is_key_down(VK_CONTROL)
                        and not is_key_down(VK_SHIFT) and not is_key_down(VK_MENU)):
                    self._swallow_up = True
                    return 1
                if wparam == WM_RBUTTONUP and self._swallow_up:
                    self._swallow_up = False
                    self._on_trigger("click")
                    return 1
        return user32.CallNextHookEx(None, code, wparam, lparam)

    def run(self):
        self._thread_id = kernel32.GetCurrentThreadId()
        hook = user32.SetWindowsHookExW(WH_MOUSE_LL, self._proc, kernel32.GetModuleHandleW(None), 0)
        self._ready.set()
        msg = wt.MSG()
        try:
            while user32.GetMessageW(ctypes.byref(msg), None, 0, 0) > 0:
                if msg.message == WM_HOTKEY and msg.wParam == HOTKEY_ID:
                    self._on_trigger("hotkey")
                elif msg.message == self._APPLY:
                    self._apply_hotkey()
        finally:
            if self._hotkey_registered:
                user32.UnregisterHotKey(None, HOTKEY_ID)
            if hook:
                user32.UnhookWindowsHookEx(hook)
