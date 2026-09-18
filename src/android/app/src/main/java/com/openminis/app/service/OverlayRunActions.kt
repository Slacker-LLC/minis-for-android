package com.openminis.app.service

/**
 * [T-eta-overlay-run-panel] What the floating run capsule may do while a turn is live.
 *
 * Ported from Eta's assistant overlay panel (Mangi-11/Eta @ c15de97), where the panel shows
 * the run in place and offers stop. This app's capsule already displays the run and opens
 * the session on tap, so the rule that needs one owner is the destructive one: stopping is
 * offered exactly while there is still something to cancel. A finished turn that lingers on
 * screen must not look cancellable.
 */
object OverlayRunActions {

    /**
     * True when a tool is executing or a stream is open. Both conditions come from the same
     * tracker snapshot the capsule renders, so the control cannot outlive the run.
     */
    fun offersStop(toolRunning: Boolean, streamActive: Boolean): Boolean = toolRunning || streamActive
}
