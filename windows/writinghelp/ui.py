"""Qt UI: the popup that appears at the mouse pointer, the Settings window and the tray icon."""

import threading
import unicodedata

from PySide6.QtCore import QEvent, QObject, QPoint, QRectF, Qt, QTimer, Signal
from PySide6.QtGui import (
    QAction, QColor, QFont, QGuiApplication, QIcon, QKeySequence, QPainter, QPainterPath, QPixmap,
    QShortcut, QTextCursor,
)
from PySide6.QtWidgets import (
    QCheckBox, QComboBox, QDialog, QFormLayout, QFrame, QHBoxLayout, QLabel, QLineEdit, QMenu,
    QLayout, QPushButton, QTextEdit, QSystemTrayIcon, QVBoxLayout, QWidget,
)

from . import claude, winapi
from .prompts import (
    FALLBACK_MODELS, LANGUAGES, REFINEMENT_CHIPS, Mode, ModelOption, default_model_from,
    description_for, system_prompt_for,
)


def is_dark() -> bool:
    return QGuiApplication.styleHints().colorScheme() == Qt.ColorScheme.Dark


def app_icon(size: int = 64) -> QIcon:
    """A rounded purple square with a white pen nib-ish "W", drawn so there's no image file to ship."""
    pixmap = QPixmap(size, size)
    pixmap.fill(Qt.transparent)
    painter = QPainter(pixmap)
    painter.setRenderHint(QPainter.Antialiasing)
    path = QPainterPath()
    path.addRoundedRect(QRectF(0, 0, size, size), size * 0.22, size * 0.22)
    painter.fillPath(path, QColor("#6750A4"))
    painter.setPen(QColor("white"))
    font = QFont("Segoe UI", int(size * 0.5))
    font.setBold(True)
    painter.setFont(font)
    painter.drawText(pixmap.rect(), Qt.AlignCenter, "W")
    painter.end()
    return QIcon(pixmap)


def _popup_style() -> str:
    if is_dark():
        bg, fg, sub, border, btn, btn_hover, edit = (
            "#2B2930", "#E6E0E9", "#CAC4D0", "#49454F", "#36343B", "#45424C", "#1D1B20")
        primary, on_primary = "#D0BCFF", "#381E72"
    else:
        bg, fg, sub, border, btn, btn_hover, edit = (
            "#FFFFFF", "#1D1B20", "#49454F", "#CAC4D0", "#F3EDF7", "#E8DEF8", "#FEF7FF")
        primary, on_primary = "#6750A4", "#FFFFFF"
    return f"""
        #card {{ background: {bg}; border: 1px solid {border}; border-radius: 12px; }}
        QLabel {{ color: {fg}; }}
        QLabel#sub {{ color: {sub}; font-size: 9pt; }}
        QLabel#error {{ color: {"#F2B8B5" if is_dark() else "#B3261E"}; }}
        QPushButton {{ background: {btn}; color: {fg}; border: none; border-radius: 8px;
                       padding: 7px 12px; text-align: left; }}
        QPushButton:hover {{ background: {btn_hover}; }}
        QPushButton#primary {{ background: {primary}; color: {on_primary}; text-align: center; }}
        QPushButton#chip {{ border: 1px solid {border}; background: transparent; border-radius: 14px;
                            padding: 4px 10px; text-align: center; }}
        QPushButton#chip:hover {{ background: {btn_hover}; }}
        QPushButton#flat {{ background: transparent; padding: 2px 6px; text-align: center; }}
        QPushButton#action {{ text-align: center; }}
        QTextEdit {{ background: {edit}; color: {fg}; border: 1px solid {border};
                          border-radius: 8px; padding: 4px; }}
    """


def _paragraph_direction(text: str):
    """Right-to-left if the first letter with a direction is Persian/Arabic/Hebrew, like browsers do."""
    for ch in text:
        kind = unicodedata.bidirectional(ch)
        if kind in ("R", "AL"):
            return Qt.RightToLeft
        if kind == "L":
            return Qt.LeftToRight
    return Qt.LeftToRight


