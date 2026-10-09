package com.pixeleyes;

import android.view.ActionMode;
import android.view.KeyEvent;
import android.view.KeyboardShortcutGroup;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SearchEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import java.util.List;

/**
 * Обёртка над Window.Callback окна приложения: видит каждое касание экрана раньше
 * всех и ничего у него не забирает, всё отдаёт исходному обработчику. Так глаза
 * смотрят на палец, где бы он ни был, без единого хука.
 */
final class CallbackWrapper implements Window.Callback {

    interface Sink {
        void onWindowTouch(MotionEvent e);
    }

    final Window.Callback base;
    volatile Sink sink;

    CallbackWrapper(Window.Callback base, Sink sink) {
        this.base = base;
        this.sink = sink;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        Sink s = sink;
        if (s != null) {
            try {
                s.onWindowTouch(event);
            } catch (Throwable ignored) {
                // глаза не должны ломать касания
            }
        }
        return base.dispatchTouchEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        return base.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchKeyShortcutEvent(KeyEvent event) {
        return base.dispatchKeyShortcutEvent(event);
    }

    @Override
    public boolean dispatchTrackballEvent(MotionEvent event) {
        return base.dispatchTrackballEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        return base.dispatchGenericMotionEvent(event);
    }

    @Override
    public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
        return base.dispatchPopulateAccessibilityEvent(event);
    }

    @Override
    public View onCreatePanelView(int featureId) {
        return base.onCreatePanelView(featureId);
    }

    @Override
    public boolean onCreatePanelMenu(int featureId, Menu menu) {
        return base.onCreatePanelMenu(featureId, menu);
    }

    @Override
    public boolean onPreparePanel(int featureId, View view, Menu menu) {
        return base.onPreparePanel(featureId, view, menu);
    }

    @Override
    public boolean onMenuOpened(int featureId, Menu menu) {
        return base.onMenuOpened(featureId, menu);
    }

    @Override
    public boolean onMenuItemSelected(int featureId, MenuItem item) {
        return base.onMenuItemSelected(featureId, item);
    }

    @Override
    public void onWindowAttributesChanged(WindowManager.LayoutParams attrs) {
        base.onWindowAttributesChanged(attrs);
    }

    @Override
    public void onContentChanged() {
        base.onContentChanged();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        base.onWindowFocusChanged(hasFocus);
    }

    @Override
    public void onAttachedToWindow() {
        base.onAttachedToWindow();
    }

    @Override
    public void onDetachedFromWindow() {
        base.onDetachedFromWindow();
    }

    @Override
    public void onPanelClosed(int featureId, Menu menu) {
        base.onPanelClosed(featureId, menu);
    }

    @Override
    public boolean onSearchRequested() {
        return base.onSearchRequested();
    }

    @Override
    public boolean onSearchRequested(SearchEvent searchEvent) {
        return base.onSearchRequested(searchEvent);
    }

    @Override
    public ActionMode onWindowStartingActionMode(ActionMode.Callback callback) {
        return base.onWindowStartingActionMode(callback);
    }

    @Override
    public ActionMode onWindowStartingActionMode(ActionMode.Callback callback, int type) {
        return base.onWindowStartingActionMode(callback, type);
    }

    @Override
    public void onActionModeStarted(ActionMode mode) {
        base.onActionModeStarted(mode);
    }

    @Override
    public void onActionModeFinished(ActionMode mode) {
        base.onActionModeFinished(mode);
    }

    @Override
    public void onProvideKeyboardShortcuts(List<KeyboardShortcutGroup> data, Menu menu, int deviceId) {
        base.onProvideKeyboardShortcuts(data, menu, deviceId);
    }

    @Override
    public void onPointerCaptureChanged(boolean hasCapture) {
        base.onPointerCaptureChanged(hasCapture);
    }
}
