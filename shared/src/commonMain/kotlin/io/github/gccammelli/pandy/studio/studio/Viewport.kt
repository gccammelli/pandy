// SPDX-License-Identifier: MIT
// Copyright (c) Giancarlo Cammelli
package io.github.gccammelli.pandy.studio.studio

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The 3D plate: orbit with one finger, pinch to zoom, two fingers to pan; tap an object to select it and drag it
 * across the bed. In preview mode it draws the sliced toolpaths of the visible layers instead of the models.
 */
@Composable
expect fun Viewport(scene: Scene, dark: Boolean, modifier: Modifier = Modifier)
