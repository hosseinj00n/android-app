/*
 * Copyright (c) 2018 Proton Technologies AG
 *
 * This file is part of ProtonVPN.
 *
 * ProtonVPN is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * ProtonVPN is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with ProtonVPN.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.protonvpn.testsHelper

import android.os.Handler
import android.os.Looper
import com.azimolabs.conditionwatcher.ConditionWatcher
import com.protonvpn.android.models.config.VpnProtocol
import com.protonvpn.android.models.profiles.Profile
import com.protonvpn.android.models.vpn.Server
import com.protonvpn.android.ui.home.HomeActivity
import com.protonvpn.conditions.NetworkInstruction
import com.protonvpn.test.shared.MockedServers.getProfile
import com.protonvpn.test.shared.MockedServers.serverList

class ServiceTestHelper {

    private val helper = ServerManagerHelper()
    val stateMonitor = helper.vpnStateMonitor

    val serverManager = helper.serverManager
    val userData = helper.userData
    @JvmField var mockVpnBackend = helper.backend

    @JvmField var connectionManager = helper.vpnConnectionManager
    private val mainThreadHandler = Handler(Looper.getMainLooper())

    val isSecureCoreEnabled get() = userData.isSecureCoreEnabled

    fun addProfile(protocol: VpnProtocol, name: String, serverDomain: String): Profile {
        var server: Server? = null
        for (s in serverList) {
            if (s.domain == serverDomain) server = s
        }
        checkNotNull(server) { "No mocked server for domain: $serverDomain" }
        val profile = getProfile(protocol, server, name)
        Handler(Looper.getMainLooper()).post { helper.serverManager.addToProfileList(profile) }
        return profile
    }

    fun setDefaultProfile(profile: Profile) {
        mainThreadHandler.post { userData.defaultConnection = profile }
    }

    fun deleteCreatedProfiles() {
        mainThreadHandler.post {
            serverManager.deleteSavedProfiles()
            userData.setDefaultConnection(null)
        }
    }

    fun getExpiredTrialUserNotification(activity: HomeActivity) {
        mainThreadHandler.post { activity.showExpiredDialog() }
    }

    fun checkIfConnectedToVPN() {
        try {
            ConditionWatcher.waitForCondition(object : NetworkInstruction() {
                override fun checkCondition(): Boolean {
                    return stateMonitor.isConnected
                }
            })
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun enableSecureCore(state: Boolean) {
        mainThreadHandler.postDelayed({ userData.isSecureCoreEnabled = state }, 100)
    }

    fun checkIfDisconnectedFromVPN() {
        try {
            ConditionWatcher.waitForCondition(object : NetworkInstruction() {
                override fun checkCondition(): Boolean {
                    return !stateMonitor.isConnected
                }
            })
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
