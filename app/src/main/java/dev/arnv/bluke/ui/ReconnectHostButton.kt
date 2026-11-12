package dev.arnv.bluke.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.arnv.bluke.bluetooth.BluetoothKeyboardManager
import dev.arnv.bluke.bluetooth.BluetoothState
import dev.arnv.bluke.bluetooth.HidLifecycleState

@Composable
internal fun ReconnectHostButton(btManager: BluetoothKeyboardManager, iconOnly: Boolean = false) {
    val state by btManager.serviceState.collectAsState()
    val lifecycle by btManager.lifecycleState.collectAsState()
    val devices by btManager.bondedDevices.collectAsState()
    val target = btManager.getReconnectTarget()?.takeIf { it in devices } ?: return
    if (state is BluetoothState.Connected || state.blocksInputLaunch()) return
    val enabled = lifecycle !is HidLifecycleState.Connecting &&
        lifecycle !is HidLifecycleState.Registering && lifecycle !is HidLifecycleState.BindingProxy
    val onClick = { btManager.connectDevice(target) }
    val modifier = Modifier.testTag("reconnect_host_btn")
    if (iconOnly) {
        ToolbarIconButton(onClick = onClick, modifier = modifier, enabled = enabled, onClickLabel = "Reconnect to last host") {
            ToolbarIcon(Icons.Default.Refresh, "Reconnect to last host")
        }
    } else {
        ToolbarButton(onClick = onClick, modifier = modifier, enabled = enabled) {
            ToolbarIcon(Icons.Default.Refresh, null)
            ToolbarLabel("Reconnect")
        }
    }
}
