package com.linkcast.receiver

import com.example.autoservice.carplay.CarplayNative

enum class ProjectionState(val status: Int) {
    Idle(CarplayNative.kStatusIdel),
    Authing(CarplayNative.kStatusAuthing),
    AuthSucceeded(CarplayNative.kStatusAuthSucceeded),
    Connecting(CarplayNative.kStatusConnecting),
    Connected(CarplayNative.kStatusConnected),
    VideoStream(CarplayNative.kStatusVideoStream),
    ConnectFailed(CarplayNative.kStatusConnectFailed),
    AuthFailed(CarplayNative.kStatusAuthFailed);

    companion object {
        fun fromStatus(status: Int): ProjectionState {
            return entries.firstOrNull { it.status == status } ?: Idle
        }
    }
}

class ProjectionStateMachine(
    private val onStateChanged: (ProjectionState) -> Unit,
    private val onFailure: () -> Unit,
) {
    var state: ProjectionState = ProjectionState.Idle
        private set

    fun acceptNativeStatus(status: Int) {
        val next = ProjectionState.fromStatus(status)
        state = next
        onStateChanged(next)
        if (next == ProjectionState.ConnectFailed || next == ProjectionState.AuthFailed) {
            onFailure()
        }
    }

    fun reset() {
        state = ProjectionState.Idle
        onStateChanged(ProjectionState.Idle)
    }
}
