"""Qt UI: the popup that appears at the mouse pointer, the Settings window and the tray icon."""

import threading
import unicodedata

from PySide6.QtCore import QEvent, QObject, QPoint, QRectF, Qt, QTimer, Signal
from PySide6.QtGui import (
    QAction, QColor, QFont, QGuiApplication, QIcon, QKeySequence, QPainter, QPainterPath, QPen,
    QPixmap,
    QShortcut, QTextCursor,
)
from PySide6.QtWidgets import (
    QCheckBox, QComboBox, QDialog, QFormLayout, QFrame, QHBoxLayout, QLabel, QLineEdit, QMenu,
    QLayout, QPushButton, QTextEdit, QSystemTrayIcon, QVBoxLayout, QWidget,
)

from . import claude, winapi
from .prompts import (
    FALLBACK_MODELS, LANGUAGES, REFINEMENT_CHIPS, Feature, Mode, ModelOption, default_model_from,
    description_for, explain_input, explain_prompt, parse_bullets, system_prompt_for,
)


def is_dark() -> bool:
    return QGuiApplication.styleHints().colorScheme() == Qt.ColorScheme.Dark


BLUE = "#185FA5"


def app_icon(size: int = 64) -> QIcon:
    """The Android launcher icon: a pencil writing a wavy line on blue. Drawn with QPainter from the
    same vector coordinates, so there's no image file to ship."""
    pixmap = QPixmap(size, size)
    pixmap.fill(Qt.transparent)
    painter = QPainter(pixmap)
    painter.setRenderHint(QPainter.Antialiasing)
    path = QPainterPath()
    path.addRoundedRect(QRectF(0, 0, size, size), size * 0.22, size * 0.22)
    painter.fillPath(path, QColor(BLUE))

    # Android draws this in a 108-unit canvas with a 1.3x group transform and lets the launcher
    # mask the edges; here the middle 84 units fill the square so the pencil reads at tray size.
    painter.scale(size / 84, size / 84)
    painter.translate(-12 + 18.9, -12 + 22.15)
    painter.scale(1.3, 1.3)
    painter.setPen(Qt.NoPen)
    for color, points in (
        ("#FFFFFF", [(32, 9), (41, 18), (22, 37), (13, 28)]),
        ("#FAC775", [(13, 28), (22, 37), (10, 40)]),
        ("#B5D4F4", [(32, 9), (41, 18), (44, 15), (35, 6)]),
    ):
        shape = QPainterPath()
        shape.moveTo(*points[0])
        for point in points[1:]:
            shape.lineTo(*point)
        shape.closeSubpath()
        painter.fillPath(shape, QColor(color))
    wave = QPainterPath()
    wave.moveTo(24, 43)
    wave.quadTo(27, 40, 30, 43)
    wave.quadTo(33, 46, 36, 43)
    wave.quadTo(39, 40, 42, 43)
    pen = QPen(QColor("#FFFFFF"), 2.2)
    pen.setCapStyle(Qt.RoundCap)
    painter.setPen(pen)
    painter.setBrush(Qt.NoBrush)
    painter.drawPath(wave)
    painter.end()
    return QIcon(pixmap)


