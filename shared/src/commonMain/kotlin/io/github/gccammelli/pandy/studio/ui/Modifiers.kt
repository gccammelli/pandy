// SPDX-License-Identifier: MIT
// Copyright (c) Giancarlo Cammelli
package io.github.gccammelli.pandy.studio.ui

import io.github.gccammelli.pandy.ui.*

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

fun Modifier.graphicsLayerRotate(degrees: Float) = graphicsLayer { rotationZ = degrees }
