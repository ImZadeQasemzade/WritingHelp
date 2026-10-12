"""Wires the triggers, the popup, Settings and the tray together."""

import sys
import threading

from PySide6.QtCore import QObject, Signal
from PySide6.QtGui import QCursor
from PySide6.QtWidgets import QApplication, QMessageBox

from . import winapi
from .settings import Settings
from .ui import Popup, SettingsWindow, Tray, app_icon


class Controller(QObject):
    # Cross-thread hand-offs; Qt queues these onto the UI thread.
    _triggered = Signal()
    _captured = Signal(object, object, object, object)  # text, previous clipboard, hwnd, anchor
    _hotkey_failed = Signal()

    def __init__(self, app: QApplication):
        super().__init__()
        self.app = app
        winapi.clipboard_owner()  # create it on the UI thread so it outlives worker threads
        self.settings = Settings()
        self.paused = False
        self.capturing = False
        self.popup = Popup(self.settings, self.open_settings)
        self.settings_window = SettingsWindow(self.settings, self.apply_triggers)
        self.tray = Tray(self.open_settings, self.set_paused, self.quit)

        self._triggered.connect(self._on_trigger)
        self._captured.connect(self._on_captured)
        self._hotkey_failed.connect(lambda: self.tray.notify(
            "Shortcut unavailable",
            f"{winapi.HOTKEY_LABEL} is already used by another app. Ctrl + right-click still works.",
        ))

        self.listener = winapi.TriggerListener(
            on_trigger=lambda source: self._triggered.emit(),
            on_hotkey_failed=self._hotkey_failed.emit,
        )
        self.listener.start()
        self.apply_triggers()

        if not self.settings.api_key:
            self.open_settings()
        if not self.settings.welcome_shown:
            self.settings.welcome_shown = True
            self.tray.notify(
                "WritingHelp is running",
                f"Select text anywhere, then press {winapi.HOTKEY_LABEL} or Ctrl + right-click.",
            )

    def apply_triggers(self):
        active = not self.paused
        self.listener.configure(
            hotkey_enabled=active and self.settings.hotkey_enabled,
            ctrl_click_enabled=active and self.settings.ctrl_click_enabled,
        )

    def set_paused(self, paused: bool):
        self.paused = paused
        self.apply_triggers()

    def open_settings(self):
        self.popup.close()
        self.settings_window.open_window()

    def _on_trigger(self):
        if self.capturing:
            return
        self.popup.close()
        hwnd = winapi.foreground_window()
        if self.settings_window.isVisible() and hwnd == int(self.settings_window.winId()):
            return  # ignore triggers inside our own Settings window
        anchor = QCursor.pos()
        self.capturing = True

        def capture():
            text, previous = winapi.copy_selection()
            self._captured.emit(text, previous, hwnd, anchor)

        threading.Thread(target=capture, daemon=True).start()

    def _on_captured(self, text, previous, hwnd, anchor):
        self.capturing = False
        if not text or not text.strip():
            self.tray.notify("Nothing selected", "Select some text first, then try again.")
            return
        self.popup.open_for(text, previous, hwnd, anchor)

    def quit(self):
        self.listener.stop()
        self.tray.icon.hide()
        self.app.quit()


def main():
    app = QApplication(sys.argv)
    app.setQuitOnLastWindowClosed(False)
    app.setApplicationName("WritingHelp")
    app.setWindowIcon(app_icon())
    if not winapi.claim_single_instance():
        QMessageBox.information(None, "WritingHelp", "WritingHelp is already running in the tray.")
        return 0
    controller = Controller(app)  # noqa: F841 - kept alive for the app's lifetime
    return app.exec()
