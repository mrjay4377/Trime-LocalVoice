/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.window

import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import androidx.transition.Transition
import androidx.transition.TransitionManager
import androidx.transition.TransitionSet
import com.osfans.trime.R
import com.osfans.trime.ime.broadcast.InputBroadcaster
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import timber.log.Timber

class BoardWindowManager(override val di: DI) : DIAware {
    private val context by instance<ContextThemeWrapper>()
    private val broadcaster by instance<InputBroadcaster>()

    private val cachedResidentWindows = mutableMapOf<ResidentWindow.Key, Pair<BoardWindow, View?>>()

    private var currentWindow: BoardWindow? = null
    private var currentView: View? = null

    /** 当前显示的窗口。展开候选窗打开前用它记住「基底窗口」，收起时回到它（手写面板或键盘）。 */
    val topWindow: BoardWindow? get() = currentWindow

    private fun prepareAnimation(
        exitAnimation: Transition?,
        enterAnimation: Transition?,
        remove: View,
        add: View,
    ) {
        enterAnimation?.addTarget(add)
        exitAnimation?.addTarget(remove)
        TransitionManager.beginDelayedTransition(
            view,
            TransitionSet().apply {
                enterAnimation?.let { addTransition(it) }
                exitAnimation?.let { addTransition(it) }
                duration = 100
            },
        )
    }

    fun <R> cacheResidentWindow(
        window: R,
        createView: Boolean = false,
    ) where R : BoardWindow, R : ResidentWindow {
        if (window.key in cachedResidentWindows) {
            if (cachedResidentWindows[window.key]!!.first === window) {
                Timber.d("Skip adding resident window $window")
            } else {
                throw IllegalStateException("${window.key} is already occupied")
            }
        }
        broadcaster.addReceiver(window)
        val view = if (createView) window.onCreateView() else null
        cachedResidentWindows[window.key] = window to view
    }

    fun attachWindow(windowKey: ResidentWindow.Key) {
        cachedResidentWindows[windowKey]?.let { (window, _) ->
            attachWindow(window)
        } ?: throw IllegalStateException("$windowKey is not a known resident window key")
    }

    fun attachWindow(window: BoardWindow) {
        if (window === currentWindow) {
            Timber.d("Skip attaching $window")
            // 原来这里漏了 return，重复 attach 同一窗口会把视图 remove 再 add 一遍，
            // 并触发一次无意义的 onDetached/onAttached（手写窗口的协程作用域会被 cancel）。
            return
        }
        val newView =
            if (window is ResidentWindow) {
                cachedResidentWindows[window.key]?.second ?: window
                    .onCreateView()
                    .also { cachedResidentWindows[window.key] = window to it }
            } else {
                broadcaster.addReceiver(window)
                window.onCreateView()
            }
        if (currentWindow != null) {
            val oldWindow = currentWindow!!
            val oldView = currentView!!
            prepareAnimation(
                oldWindow.exitAnimation(window),
                window.enterAnimation(oldWindow),
                oldView,
                newView,
            )
            oldWindow.onDetached()
            view.removeView(oldView)
            broadcaster.onWindowDetached(oldWindow)
            Timber.d("Detach $oldWindow")
            if (oldWindow !is ResidentWindow) {
                broadcaster.removeReceiver(oldWindow)
            }
        }
        if (window is ResidentWindow) {
            window.beforeAttached()
        }
        view.apply { add(newView, lParams(matchParent, matchParent)) }
        currentView = newView
        Timber.d("Attach $window")
        window.onAttached()
        currentWindow = window
        broadcaster.onWindowAttached(window)
    }

    /** Restyles the window currently on screen after a scheme switch. */
    fun refreshColors() {
        currentWindow?.refreshColors()
    }

    val view: FrameLayout by lazy { context.frameLayout(R.id.input_window) }

    fun isAttached(window: BoardWindow) = currentWindow === window

    /** 供上层在「输入连接重启」时判断当前是不是某个临时面板，避免把它无端切回键盘。 */
    fun currentWindowIs(clazz: Class<*>): Boolean = clazz.isInstance(currentWindow)
}
