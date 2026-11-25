/*
 * Copyright 2022 The TensorFlow Authors. All Rights Reserved.
 * Modifications Copyright 2025 Jeff Breidenbach.
 *
 * This file was modified from the MediaPipe HandLandmarker example
 * (https://github.com/google-ai-edge/mediapipe-samples).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *             http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jab.laserbird

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlin.math.max
import kotlin.math.min
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.components.containers.Landmark
import android.media.MediaPlayer
import android.util.Log
import android.graphics.BlurMaskFilter
import android.graphics.LinearGradient
import android.graphics.Shader
import kotlin.random.Random
import kotlin.math.pow


class OverlayView(context: Context?, attrs: AttributeSet?) :
    View(context, attrs) {

    private var results: HandLandmarkerResult? = null
    private var scaleFactor: Float = 1f
    private var imageWidth: Int = 1
    private var imageHeight: Int = 1

    fun clear() {
        results = null
        invalidate()
    }

    fun setResults(
        handLandmarkerResults: HandLandmarkerResult,
        imageHeight: Int,
        imageWidth: Int,
        runningMode: RunningMode = RunningMode.IMAGE
    ) {
        results = handLandmarkerResults

        this.imageHeight = imageHeight
        this.imageWidth = imageWidth

        scaleFactor = when (runningMode) {
            RunningMode.IMAGE,
            RunningMode.VIDEO -> {
                min(width * 1f / imageWidth, height * 1f / imageHeight)
            }
            RunningMode.LIVE_STREAM -> {
                // PreviewView is in FILL_START mode. So we need to scale up the
                // landmarks to match with the size that the captured images will be
                // displayed.
                max(width * 1f / imageWidth, height * 1f / imageHeight)
            }
        }
        invalidate()
    }

    private fun isFingerExtended(landmarks: List<Landmark>): Int? {
        val fingerIndices = listOf(8, 12, 16, 20) //  Index, Middle, Ring, Pinky
        var maxExtension = 0.04f  // threshold in cm
        var extendedFinger: Int? = null
        for (fingerIndex in fingerIndices) {
            val fingerTip = landmarks[fingerIndex]
            val fingerBase = landmarks[fingerIndex - 3] // Base of the finger
            val wrist = landmarks[0]

            // Calculate the 3D distance between the fingertip and the wrist
            val distanceTipToWrist = distance3D(fingerTip, wrist)
            val distanceBaseToWrist = distance3D(fingerBase, wrist)
            var extension = distanceTipToWrist - distanceBaseToWrist

            if (extension > maxExtension) {
                maxExtension = extension
                extendedFinger = fingerIndex
            }
        }
        return extendedFinger
    }

    private fun distance3D(p1: Landmark, p2: Landmark): Float {
        return kotlin.math.sqrt(
            (p1.x() - p2.x()).toDouble().pow(2) +
                    (p1.y() - p2.y()).toDouble().pow(2) +
                    (p1.z() - p2.z()).toDouble().pow(2)
        ).toFloat()
    }

    private fun calculateFingerAngle(landmarks: List<NormalizedLandmark>, fingerIndex: Int): Float {
        val tip = landmarks[fingerIndex]
        val base = landmarks[fingerIndex - 3]
        return 90 + Math.toDegrees(
            kotlin.math.atan2(
                (tip.y() - base.y()).toDouble(),
                (tip.x() - base.x()).toDouble()
            )
        ).toFloat()
    }

    private fun distance(p1: NormalizedLandmark, p2: NormalizedLandmark): Float {
        return kotlin.math.sqrt(
            ((p1.x() - p2.x()) * (p1.x() - p2.x()) +
                    (p1.y() - p2.y()) * (p1.y() - p2.y())).toDouble()
        ).toFloat()
    }

    private val previousPositions = mutableMapOf<Int, NormalizedLandmark>()

    private fun shouldPlaySound(handId: Int, currentPosition: NormalizedLandmark): Boolean {
        previousPositions[handId]?.let { prev ->
            val distanceMoved = distance(prev, currentPosition)

            if (distanceMoved > movementThreshold) {
                Log.d("Distance Moved", "Hand $handId moved: $distanceMoved")
                previousPositions[handId] = currentPosition // Update the previous position
                return true
            }
        }
        previousPositions[handId] = currentPosition // Initialize the previous position
        return false
    }

    private var previousPosition: NormalizedLandmark? = null
    private val movementThreshold = 0.02f // Adjust this value as needed


// =======================

    private val soundMap = mapOf(
        "laser_1" to R.raw.laser_1,
        "laser_clash" to R.raw.laser_clash,
        "laser_move" to R.raw.laser_move
    )

    private val mediaPlayers = mutableMapOf<String, MediaPlayer>()

    init {
        preloadSounds() // Preload sounds during initialization
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        mediaPlayers.values.forEach { it.release() }
        mediaPlayers.clear()
        handMediaPlayers.values.forEach { it.release() }
        handMediaPlayers.clear()
    }

    private fun rotatePoint(
        x: Float,
        y: Float,
        cx: Float,
        cy: Float,
        angle: Float
    ): Pair<Float, Float> {
        val radians = Math.toRadians(angle.toDouble())
        val cos = kotlin.math.cos(radians)
        val sin = kotlin.math.sin(radians)
        val dx = x - cx
        val dy = y - cy
        val newX = cx + (dx * cos - dy * sin).toFloat()
        val newY = cy + (dx * sin + dy * cos).toFloat()
        return Pair(newX, newY)
    }

    private fun areBladesTouching(
        blade1: List<Pair<Float, Float>>,
        blade2: List<Pair<Float, Float>>
    ): Boolean {
        return checkPolygonsIntersect(blade1, blade2)
    }

    private fun checkPolygonsIntersect(
        polygon1: List<Pair<Float, Float>>,
        polygon2: List<Pair<Float, Float>>
    ): Boolean {
        val polygons = listOf(polygon1, polygon2)
        for (polygon in polygons) {
            for (i in polygon.indices) {
                val p1 = polygon[i]
                val p2 = polygon[(i + 1) % polygon.size]
                val normal = Pair(-(p2.second - p1.second), p2.first - p1.first)

                val projection1 = projectPolygon(polygon1, normal)
                val projection2 = projectPolygon(polygon2, normal)

                if (projection1.second < projection2.first || projection2.second < projection1.first) {
                    return false // No overlap
                }
            }
        }
        return true // Overlap exists
    }

    private fun projectPolygon(
        polygon: List<Pair<Float, Float>>,
        axis: Pair<Float, Float>
    ): Pair<Float, Float> {
        var min = Float.MAX_VALUE
        var max = Float.MIN_VALUE
        for (point in polygon) {
            val projection = point.first * axis.first + point.second * axis.second
            if (projection < min) min = projection
            if (projection > max) max = projection
        }
        return Pair(min, max)
    }


    private var movementMediaPlayer: MediaPlayer? = null
    private var clashMediaPlayer: MediaPlayer? = null

    private fun preloadSounds() {
        soundMap.forEach { (key, resId) ->
            try {
                mediaPlayers[key] = MediaPlayer.create(context, resId)
            } catch (e: Exception) {
                Log.e("MediaPlayerError", "Failed to preload sound: $key", e)
            }
        }
        movementMediaPlayer = MediaPlayer.create(context, R.raw.laser_move)
        clashMediaPlayer = MediaPlayer.create(context, R.raw.laser_clash)
    }

    private fun playClashSound() {
        clashMediaPlayer?.let { player ->
            try {
                if (!player.isPlaying) {
                    movementMediaPlayer?.let { movementPlayer ->
                        if (movementPlayer.isPlaying) {
                            movementPlayer.stop() // Stop movement sound
                            movementPlayer.prepare() // Prepare it for future use
                        }
                    }
                    player.seekTo(0)
                    player.start()
                } else {
                }
            } catch (e: Exception) {
                Log.e("MediaPlayerError", "Error playing clash sound", e)
            }
        }
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        results?.let { handLandmarkerResult ->
            val landmarksList = handLandmarkerResult.landmarks()
            val worldLandmarksList = handLandmarkerResult.worldLandmarks()
            val bladePolygons = mutableListOf<List<Pair<Float, Float>>>()
            if (landmarksList.size == 2) {
                for (handId in landmarksList.indices) {
                    val landmark = landmarksList[handId]
                    val worldLandmark = worldLandmarksList[handId]
                    val extendedFinger = isFingerExtended(worldLandmark)
                    if (extendedFinger != null) {
                        val fingerTip = landmark[extendedFinger]
                        if (shouldPlaySound(handId, fingerTip)) {
                            playMovementSound(handId)
                        }
                        val bladePolygon = drawLaser(
                            canvas,
                            fingerTip,
                            calculateFingerAngle(landmark, extendedFinger),
                            handId
                        )
                        bladePolygons.add(bladePolygon)
                    }
                }

                // Check if blades are touching
                if (bladePolygons.size == 2 && areBladesTouching(
                        bladePolygons[0],
                        bladePolygons[1]
                    )
                ) {
                    sparkPaint.maskFilter = BlurMaskFilter(50f, BlurMaskFilter.Blur.NORMAL)
                    sparkPaint.color = Color.rgb(128, 0, 128)
                    playClashSound()
                    return
                } else {
                    sparkPaint.maskFilter = BlurMaskFilter(100f, BlurMaskFilter.Blur.NORMAL)
                    sparkPaint.color = Color.WHITE
                }
            } else {
                // Handle zero or single hand case
                for (handId in landmarksList.indices) {
                    val landmark = landmarksList[handId]
                    val worldLandmark = worldLandmarksList[handId]
                    val extendedFinger = isFingerExtended(worldLandmark)
                    if (extendedFinger != null) {
                        val fingerTip = landmark[extendedFinger]
                        if (shouldPlaySound(handId, fingerTip)) {
                            playMovementSound(handId)
                        }
                        drawLaser(
                            canvas,
                            fingerTip,
                            calculateFingerAngle(landmark, extendedFinger),
                            handId
                        )
                    }
                }
            }
        }
    }

    private val hiltPaint = Paint().apply {
        style = Paint.Style.FILL
    }

    private val bladePaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
        maskFilter = BlurMaskFilter(10f, BlurMaskFilter.Blur.NORMAL)
        alpha = 255
    }

    private fun drawLaser(
        canvas: Canvas,
        handPosition: NormalizedLandmark,
        angle: Float,
        handId: Int
    ): List<Pair<Float, Float>> {
        val centerX = handPosition.x() * imageWidth * scaleFactor
        val centerY = handPosition.y() * imageHeight * scaleFactor

        val flickerFactor = (0.95f + (Math.random() * 0.1f)).toFloat()

        val hiltWidth = 20f * scaleFactor
        val hiltHeight = 100f * scaleFactor
        val bladeWidth = 10f * scaleFactor * flickerFactor
        val bladeHeight = 300f * scaleFactor

        val hiltCorners = listOf(
            Pair(centerX - hiltWidth / 2, centerY),
            Pair(centerX + hiltWidth / 2, centerY),
            Pair(centerX + hiltWidth / 2, centerY + hiltHeight),
            Pair(centerX - hiltWidth / 2, centerY + hiltHeight)
        ).map { rotatePoint(it.first, it.second, centerX, centerY, angle) }



        val bladeCorners = listOf(
            Pair(centerX - bladeWidth / 2, centerY),
            Pair(centerX + bladeWidth / 2, centerY),
            Pair(centerX + bladeWidth / 2, centerY - bladeHeight),
            Pair(centerX - bladeWidth / 2, centerY - bladeHeight)
        ).map { rotatePoint(it.first, it.second, centerX, centerY, angle) }

        // Set hilt color
        hiltPaint.color = if (handId == 0) Color.rgb(12, 20, 45) else Color.rgb(43, 5, 8)
        val hiltPath = android.graphics.Path().apply {
            moveTo(hiltCorners[0].first, hiltCorners[0].second)
            hiltCorners.forEach { lineTo(it.first, it.second) }
            close()
        }
        canvas.drawPath(hiltPath, hiltPaint)

        // Add gradient to the blade
        val bladeGradient = LinearGradient(
            centerX, centerY - bladeHeight, centerX, centerY,
            Color.WHITE, if (handId == 0) Color.rgb(123, 156, 230) else Color.RED,
            Shader.TileMode.CLAMP
        )
        bladePaint.shader = bladeGradient

        val bladePath = android.graphics.Path().apply {
            moveTo(bladeCorners[0].first, bladeCorners[0].second)
            bladeCorners.forEach { lineTo(it.first, it.second) }
            close()
        }
        canvas.drawPath(bladePath, bladePaint)

        val sparkRadius = 5f * scaleFactor
        val (topLeft, _, bottomRight, _) = bladeCorners
        val numberOfSparks = 50

        for (i in 0..numberOfSparks) {
            val r = Random.nextFloat()
            val randomX = r * (bottomRight.first - topLeft.first) + topLeft.first
            val randomY = r * (bottomRight.second - topLeft.second) + topLeft.second
            canvas.drawCircle(randomX, randomY, sparkRadius, sparkPaint)
        }

        return bladeCorners
    }

    private val handMediaPlayers = mutableMapOf<Int, MediaPlayer>()

    private fun playMovementSound(handId: Int) {
        val soundResId = if (handId == 0) R.raw.laser_move else R.raw.laser_1

        // Get or create the MediaPlayer for the specific hand
        val player = handMediaPlayers.getOrPut(handId) {
            MediaPlayer.create(context, soundResId)
        }

        try {
            if (player.isPlaying) {
                player.stop() // Stop the current sound for this hand
                player.prepare() // Prepare the player for the next sound
            }
            player.seekTo(0)
            player.start()
        } catch (e: Exception) {
            Log.e("MediaPlayerError", "Error playing movement sound for hand $handId", e)
        }
    }

    private val sparkPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
        maskFilter = BlurMaskFilter(50f, BlurMaskFilter.Blur.NORMAL)
    }

}