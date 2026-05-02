package com.orion.inference

import android.graphics.Rect
import android.media.Image

data class CycleContext(
    val image: Image,
    val nodes: List<Pair<String, Rect>>,
    val goal: String,
    val screenW: Int,
    val screenH: Int,
    val appPackage: String,
    val retryContext: String,
    val previousAction: String,
    val frameNum: Int,
)
