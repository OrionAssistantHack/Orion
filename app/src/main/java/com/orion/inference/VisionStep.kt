package com.orion.inference

import android.graphics.Bitmap
import android.media.Image
import android.util.Log

private const val TAG = "Orion.VisionStep"

class VisionStep(private val lm: InferenceEngine) : InferenceStep {

    override val name = "Vision"

    override suspend fun run(context: CycleContext): StepResult {
        val bitmap = decodeImage(context.image)
        Log.i(TAG, "Frame #${context.frameNum} running vision inference (${bitmap.width}x${bitmap.height})")
        return try {
            val (perception, plan) = lm.perceiveAndPlan(
                bitmap = bitmap,
                goal = context.goal,
                nodes = context.nodes,
                screenWidth = context.screenW,
                screenHeight = context.screenH,
                appPackage = context.appPackage,
                retryContext = context.retryContext,
                previousAction = context.previousAction,
            )
            StepResult.Resolved(perception, plan)
        } finally {
            bitmap.recycle()
        }
    }

    private fun decodeImage(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val rowPadding = rowStride - pixelStride * image.width
        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        return bitmap
    }
}
