package com.example.autoservice.carplay;

import android.os.Build;
import android.util.Log;

import com.google.android.projection.common.UsedByNative;
import com.linkcast.receiver.nativebridge.NativeCallbackRegistry;

import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CarplayNative {
    public static final int kStatusIdel = 0;
    public static final int kStatusAuthing = 1;
    public static final int kStatusAuthSucceeded = 2;
    public static final int kStatusConnecting = 3;
    public static final int kStatusConnected = 4;
    public static final int kStatusConnectFailed = 5;
    public static final int kStatusVideoStream = 6;
    public static final int kStatusAuthFailed = -2;

    private static final String TAG = "CarplayNative";
    private static final AtomicBoolean running = new AtomicBoolean(false);

    static {
        int sdk = Build.VERSION.SDK_INT;
        System.loadLibrary((sdk <= 25 || sdk >= 35) ? "carplay2_jni" : "carplay_jni");
    }

    private CarplayNative() {
    }

    public static native void start(byte[] cfg, int a, int b, int audioBuffers);
    public static native void stop();
    private static native long createIap2Link();
    public static native void destroyIap2Link(long h);
    public static native int income_data(long h, byte[] buf, int len);
    public static native void CarPlayStartSession(long h);
    public static native void setWifiConfiguration(String ssid, String psk, int a, int b, String s3, String s4, String s5);
    private static native void setResolutions(int[] res, int n, int[] opts);
    public static native void setAltResolutions(int[] res, int n);
    public static native void setResolutionIndex(int i);
    private static native void setResponse(byte[] resp, int len);
    public static native int sendAudioData(byte[] buf, int len, float vol, long ptr);
    public static native byte[] readAudioData(long ptr);
    public static native void onTouchEvent(int action, int x, int y);
    public static native void onKeyEvent(int code, int action);
    public static native void onKnobEvent(int[] state);
    public static native void forceKeyFrame(int i);
    public static native void SetNightMode(int mode);
    public static native void RequestUI(String s);
    public static native String setStatusBarEdge(int i);
    public static native void setDataPath(String a, String b, String c);
    public static native int onBrowseHandler(String a, String b, String c, int d, byte[] e, int f);

    public static long createIap2LinkHandle() {
        return createIap2Link();
    }

    public static void configureResolutions(int[] resolutions, int count, int[] options) {
        setResolutions(resolutions, count, options);
    }

    public static void provideChallengeResponse(byte[] response, int length) {
        setResponse(response, length);
    }

    public static boolean startNativeSession(byte[] payload, int length, int flag, int audioBuffers) {
        if (!running.getAndSet(true)) {
            start(payload, length, flag, audioBuffers);
            return true;
        }
        return false;
    }

    public static boolean markNativeStarted() {
        return running.getAndSet(true);
    }

    public static void markNativeStopped() {
        running.set(false);
    }

    public static boolean isNativeStarted() {
        return running.get();
    }

    public static String statusString(int status) {
        switch (status) {
            case kStatusAuthFailed: return "kStatusAuthFailed";
            case kStatusIdel: return "kStatusIdel";
            case kStatusAuthing: return "kStatusAuthing";
            case kStatusAuthSucceeded: return "kStatusAuthSucceeded";
            case kStatusConnecting: return "kStatusConnecting";
            case kStatusConnected: return "kStatusConnected";
            case kStatusConnectFailed: return "kStatusConnectFailed";
            case kStatusVideoStream: return "kStatusVideoStream";
            default: return "Unknown-" + status;
        }
    }

    @UsedByNative
    private static int ChallengeResponse(int n, byte[] challenge) {
        byte[] response = new byte[256];
        int length = NativeCallbackRegistry.challengeResponse(n, challenge, response);
        // Always hand the response back to native, even when length==0.
        // Offline the native layer self-signs with its built-in key; the Java
        // side only relays an optional external signer (USB chip / network).
        setResponse(response, Math.max(length, 0));
        Log.d(TAG, String.format(Locale.US, "ChallengeResponse length=%d", length));
        return length;
    }

    @UsedByNative
    private static void onConnectionStatus(int kind, int status) {
        NativeCallbackRegistry.onConnectionStatus(kind, status);
    }

    @UsedByNative
    private static void onVideoDataCallback(int flags, ByteBuffer buf) {
        NativeCallbackRegistry.onVideoData(flags, buf);
    }

    @UsedByNative
    private static void onAudioStreamCreate(int dir, String key, String fmt) {
        NativeCallbackRegistry.onAudioStreamCreate(dir, key, fmt);
    }

    @UsedByNative
    private static void onAudioStreamDestroy(String key) {
        NativeCallbackRegistry.onAudioStreamDestroy(key);
    }

    @UsedByNative
    private static void output_data(int i, byte[] bytes) {
        NativeCallbackRegistry.outputData(i, bytes);
    }

    @UsedByNative
    private static void onMediaInfoUpdate(int i, String[] meta) {
        NativeCallbackRegistry.onMediaInfoUpdate(i, meta);
    }

    @UsedByNative
    private static void onTelephonyUpdate(int i, String[] info) {
        NativeCallbackRegistry.onTelephonyUpdate(i, info);
    }

    @UsedByNative
    private static void onRouteGuidanceUpdate(int i, byte[] data) {
        NativeCallbackRegistry.onRouteGuidanceUpdate(i, data);
    }

    @UsedByNative
    private static void onRouteGuidanceManeuverInformation(int i, byte[] data) {
        NativeCallbackRegistry.onRouteGuidanceManeuverInformation(i, data);
    }

    @UsedByNative
    private static void onLaneGuidanceInformation(int i, byte[] data) {
        NativeCallbackRegistry.onLaneGuidanceInformation(i, data);
    }

    @UsedByNative
    private static void outputDebugString(String tag, String msg) {
        NativeCallbackRegistry.outputDebugString(tag, msg);
    }
}
