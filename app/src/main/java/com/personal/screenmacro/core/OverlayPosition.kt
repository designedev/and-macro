package com.personal.screenmacro.core

data class OverlayPosition(val x: Int, val y: Int) {
    fun constrained(left: Int, top: Int, right: Int, bottom: Int, width: Int, height: Int) = OverlayPosition(
        x.coerceIn(left, maxOf(left, right - width)), y.coerceIn(top, maxOf(top, bottom - height)))
}
