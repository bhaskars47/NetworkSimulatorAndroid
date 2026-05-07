package com.networksimulator.model

import java.io.Serializable

/**
 * Runtime configuration passed from the UI to [com.networksimulator.vpn.NetworkSimulatorVpnService]
 * via an Intent extra.
 *
 * @param isEnabled          Whether simulation is currently active.
 * @param profile            Active [NetworkProfile] to apply.
 * @param targetPackageName  If non-empty, only this app's traffic is intercepted.
 *                           Leave blank to intercept ALL device traffic (use with caution).
 */
data class SimulationConfig(
    val isEnabled:          Boolean        = false,
    val profile:            NetworkProfile = NetworkProfile.slow3G(),
    val targetPackageName:  String         = ""
) : Serializable
