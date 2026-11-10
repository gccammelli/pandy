// SPDX-License-Identifier: MIT
// Copyright (c) Giancarlo Cammelli
package io.github.gccammelli.pandy.studio.io

/** Platform ZIP archive support. */
expect object Zip {
    fun read(bytes: ByteArray): Map<String, ByteArray>
    fun write(entries: List<Pair<String, ByteArray>>, deflate: Boolean = true): ByteArray
}
