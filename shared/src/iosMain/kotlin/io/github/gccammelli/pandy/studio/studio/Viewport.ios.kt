// SPDX-License-Identifier: MIT
// Copyright (c) Giancarlo Cammelli
package io.github.gccammelli.pandy.studio.studio

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Studio is not offered on iOS yet (`PlatformUi.studio` is null there), so there is no 3D view to draw. */
@Composable
actual fun Viewport(scene: Scene, dark: Boolean, modifier: Modifier) { Box(modifier) }
