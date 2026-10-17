package com.ljyh.mei.ui.component.sheet

/** A fallback snapshot must be replaced when the shared cover takes ownership. */
internal class PlayerSheetCaptures<Layer> {
    private val recorded = mutableMapOf<Layer, Boolean>()

    fun shouldCapture(layer: Layer, sharedArtwork: Boolean, live: Boolean = false): Boolean =
        live || recorded[layer] != sharedArtwork

    fun markCaptured(layer: Layer, sharedArtwork: Boolean) {
        recorded[layer] = sharedArtwork
    }

    fun clear() = recorded.clear()
}