def _set_paragraph_directions(edit: QTextEdit):
    block = edit.document().begin()
    while block.isValid():
        cursor = QTextCursor(block)
        block_format = cursor.blockFormat()
        block_format.setLayoutDirection(_paragraph_direction(block.text()))
        cursor.setBlockFormat(block_format)
        block = block.next()


class Popup(QWidget):
    """Menu -> loading -> result (or error), anchored at the mouse pointer.

    Result actions mirror the Android result sheet: Replace pastes into the app the text came
    from, Copy puts it on the clipboard, and AI Rewrite offers the refinement chips."""

    _finished = Signal(int, object, object)  # request id, text, ClaudeError

    def __init__(self, settings, open_settings):
        super().__init__(None, Qt.Tool | Qt.FramelessWindowHint | Qt.WindowStaysOnTopHint)
        self.setAttribute(Qt.WA_TranslucentBackground)
        self.settings = settings
        self.open_settings = open_settings
        self.target_hwnd = None
        self.original = ""
        self.previous_clipboard = None
        self.current = ""
        self.mode = None
        self.request_id = 0
        self.anchor = QPoint()
        self._finished.connect(self._on_finished)

        outer = QVBoxLayout(self)
        outer.setContentsMargins(0, 0, 0, 0)
        outer.setSizeConstraint(QLayout.SetFixedSize)  # the window always fits the current page
        card = QFrame(objectName="card")
        outer.addWidget(card)
        layout = QVBoxLayout(card)
        layout.setContentsMargins(12, 10, 12, 12)
        layout.setSpacing(8)

        header = QHBoxLayout()
        self.title = QLabel("WritingHelp", objectName="sub")
        close = QPushButton("✕", objectName="flat")
        close.setCursor(Qt.PointingHandCursor)
        close.clicked.connect(self.close)
        header.addWidget(self.title)
        header.addStretch()
        header.addWidget(close)
        layout.addLayout(header)

        self.pages = []
        self.page_layout = layout
        self.current_page = None
        self._build_menu()
        self._build_loading()
        self._build_result()
        self._build_error()

        QShortcut(QKeySequence(Qt.Key_Escape), self, activated=self.close)
        QShortcut(QKeySequence("Ctrl+Return"), self, activated=self._replace)
        self.setStyleSheet(_popup_style())

    # ----- pages -----

    def _build_menu(self):
        page = QWidget()
        column = QVBoxLayout(page)
        column.setContentsMargins(0, 0, 0, 0)
        column.setSpacing(4)
        for mode, key in ((Mode.FIX_GRAMMAR, "G"), (Mode.REWRITE, "R"), (Mode.TRANSLATE, "T")):
            button = QPushButton(f"{mode.title}    ({key})")
            button.setCursor(Qt.PointingHandCursor)
            button.setMinimumWidth(200)
            button.clicked.connect(lambda _=False, m=mode: self._start(m))
            column.addWidget(button)
            QShortcut(QKeySequence(key), page, activated=lambda m=mode: self._start(m),
                      context=Qt.WidgetWithChildrenShortcut)
            if mode is Mode.FIX_GRAMMAR:
                self.first_button = button
        self.menu_page = page
        self._add_page(page)

    def _build_loading(self):
        page = QWidget()
        column = QVBoxLayout(page)
        column.setContentsMargins(4, 8, 4, 8)
        self.loading_label = QLabel()
        column.addWidget(self.loading_label)
        self.loading_page = page
        self._add_page(page)

    def _build_result(self):
        page = QWidget()
        column = QVBoxLayout(page)
        column.setContentsMargins(0, 0, 0, 0)
        column.setSpacing(8)
        self.result_edit = QTextEdit()
        self.result_edit.setAcceptRichText(False)
        self.result_edit.setMinimumSize(380, 110)
        self.result_edit.setFont(QFont("Segoe UI", 10))
        column.addWidget(self.result_edit)

        self.chips = QWidget()
        chip_row = QHBoxLayout(self.chips)
        chip_row.setContentsMargins(0, 0, 0, 0)
        chip_row.setSpacing(4)
        for chip in REFINEMENT_CHIPS:
            button = QPushButton(chip.label, objectName="chip")
            button.setCursor(Qt.PointingHandCursor)
            button.clicked.connect(lambda _=False, c=chip: self._run(self.current, c.instruction))
            chip_row.addWidget(button)
        chip_row.addStretch()
        column.addWidget(self.chips)

        actions = QHBoxLayout()
        retry = QPushButton("Retry", objectName="action")
        retry.clicked.connect(lambda: self._run(self.original, None))
        copy = QPushButton("Copy", objectName="action")
        copy.clicked.connect(self._copy)
        replace = QPushButton("Replace", objectName="primary")
        replace.setToolTip("Ctrl+Enter")
        replace.clicked.connect(self._replace)
        for button in (retry, copy, replace):
            button.setCursor(Qt.PointingHandCursor)
        actions.addWidget(retry)
        actions.addStretch()
        actions.addWidget(copy)
        actions.addWidget(replace)
        column.addLayout(actions)
        self.result_page = page
        self._add_page(page)

    def _build_error(self):
        page = QWidget()
        column = QVBoxLayout(page)
        column.setContentsMargins(0, 0, 0, 0)
        self.error_label = QLabel(objectName="error")
        self.error_label.setWordWrap(True)
        self.error_label.setMaximumWidth(360)
        column.addWidget(self.error_label)
        actions = QHBoxLayout()
        self.settings_button = QPushButton("Open Settings", objectName="action")
        self.settings_button.clicked.connect(lambda: (self.close(), self.open_settings()))
        retry = QPushButton("Retry", objectName="primary")
        retry.clicked.connect(lambda: self._run(self.original, None))
        actions.addStretch()
        actions.addWidget(self.settings_button)
        actions.addWidget(retry)
        column.addLayout(actions)
        self.error_page = page
        self._add_page(page)

    # ----- flow -----

    def open_for(self, text, previous_clipboard, target_hwnd, anchor: QPoint):
        self.original = self.current = text
        self.previous_clipboard = previous_clipboard
        self.target_hwnd = target_hwnd
        self.anchor = anchor
        self.request_id += 1
        self.title.setText("WritingHelp")
        self.setStyleSheet(_popup_style())
        self._show_page(self.menu_page)
        self.show()
        self.raise_()
        self.activateWindow()
        winapi.focus_window(int(self.winId()))
        self.first_button.setFocus()

    def _add_page(self, page):
        page.hide()
        self.pages.append(page)
        self.page_layout.addWidget(page)

    def _show_page(self, page):
        self.current_page = page
        for widget in self.pages:
            widget.setVisible(widget is page)
        self.page_layout.activate()
        self.layout().activate()
        self.resize(self.layout().totalSizeHint())
        self._place()

    def _place(self):
        """Just below-right of the pointer, kept fully on the screen it's on."""
        screen = QGuiApplication.screenAt(self.anchor) or QGuiApplication.primaryScreen()
        area = screen.availableGeometry()
        x = min(max(self.anchor.x() + 8, area.left()), area.right() - self.width())
        y = self.anchor.y() + 12
        if y + self.height() > area.bottom():
            y = max(area.top(), self.anchor.y() - self.height() - 12)
        self.move(x, y)

    def _start(self, mode: Mode):
        if self.current_page is not self.menu_page:
            return
        self.mode = mode
        self.title.setText(mode.title)
        self._run(self.original, None)

    def _run(self, text, refinement):
        api_key = self.settings.api_key
        if not api_key:
            self._show_error(claude.ClaudeError("No Claude API key set yet. Add one in Settings.", True))
            return
        self.request_id += 1
        request_id = self.request_id
        self.loading_label.setText(self.mode.loading_label)
        self._show_page(self.loading_page)
        prompt = system_prompt_for(
            self.mode, self.settings.main_language, self.settings.second_language, refinement
        )
        model = self.settings.model

        def work():
            try:
                self._finished.emit(request_id, claude.send_message(api_key, model, prompt, text), None)
            except claude.ClaudeError as e:
                self._finished.emit(request_id, None, e)

        threading.Thread(target=work, daemon=True).start()

    def _on_finished(self, request_id, text, error):
        if request_id != self.request_id or not self.isVisible():
            return
        if error is not None:
            self._show_error(error)
            return
        self.current = text
        self.result_edit.setPlainText(text)
        _set_paragraph_directions(self.result_edit)
        self.chips.setVisible(self.mode.supports_refinement)
        self._show_page(self.result_page)
        self.result_edit.setFocus()

    def _show_error(self, error):
        self.error_label.setText(str(error))
        self.settings_button.setVisible(error.is_auth_error)
        self._show_page(self.error_page)

    def _replace(self):
        if self.current_page is not self.result_page:
            return
        text = self.result_edit.toPlainText()
        hwnd, previous = self.target_hwnd, self.previous_clipboard
        self.hide()
        threading.Thread(target=winapi.paste_into, args=(hwnd, text, previous), daemon=True).start()

    def _copy(self):
        winapi.set_clipboard_text(self.result_edit.toPlainText(), keep_out_of_history=False)
        self.close()

    # Clicking anywhere else dismisses it, like a menu.
    def event(self, event):
        if event.type() == QEvent.WindowDeactivate and self.isVisible():
            QTimer.singleShot(0, self.close)
        return super().event(event)

    def closeEvent(self, event):
        self.request_id += 1  # drop any reply still on its way
        super().closeEvent(event)


