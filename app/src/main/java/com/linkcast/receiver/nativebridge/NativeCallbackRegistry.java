package com.linkcast.receiver.nativebridge;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

public final class NativeCallbackRegistry {
    private static final AtomicReference<NativeCallbacks> HANDLER = new AtomicReference<>();

    private NativeCallbackRegistry() {
    }

    public static void attach(NativeCallbacks callbacks) {
        HANDLER.set(callbacks);
    }

    public static void detach(NativeCallbacks callbacks) {
        HANDLER.compareAndSet(callbacks, null);
    }

    public static int challengeResponse(int length, byte[] challenge, byte[] response) {
        NativeCallbacks callbacks = HANDLER.get();
        return callbacks == null ? 0 : callbacks.challengeResponse(length, challenge == null ? new byte[0] : challenge, response);
    }

    public static void onConnectionStatus(int kind, int status) {
        NativeCallbacks callbacks = HANDLER.get();
        if (callbacks != null) callbacks.onConnectionStatus(kind, status);
    }

    public static void onVideoData(int flags, ByteBuffer buffer) {
        NativeCallbacks callbacks = HANDLER.get();
        if (callbacks != null) callbacks.onVideoData(flags, buffer);
    }

    public static void onAudioStreamCreate(int direction, String key, String format) {
        NativeCallbacks callbacks = HANDLER.get();
        if (callbacks != null) callbacks.onAudioStreamCreate(direction, key == null ? "" : key, format == null ? "" : format);
    }

    public static void onAudioStreamDestroy(String key) {
        NativeCallbacks callbacks = HANDLER.get();
        if (callbacks != null) callbacks.onAudioStreamDestroy(key == null ? "" : key);
    }

    public static void outputData(int channel, byte[] bytes) {
        NativeCallbacks callbacks = HANDLER.get();
        if (callbacks != null) callbacks.outputData(channel, bytes == null ? new byte[0] : bytes);
    }

    public static void onMediaInfoUpdate(int type, String[] metadata) {
        NativeCallbacks callbacks = HANDLER.get();
        if (callbacks != null) callbacks.onMediaInfoUpdate(type, metadata == null ? new String[0] : metadata);
    }

    public static void onTelephonyUpdate(int type, String[] info) {
        NativeCallbacks callbacks = HANDLER.get();
        if (callbacks != null) callbacks.onTelephonyUpdate(type, info == null ? new String[0] : info);
    }

    public static void onRouteGuidanceUpdate(int type, byte[] data) {
        NativeCallbacks callbacks = HANDLER.get();
        if (callbacks != null) callbacks.onRouteGuidanceUpdate(type, data == null ? new byte[0] : data);
    }

    public static void onRouteGuidanceManeuverInformation(int type, byte[] data) {
        NativeCallbacks callbacks = HANDLER.get();
        if (callbacks != null) callbacks.onRouteGuidanceManeuverInformation(type, data == null ? new byte[0] : data);
    }

    public static void onLaneGuidanceInformation(int type, byte[] data) {
        NativeCallbacks callbacks = HANDLER.get();
        if (callbacks != null) callbacks.onLaneGuidanceInformation(type, data == null ? new byte[0] : data);
    }

    public static void outputDebugString(String tag, String message) {
        NativeCallbacks callbacks = HANDLER.get();
        if (callbacks != null) callbacks.outputDebugString(tag == null ? "" : tag, message == null ? "" : message);
    }
}
