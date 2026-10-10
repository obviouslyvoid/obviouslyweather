// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather.ui

import org.junit.Assert.*
import org.junit.Test

class BackMotionTest {
    @Test fun bothEdgesShareGeometryAndMirrorOnlyHorizontalMovement() {
        listOf(0f, 0.3f, 0.8f, 1f, 1.5f, 2f).forEach { progress ->
            val left = predictiveTransform(progress, false)
            val right = predictiveTransform(progress, true)
            assertEquals(left.scale, right.scale, 0f)
            assertEquals(left.alpha, right.alpha, 0f)
            assertEquals(left.cornerDp, right.cornerDp, 0f)
            assertEquals(left.translationDp, -right.translationDp, 0f)
        }
    }

    @Test fun dismissalStartsContinuouslyWithoutAScaleOrTranslationJump() {
        val before = predictiveTransform(0.9999f, false)
        val after = predictiveTransform(1.0001f, false)
        assertEquals(before.scale, after.scale, 0.0001f)
        assertEquals(before.translationDp, after.translationDp, 0.01f)
        assertEquals(before.cornerDp, after.cornerDp, 0.01f)
        assertEquals(before.alpha, after.alpha, 0.001f)
    }

    @Test fun cancellationReturnsToOriginalScreenAndCommitEndsInvisible() {
        val original = predictiveTransform(0f, false)
        assertEquals(1f, original.scale, 0f)
        assertEquals(0f, original.translationDp, 0f)
        assertEquals(1f, original.alpha, 0f)
        assertEquals(0f, original.cornerDp, 0f)
        assertEquals(1f, predictiveTransform(1f, false).alpha, 0f)
        assertEquals(0f, predictiveTransform(2f, false).alpha, 0f)
    }

    @Test fun invalidProgressCannotCreateNaNOrInvisibleGesturePreview() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f).forEach {
            assertEquals(predictiveTransform(0f, false), predictiveTransform(it, false))
        }
        assertEquals(predictiveTransform(2f, false), predictiveTransform(20f, false))
    }
}
