package com.linkcast.receiver.nativebridge;

import java.nio.ByteBuffer;

public interface NativeCallbacks {
    int challengeResponse(int length, byte[] challenge, byte[] response);
    void onConnectionStatus(int kind, int status);
    void onVideoData(int flags, ByteBuffer buffer);
    void onAudioStreamCreate(int direction, String key, String format);
    void onAudioStreamDestroy(String key);
    void outputData(int channel, byte[] bytes);
    void onMediaInfoUpdate(int type, String[] metadata);
    void onTelephonyUpdate(int type, String[] info);
    void onRouteGuidanceUpdate(int type, byte[] data);
    void onRouteGuidanceManeuverInformation(int type, byte[] data);
    void onLaneGuidanceInformation(int type, byte[] data);
    void outputDebugString(String tag, String message);
}
