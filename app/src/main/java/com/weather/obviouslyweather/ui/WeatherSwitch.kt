// SPDX-License-Identifier: GPL-3.0-or-later
package com.weather.obviouslyweather.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Material switch proportions with a slightly longer transition for settings controls. */
@Composable
fun WeatherSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val colors = SwitchDefaults.colors()
    val thumbColor by animateColorAsState(
        targetValue = when {
            enabled && checked -> colors.checkedThumbColor
            enabled -> colors.uncheckedThumbColor
            checked -> colors.disabledCheckedThumbColor
            else -> colors.disabledUncheckedThumbColor
        },
        animationSpec = tween(240, easing = FastOutSlowInEasing), label = "Switch thumb color"
    )
    val trackColor by animateColorAsState(
        targetValue = when {
            enabled && checked -> colors.checkedTrackColor
            enabled -> colors.uncheckedTrackColor
            checked -> colors.disabledCheckedTrackColor
            else -> colors.disabledUncheckedTrackColor
        },
        animationSpec = tween(240, easing = FastOutSlowInEasing), label = "Switch track color"
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            enabled && checked -> colors.checkedBorderColor
            enabled -> colors.uncheckedBorderColor
            checked -> colors.disabledCheckedBorderColor
            else -> colors.disabledUncheckedBorderColor
        },
        animationSpec = tween(240, easing = FastOutSlowInEasing), label = "Switch border color"
    )
    val thumbSize by animateDpAsState(
        targetValue = if (pressed) 28.dp else if (checked) 24.dp else 16.dp,
        animationSpec = if (pressed) snap() else tween(240, easing = FastOutSlowInEasing),
        label = "Switch thumb size"
    )
    val thumbOffset by animateDpAsState(
        targetValue = when {
            pressed && checked -> 22.dp
            pressed -> 2.dp
            checked -> 24.dp
            else -> 8.dp
        },
        animationSpec = if (pressed) snap() else tween(240, easing = FastOutSlowInEasing),
        label = "Switch thumb position"
    )
    val trackShape = RoundedCornerShape(percent = 50)
    Box(
        modifier.minimumInteractiveComponentSize()
            .toggleable(checked, interactionSource = interactions, indication = null,
                enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .wrapContentSize(Alignment.Center)
            .requiredSize(52.dp, 32.dp)
            .border(2.dp, borderColor, trackShape)
            .background(trackColor, trackShape)
    ) {
        Box(Modifier.align(Alignment.CenterStart).offset(x = thumbOffset).size(thumbSize)
            .indication(interactions, ripple(bounded = false, radius = 20.dp))
            .background(thumbColor, CircleShape))
    }
}