def _popup_style() -> str:
    if is_dark():
        bg, fg, sub, border, btn, btn_hover, edit = (
            "#25282C", "#E3E6EA", "#B9C0C8", "#454B52", "#31363C", "#3C434B", "#1B1D20")
        primary, on_primary = "#9DC4EE", "#0B2A4A"
    else:
        bg, fg, sub, border, btn, btn_hover, edit = (
            "#FFFFFF", "#17202A", "#4A5562", "#C5D0DC", "#EEF4FA", "#DCE9F6", "#FAFCFE")
        primary, on_primary = BLUE, "#FFFFFF"
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
        QPushButton#chip:checked {{ background: {btn_hover}; border-color: {primary}; }}
        QPushButton#flat {{ background: transparent; padding: 2px 6px; text-align: center; }}
        QPushButton#action {{ text-align: center; }}
        QTextEdit, QLineEdit {{ background: {edit}; color: {fg}; border: 1px solid {border};
                          border-radius: 8px; padding: 4px; }}
        QTextEdit#explain {{ background: {btn}; border: none; }}
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
    """Menu (or the Write for me box) -> loading -> result (or error), anchored at the mouse pointer.

    Result actions mirror the Android result sheet: Replace pastes into the app the text came
    from, Copy puts it on the clipboard, AI Rewrite and Write for me offer the tone chips, and
    Why? explains a Fix Grammar or AI Rewrite result."""

    _finished = Signal(int, object, object)  # request id, text, ClaudeError
    _explained = Signal(int, object, object, object)  # request id, cache key, text, ClaudeError

    MENU_KEYS = {Mode.FIX_GRAMMAR: "G", Mode.REWRITE: "R", Mode.TRANSLATE: "T", Mode.WRITE: "W"}

    def __init__(self, settings, open_settings):
        super().__init__(None, Qt.Tool | Qt.FramelessWindowHint | Qt.WindowStaysOnTopHint)
        self.setAttribute(Qt.WA_TranslucentBackground)
        self.settings = settings
        self.open_settings = open_settings
        self.target_hwnd = None
        self.original = ""  # the selected text, or the Write for me request
        self.has_selection = False
        self.previous_clipboard = None
        self.current = ""
        self.mode = None
        self.request_id = 0
        self.explain_id = 0
        self.explanations = {}  # (original, revised) -> explanation text, so Why? is asked once
        self.anchor = QPoint()
        self._finished.connect(self._on_finished)
        self._explained.connect(self._on_explained)

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
        self._build_write()
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
        self.menu_buttons = {}
        for mode, key in self.MENU_KEYS.items():
            button = QPushButton(f"{mode.title}    ({key})")
            button.setCursor(Qt.PointingHandCursor)
            button.setMinimumWidth(200)
            button.clicked.connect(lambda _=False, m=mode: self._pick(m))
            column.addWidget(button)
            QShortcut(QKeySequence(key), page, activated=lambda m=mode: self._pick(m),
                      context=Qt.WidgetWithChildrenShortcut)
            self.menu_buttons[mode] = button
        self.menu_page = page
        self._add_page(page)

    def _build_write(self):
        page = QWidget()
        column = QVBoxLayout(page)
        column.setContentsMargins(0, 0, 0, 0)
        column.setSpacing(8)
        self.write_edit = QLineEdit()
        self.write_edit.setMinimumWidth(380)
        self.write_edit.setPlaceholderText("What should I write? e.g. an email to my friend about Saturday")
        self.write_edit.returnPressed.connect(self._submit_write)
        column.addWidget(self.write_edit)
        self.write_note = QLabel(objectName="sub")
        column.addWidget(self.write_note)
        actions = QHBoxLayout()
        write = QPushButton("Write", objectName="primary")
        write.setCursor(Qt.PointingHandCursor)
        write.clicked.connect(self._submit_write)
        actions.addStretch()
        actions.addWidget(write)
        column.addLayout(actions)
        self.write_page = page
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

        self.explain_box = QTextEdit(objectName="explain")
        self.explain_box.setReadOnly(True)
        self.explain_box.setFont(QFont("Segoe UI", 9))
        self.explain_box.setMinimumWidth(380)
        self.explain_box.hide()
        column.addWidget(self.explain_box)

        actions = QHBoxLayout()
        retry = QPushButton("Retry", objectName="action")
        retry.clicked.connect(lambda: self._run(self.original, None))
        self.why_button = QPushButton("Why?", objectName="action")
        self.why_button.setToolTip("Short notes on what changed and why")
        self.why_button.clicked.connect(self._toggle_explanation)
        copy = QPushButton("Copy", objectName="action")
        copy.clicked.connect(self._copy)
        self.replace_button = QPushButton("Replace", objectName="primary")
        self.replace_button.setToolTip("Ctrl+Enter")
        self.replace_button.clicked.connect(self._replace)
        for button in (retry, self.why_button, copy, self.replace_button):
            button.setCursor(Qt.PointingHandCursor)
        actions.addWidget(retry)
        actions.addWidget(self.why_button)
        actions.addStretch()
        actions.addWidget(copy)
        actions.addWidget(self.replace_button)
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

    def enabled_modes(self, has_selection: bool):
        """Menu entries in order: the actions switched on in Settings. Write for me works without a
        selection too; the other three need one."""
        return [
            mode for mode in self.MENU_KEYS
            if self.settings.feature_enabled(Feature.for_mode(mode))
            and (has_selection or mode is Mode.WRITE)
        ]

    def open_for(self, text, previous_clipboard, target_hwnd, anchor: QPoint):
        """Opens the menu for the selected text, or straight into Write for me when text is empty.
        The caller checks enabled_modes() first, so there's always something to show."""
        self.has_selection = bool(text and text.strip())
        self.original = self.current = text if self.has_selection else ""
        self.previous_clipboard = previous_clipboard
        self.target_hwnd = target_hwnd
        self.anchor = anchor
        self.request_id += 1
        self.mode = None
        self.title.setText("WritingHelp")
        self.setStyleSheet(_popup_style())
        modes = self.enabled_modes(self.has_selection)
        self._show_window()
        if self.has_selection:
            for mode, button in self.menu_buttons.items():
                button.setVisible(mode in modes)
            self._show_page(self.menu_page)
            self.menu_buttons[modes[0]].setFocus()
        else:
            self._open_write()

    def _show_window(self):
        self.show()
        self.raise_()
        self.activateWindow()
        winapi.focus_window(int(self.winId()))

    def _open_write(self):
        self.mode = Mode.WRITE
        self.title.setText(Mode.WRITE.title)
        self.write_note.setText(
            "The result replaces the selected text." if self.has_selection
            else "The result goes in at the cursor, or Copy it.")
        self.write_edit.clear()
        self._show_page(self.write_page)
        self.write_edit.setFocus()

    def _add_page(self, page):
        page.hide()
        self.pages.append(page)
        self.page_layout.addWidget(page)

    def _show_page(self, page):
        self.current_page = page
        for widget in self.pages:
            widget.setVisible(widget is page)
        self._fit()

    def _fit(self):
        if self.current_page is not None:
            self.current_page.layout().invalidate()  # pick up size changes inside the page
            self.current_page.layout().activate()
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

    def _pick(self, mode: Mode):
        if self.current_page is not self.menu_page or not self.menu_buttons[mode].isVisible():
            return
        if mode is Mode.WRITE:
            self._open_write()
            return
        self.mode = mode
        self.title.setText(mode.title)
        self._run(self.original, None)

    def _submit_write(self):
        request = self.write_edit.text().strip()
        if self.current_page is not self.write_page or not request:
            return
        self.original = request
        self._run(request, None)

    def _run(self, text, refinement):
        api_key = self.settings.api_key
        if not api_key:
            self._show_error(claude.ClaudeError("No Claude API key set yet. Add one in Settings.", True))
            return
        self.request_id += 1
        request_id = self.request_id
        self.loading_label.setText(self.mode.loading_label)
        self._show_page(self.loading_page)
        # A tone chip reworks the current result, so even after Write for me it's a rewrite.
        prompt_mode = Mode.REWRITE if refinement else self.mode
        prompt = system_prompt_for(
            prompt_mode, self.settings.main_language, self.settings.second_language, refinement
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
        self.why_button.setVisible(
            self.mode in (Mode.FIX_GRAMMAR, Mode.REWRITE)
            and self.settings.feature_enabled(Feature.EXPLAIN)
        )
        self.replace_button.setText("Replace" if self.has_selection else "Insert")
        self.explain_id += 1
        self.explain_box.hide()
        self._show_page(self.result_page)
        self.result_edit.setFocus()

    def _show_error(self, error):
        self.error_label.setText(str(error))
        self.settings_button.setVisible(error.is_auth_error)
        self._show_page(self.error_page)

    # ----- Why? -----

    def _toggle_explanation(self):
        if self.explain_box.isVisible():
            self.explain_id += 1
            self.explain_box.hide()
            self._fit()
            return
        key = (self.original, self.result_edit.toPlainText())
        if key in self.explanations:
            self._show_explanation(self.explanations[key])
            return
        api_key = self.settings.api_key
        if not api_key:
            self._show_explanation("No Claude API key set yet. Add one in Settings.")
            return
        self.explain_id += 1
        explain_id = self.explain_id
        self._show_explanation("Explaining the changes…")
        prompt = explain_prompt(self.settings.main_language)
        model = self.settings.model

        def work():
            try:
                reply = claude.send_message(api_key, model, prompt, explain_input(*key))
                self._explained.emit(explain_id, key, reply, None)
            except claude.ClaudeError as e:
                self._explained.emit(explain_id, key, None, e)

        threading.Thread(target=work, daemon=True).start()

    def _on_explained(self, explain_id, key, reply, error):
        if explain_id != self.explain_id or not self.isVisible():
            return
        if error is not None:
            self._show_explanation(f"Couldn't explain: {error}")
            return
        bullets = parse_bullets(reply)
        text = "\n".join(f"• {b}" for b in bullets) or "No explanation came back. Try again."
        if bullets:
            self.explanations[key] = text
        self._show_explanation(text)

    def _show_explanation(self, text):
        box = self.explain_box
        box.setPlainText(text)
        _set_paragraph_directions(box)
        box.document().setTextWidth(max(box.width(), box.minimumWidth()) - 12)
        box.setFixedHeight(min(int(box.document().size().height()) + 14, 240))
        box.show()
        self._fit()

    # ----- output -----

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
        self.explain_id += 1
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

        actions_title = QLabel("Actions")
        actions_title.setStyleSheet("font-weight: 600; margin-top: 8px;")
        form.addRow(actions_title)
        self.feature_checks = {}
        for feature in Feature:
            check = QCheckBox(feature.title)
            check.setToolTip(feature.description)
            note = QLabel(feature.description)
            note.setWordWrap(True)
            note.setMinimumWidth(380)
            note.setStyleSheet("color: gray; margin-left: 22px;")
            form.addRow("", check)
            form.addRow("", note)
            self.feature_checks[feature] = check

        triggers_title = QLabel("Shortcuts and startup")
        triggers_title.setStyleSheet("font-weight: 600; margin-top: 8px;")
        form.addRow(triggers_title)
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
        for feature, check in self.feature_checks.items():
            check.setChecked(self.settings.feature_enabled(feature))
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
        for feature, check in self.feature_checks.items():
            self.settings.set_feature_enabled(feature, check.isChecked())
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
