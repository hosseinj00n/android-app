/*
 * Copyright (c) 2021 Proton Technologies AG
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

package com.protonvpn.android.vpn

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Transformations
import com.protonvpn.android.R
import com.protonvpn.android.bus.ConnectedToServer
import com.protonvpn.android.bus.EventBus
import com.protonvpn.android.components.BaseActivityV2.Companion.showNoVpnPermissionDialog
import com.protonvpn.android.components.NotificationHelper
import com.protonvpn.android.models.config.UserData
import com.protonvpn.android.models.config.VpnProtocol
import com.protonvpn.android.models.profiles.Profile
import com.protonvpn.android.models.vpn.ConnectionParams
import com.protonvpn.android.models.vpn.Server
import com.protonvpn.android.utils.AndroidUtils.registerBroadcastReceiver
import com.protonvpn.android.utils.DebugUtils
import com.protonvpn.android.utils.Log
import com.protonvpn.android.utils.ProtonLogger
import com.protonvpn.android.utils.Storage
import com.protonvpn.android.utils.eagerMapNotNull
import com.protonvpn.android.utils.implies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import me.proton.core.network.domain.NetworkManager
import javax.inject.Singleton

@Singleton
open class VpnConnectionManager(
    private val appContext: Context,
    private val userData: UserData,
    private val backendProvider: VpnBackendProvider,
    private val networkManager: NetworkManager,
    private val vpnErrorHandler: VpnConnectionErrorHandler,
    private val vpnStateMonitor: VpnStateMonitor,
    private val notificationHelper: NotificationHelper,
    private val scope: CoroutineScope
) : VpnStateSource {

    companion object {
        private const val STORAGE_KEY_STATE = "VpnStateMonitor.VPN_STATE_NAME"
    }

    private var ongoingConnect: Job? = null
    private val activeBackendObservable = MutableLiveData<VpnBackend?>()
    private val activeBackend: VpnBackend? get() = activeBackendObservable.value

    private var connectionParams: ConnectionParams? = null
    private var lastProfile: Profile? = null

    override val selfStateObservable = MutableLiveData<VpnState>(VpnState.Disabled)

    // State taken from active backend or from monitor when no active backend, value always != null
    private val stateInternal: LiveData<VpnState> = Transformations.switchMap(
        activeBackendObservable.eagerMapNotNull { it ?: this }, VpnStateSource::selfStateObservable)
    private val state get() = stateInternal.value!!

    val retryInfo get() = activeBackend?.retryInfo

    var initialized = false

    init {
        Log.i("create state monitor")

        appContext.registerBroadcastReceiver(IntentFilter(NotificationHelper.DISCONNECT_ACTION)) { intent ->
            when (intent?.action) {
                NotificationHelper.DISCONNECT_ACTION -> disconnect()
            }
        }

        stateInternal.observeForever {
            if (initialized) {
                Storage.saveString(STORAGE_KEY_STATE, state.name)

                var newState = it ?: VpnState.Disabled
                if ((newState as? VpnState.Error)?.type == ErrorType.AUTH_FAILED_INTERNAL) {
                    newState = VpnState.CheckingAvailability
                    DebugUtils.debugAssert { ongoingConnect == null }
                    ongoingConnect = scope.launch {
                        checkAuthFailedReason()
                    }
                }
                vpnStateMonitor.status.value = VpnStateMonitor.Status(newState, connectionParams)

                ProtonLogger.log("VpnStateMonitor state=$it backend=${activeBackend?.name}")
                DebugUtils.debugAssert {
                    (state in arrayOf(VpnState.Connecting, VpnState.Connected, VpnState.Reconnecting))
                        .implies(connectionParams != null && activeBackend != null)
                }

                when (state) {
                    VpnState.Connected -> {
                        EventBus.postOnMain(ConnectedToServer(Storage.load(Server::class.java)))
                    }
                    VpnState.Disabled -> {
                        EventBus.postOnMain(ConnectedToServer(null))
                    }
                }
            }
        }

        scope.launch {
            vpnErrorHandler.switchConnectionFlow.collect { fallback ->
                if (vpnStateMonitor.isConnected || vpnStateMonitor.isEstablishingConnection)
                    fallbackConnect(fallback)
            }
        }

        initialized = true
    }

    private fun activateBackend(newBackend: VpnBackend) {
        DebugUtils.debugAssert {
            activeBackend == null || activeBackend == newBackend
        }
        if (activeBackend != newBackend) {
            activeBackend?.active = false
            newBackend.active = true
            newBackend.setSelfState(VpnState.Connecting)
            activeBackendObservable.value = newBackend
            setSelfState(VpnState.Disabled)
        }
    }

    private suspend fun checkAuthFailedReason() {
        activeBackend?.setSelfState(VpnState.CheckingAvailability)
        when (val result = vpnErrorHandler.onAuthError(lastProfile!!)) {
            is VpnFallbackResult.SwitchProfile ->
                fallbackConnect(result)
            is VpnFallbackResult.Error -> {
                ongoingConnect = null
                activeBackend?.setSelfState(VpnState.Error(result.type))
            }
        }
    }

    private suspend fun fallbackConnect(fallback: VpnFallbackResult.SwitchProfile) {
        fallback.notificationReason?.let {
            vpnStateMonitor.fallbackConnectionFlow.emit(it)
        }
        connect(appContext, fallback.profile, "automatic fallback")
    }

    private suspend fun coroutineConnect(profile: Profile) {
        // If smart profile fails we need this to handle reconnect request
        lastProfile = profile
        val server = profile.server!!
        ProtonLogger.log("Connect: ${server.domain}")
        connectionParams = ConnectionParams(profile, server, null, null)

        if (profile.getProtocol(userData) == VpnProtocol.Smart)
            setSelfState(VpnState.ScanningPorts)

        var protocol = profile.getProtocol(userData)
        if (!networkManager.isConnectedToNetwork() && protocol == VpnProtocol.Smart)
            protocol = userData.manualProtocol
        var preparedConnection = backendProvider.prepareConnection(protocol, profile, server)
        if (preparedConnection == null) {
            ProtonLogger.log("Smart protocol: no protocol available for ${server.domain}, " +
                "falling back to ${userData.manualProtocol}")

            // If port scanning fails (because e.g. some temporary network situation) just connect
            // without smart protocol
            preparedConnection = backendProvider.prepareConnection(userData.manualProtocol, profile, server)!!
        }

        val newBackend = preparedConnection.backend
        if (activeBackend != null && activeBackend != newBackend)
            disconnectBlocking()

        connectionParams = preparedConnection.connectionParams
        ProtonLogger.log("Connecting using ${connectionParams?.info}")

        Storage.save(connectionParams, ConnectionParams::class.java)
        activateBackend(newBackend)
        activeBackend?.connect()
        ongoingConnect = null
    }

    private fun clearOngoingConnection() {
        ongoingConnect?.cancel()
        ongoingConnect = null
    }

    fun onRestoreProcess(context: Context, profile: Profile): Boolean {
        if (state == VpnState.Disabled && Storage.getString(STORAGE_KEY_STATE, null) == VpnState.Connected.name) {
            connect(context, profile, "Process restore")
            return true
        }
        return false
    }

    fun connect(context: Context, profile: Profile, connectionCauseLog: String? = null) {
        connectionCauseLog?.let { ProtonLogger.log("Connecting caused by: $it") }
        val intent = prepare(context)
        if (intent != null) {
            if (context is ActivityResultRegistryOwner) {
                val permissionCall = context.activityResultRegistry.register(
                    "VPNPermission", PermissionContract(intent)
                ) { permissionGranted ->
                    if (permissionGranted) {
                        connectWithPermission(context, profile)
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        (context as Activity).showNoVpnPermissionDialog()
                    }
                }
                permissionCall.launch(PermissionContract.VPN_PERMISSION_ACTIVITY)
            } else {
                showInsufficientPermissionNotification(context)
            }
        } else {
            connectWithPermission(context, profile)
        }
    }

    private fun connectWithPermission(context: Context, profile: Profile) {
        if (profile.server != null) {
            clearOngoingConnection()
            ongoingConnect = scope.launch {
                coroutineConnect(profile)
            }
        } else {
            notificationHelper.showInformationNotification(
                context, context.getString(R.string.error_server_not_set)
            )
        }
    }

    private fun showInsufficientPermissionNotification(context: Context) {
        notificationHelper.showInformationNotification(
            context,
            context.getString(R.string.insufficientPermissionsDetails),
            context.getString(R.string.insufficientPermissionsTitle),
            icon = R.drawable.ic_notification_disconnected
        )
    }

    protected open fun prepare(context: Context): Intent? = VpnService.prepare(context)

    private suspend fun disconnectBlocking() {
        Storage.delete(ConnectionParams::class.java)
        setSelfState(VpnState.Disabled)
        activeBackend?.disconnect()
        activeBackend?.active = false
        activeBackendObservable.value = null
        connectionParams = null
    }

    open fun disconnect() {
        ProtonLogger.log("Manually disconnecting")
        disconnectWithCallback()
    }

    suspend fun disconnectSync() {
        clearOngoingConnection()
        disconnectBlocking()
    }

    open fun disconnectWithCallback(afterDisconnect: (() -> Unit)? = null) {
        clearOngoingConnection()
        scope.launch {
            disconnectBlocking()
            vpnStateMonitor.onDisconnectedByUser.emit(Unit)
            afterDisconnect?.invoke()
        }
    }

    fun reconnect(context: Context) = scope.launch {
        clearOngoingConnection()
        if (activeBackend != null)
            activeBackend?.reconnect()
        else
            lastProfile?.let { connect(context, it, "reconnection") }
    }
}