class SettingsWindow(QDialog):
    """API key, model (fetched live when the window opens), the two languages, and triggers."""

    _models_loaded = Signal(object)

    def __init__(self, settings, on_saved):
        super().__init__(None)
        self.settings = settings
        self.on_saved = on_saved
        self.models = list(FALLBACK_MODELS)
        self.setWindowTitle("WritingHelp Settings")
        self.setWindowIcon(app_icon())
        self.setMinimumWidth(560)
        self._models_loaded.connect(self._on_models_loaded)

        form = QFormLayout()
        form.setFieldGrowthPolicy(QFormLayout.ExpandingFieldsGrow)

        self.key_edit = QLineEdit()
        self.key_edit.setEchoMode(QLineEdit.Password)
        self.key_edit.setPlaceholderText("sk-ant-…")
        self.key_edit.editingFinished.connect(self._fetch_models)
        form.addRow("Claude API key", self.key_edit)

        self.model_combo = QComboBox()
        self.model_combo.currentIndexChanged.connect(self._update_model_note)
        form.addRow("Model", self.model_combo)
        self.model_note = QLabel()
        self.model_note.setWordWrap(True)
        self.model_note.setMinimumWidth(380)
        self.model_note.setStyleSheet("color: gray;")
        form.addRow("", self.model_note)

        self.main_combo = QComboBox()
        self.second_combo = QComboBox()
        for combo in (self.main_combo, self.second_combo):
            combo.addItems(LANGUAGES)
        self.main_combo.textActivated.connect(lambda v: self._language_picked(self.main_combo, v))
        self.second_combo.textActivated.connect(lambda v: self._language_picked(self.second_combo, v))
        form.addRow("Main language", self.main_combo)
        form.addRow("Second language", self.second_combo)
        languages_note = QLabel(
            "Fix Grammar and AI Rewrite write in your main language. Translate turns your main "
            "language into the second one, and anything else into your main language."
        )
        languages_note.setWordWrap(True)
        languages_note.setMinimumWidth(380)
        languages_note.setStyleSheet("color: gray;")
        form.addRow("", languages_note)

        self.hotkey_check = QCheckBox(f"{winapi.HOTKEY_LABEL} opens the menu for the selected text")
        self.click_check = QCheckBox("Ctrl + right-click opens the menu for the selected text")
        self.startup_check = QCheckBox("Start WritingHelp with Windows")
        for check in (self.hotkey_check, self.click_check, self.startup_check):
            form.addRow("", check)

        save = QPushButton("Save")
        save.setDefault(True)
        save.clicked.connect(self._save)
        cancel = QPushButton("Cancel")
        cancel.clicked.connect(self.reject)
        buttons = QHBoxLayout()
        buttons.addStretch()
        buttons.addWidget(cancel)
        buttons.addWidget(save)

        layout = QVBoxLayout(self)
        layout.addLayout(form)
        layout.addSpacing(8)
        layout.addLayout(buttons)

    def open_window(self):
        self.key_edit.setText(self.settings.api_key)
        self.main_combo.setCurrentText(self.settings.main_language)
        self.second_combo.setCurrentText(self.settings.second_language)
        self._last_languages = {True: self.main_combo.currentText(), False: self.second_combo.currentText()}
        self.hotkey_check.setChecked(self.settings.hotkey_enabled)
        self.click_check.setChecked(self.settings.ctrl_click_enabled)
        self.startup_check.setChecked(self.settings.start_with_windows)
        self._fill_models(self.settings.model, live=False)
        self._fetch_models()
        self.show()
        self.raise_()
        self.activateWindow()
        winapi.focus_window(int(self.winId()))

    def _language_picked(self, combo, value):
        """Picking the language the other box already has swaps the two, like on Android."""
        other = self.second_combo if combo is self.main_combo else self.main_combo
        if other.currentText() == value:
            other.setCurrentText(self._last_languages[combo is self.main_combo])
        self._last_languages = {True: self.main_combo.currentText(), False: self.second_combo.currentText()}

    def _fetch_models(self):
        key = self.key_edit.text().strip()
        if not key:
            return
        self.model_note.setText("Fetching the latest models…")
        threading.Thread(target=lambda: self._models_loaded.emit(claude.list_models(key)), daemon=True).start()

    def _on_models_loaded(self, models):
        selected = self.model_combo.currentData() or self.settings.model
        if models:
            self.models = models
            if selected not in {m.api_id for m in models}:
                selected = default_model_from(models)
                self.settings.model = selected  # the saved model is gone; switch like Android does
        self._fill_models(selected, live=bool(models))

    def _fill_models(self, selected, live):
        self.model_combo.blockSignals(True)
        self.model_combo.clear()
        options = self.models
        if selected not in {m.api_id for m in options}:
            options = options + [ModelOption(selected, selected)]
        for option in options:
            self.model_combo.addItem(option.display_name, option.api_id)
        self.model_combo.setCurrentIndex(max(0, self.model_combo.findData(selected)))
        self.model_combo.blockSignals(False)
        self._live = live
        self._update_model_note()

    def _update_model_note(self):
        api_id = self.model_combo.currentData() or ""
        source = "Latest models from Anthropic." if getattr(self, "_live", False) else \
            "Built-in list (add an API key to fetch the latest)."
        self.model_note.setText(f"{description_for(api_id)}\n{source}")

    def _save(self):
        self.settings.api_key = self.key_edit.text()
        self.settings.model = self.model_combo.currentData() or self.settings.model
        main, second = self.main_combo.currentText(), self.second_combo.currentText()
        if main == second:
            second = next(l for l in LANGUAGES if l != main)
        self.settings.main_language = main
        self.settings.second_language = second
        self.settings.hotkey_enabled = self.hotkey_check.isChecked()
        self.settings.ctrl_click_enabled = self.click_check.isChecked()
        try:
            self.settings.start_with_windows = self.startup_check.isChecked()
        except OSError:
            pass
        self.on_saved()
        self.accept()


class Tray(QObject):
    def __init__(self, open_settings, toggle_paused, quit_app):
        super().__init__()
        self.icon = QSystemTrayIcon(app_icon())
        self.icon.setToolTip(
            f"WritingHelp: select text, then {winapi.HOTKEY_LABEL} or Ctrl + right-click"
        )
        menu = QMenu()
        settings_action = QAction("Settings…", menu)
        settings_action.triggered.connect(open_settings)
        self.pause_action = QAction("Pause", menu, checkable=True)
        self.pause_action.toggled.connect(toggle_paused)
        quit_action = QAction("Quit WritingHelp", menu)
        quit_action.triggered.connect(quit_app)
        menu.addAction(settings_action)
        menu.addAction(self.pause_action)
        menu.addSeparator()
        menu.addAction(quit_action)
        self.menu = menu
        self.icon.setContextMenu(menu)
        self.icon.activated.connect(
            lambda reason: open_settings() if reason in (
                QSystemTrayIcon.Trigger, QSystemTrayIcon.DoubleClick) else None
        )
        self.icon.show()

    def notify(self, title, message):
        self.icon.showMessage(title, message, app_icon(), 4000)
