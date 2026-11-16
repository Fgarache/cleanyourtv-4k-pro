package com.gathin.tvhome;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public final class AudioRouteController {
    private static final String TAG = "TvHome.AudioRoute";

    public static final class AudioEndpoint {
        public final String id;
        public final String name;
        public final boolean isBluetooth;
        public final boolean isCurrent;
        public final Object nativeDevice;

        public AudioEndpoint(String id, String name, boolean isBluetooth, boolean isCurrent, Object nativeDevice) {
            this.id = id;
            this.name = name;
            this.isBluetooth = isBluetooth;
            this.isCurrent = isCurrent;
            this.nativeDevice = nativeDevice;
        }
    }

    private static BluetoothProfile a2dpProfile;

    static {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter != null) {
                adapter.getProfileProxy(null, new BluetoothProfile.ServiceListener() {
                    @Override
                    public void onServiceConnected(int profile, BluetoothProfile proxy) {
                        if (profile == BluetoothProfile.A2DP) {
                            a2dpProfile = proxy;
                        }
                    }

                    @Override
                    public void onServiceDisconnected(int profile) {
                        if (profile == BluetoothProfile.A2DP) {
                            a2dpProfile = null;
                        }
                    }
                }, BluetoothProfile.A2DP);
            }
        } catch (Throwable ignored) {}
    }

    public static void init(Context context) {
        if (a2dpProfile != null) return;
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter != null) {
                adapter.getProfileProxy(context.getApplicationContext(), new BluetoothProfile.ServiceListener() {
                    @Override
                    public void onServiceConnected(int profile, BluetoothProfile proxy) {
                        if (profile == BluetoothProfile.A2DP) {
                            a2dpProfile = proxy;
                        }
                    }

                    @Override
                    public void onServiceDisconnected(int profile) {
                        if (profile == BluetoothProfile.A2DP) {
                            a2dpProfile = null;
                        }
                    }
                }, BluetoothProfile.A2DP);
            }
        } catch (Throwable ignored) {}
    }

    public static boolean hasConnectedBluetoothAudio(Context context) {
        init(context);
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter != null && adapter.isEnabled()) {
                if (adapter.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothProfile.STATE_CONNECTED) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}

        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                AudioDeviceInfo[] devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
                if (devices != null) {
                    for (AudioDeviceInfo d : devices) {
                        int type = d.getType();
                        if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                                || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                                || (Build.VERSION.SDK_INT >= 31 && type == 26 /* TYPE_BLE_HEADSET */)
                                || (Build.VERSION.SDK_INT >= 31 && type == 27 /* TYPE_BLE_SPEAKER */)) {
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        if (a2dpProfile != null) {
            try {
                List<BluetoothDevice> connected = a2dpProfile.getConnectedDevices();
                if (connected != null && !connected.isEmpty()) return true;
            } catch (Throwable ignored) {}
        }
        return false;
    }

    public static boolean isBluetoothActive(Context context) {
        init(context);
        SharedPreferences prefs = context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        String pref = prefs.getString("audio_route_target", null);
        if ("speaker".equals(pref)) return false;
        if ("bluetooth".equals(pref)) return true;

        if (a2dpProfile != null) {
            try {
                Method getActive = a2dpProfile.getClass().getMethod("getActiveDevice");
                Object dev = getActive.invoke(a2dpProfile);
                if (dev != null) return true;
                return false;
            } catch (Throwable ignored) {}
        }
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                String routing = am.getParameters("routing");
                if (routing != null) {
                    if (routing.contains("128") || routing.contains("256")) return true;
                    if (routing.contains("2")) return false;
                }
            }
        } catch (Throwable ignored) {}
        return true;
    }

    public static List<AudioEndpoint> getAvailableEndpoints(Context context) {
        init(context);
        List<AudioEndpoint> list = new ArrayList<>();
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        String btName = "Dispositivo Bluetooth";
        BluetoothDevice a2dpDev = null;

        if (a2dpProfile != null) {
            try {
                List<BluetoothDevice> connected = a2dpProfile.getConnectedDevices();
                if (connected != null && !connected.isEmpty()) {
                    a2dpDev = connected.get(0);
                    String n = a2dpDev.getName();
                    if (n != null && !n.isEmpty()) btName = n;
                }
            } catch (Throwable ignored) {}
        }

        AudioDeviceInfo speakerDev = null;
        AudioDeviceInfo btAudioDev = null;

        if (am != null) {
            try {
                AudioDeviceInfo[] devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
                if (devices != null) {
                    for (AudioDeviceInfo d : devices) {
                        int type = d.getType();
                        if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                                || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                                || (Build.VERSION.SDK_INT >= 31 && (type == 26 || type == 27))) {
                            btAudioDev = d;
                            CharSequence n = d.getProductName();
                            if (n != null && n.length() > 0) btName = n.toString();
                        } else if (type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                            speakerDev = d;
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        if ("Dispositivo Bluetooth".equals(btName)) {
            try {
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                if (adapter != null) {
                    for (BluetoothDevice bd : adapter.getBondedDevices()) {
                        if (bd != null && adapter.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothProfile.STATE_CONNECTED) {
                            String n = bd.getName();
                            if (n != null && !n.isEmpty()) {
                                btName = n;
                                if (a2dpDev == null) a2dpDev = bd;
                                break;
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        boolean btIsCurrent = isBluetoothActive(context);

        // Endpoint 1: Bocinas del TV
        list.add(new AudioEndpoint(
                "speaker",
                "Bocinas de la TV",
                false,
                !btIsCurrent,
                speakerDev
        ));

        // Endpoint 2: Dispositivo Bluetooth
        list.add(new AudioEndpoint(
                "bluetooth",
                btName,
                true,
                btIsCurrent,
                btAudioDev != null ? btAudioDev : a2dpDev
        ));

        return list;
    }

    public static boolean switchTo(Context context, AudioEndpoint endpoint) {
        init(context);
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return false;

        SharedPreferences prefs = context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString("audio_route_target", endpoint.id).apply();

        try {
            if (endpoint.isBluetooth) {
                // Cambiar audio a Bluetooth
                if (Build.VERSION.SDK_INT >= 31 && endpoint.nativeDevice instanceof AudioDeviceInfo) {
                    try {
                        Method setComm = am.getClass().getMethod("setCommunicationDevice", AudioDeviceInfo.class);
                        setComm.invoke(am, (AudioDeviceInfo) endpoint.nativeDevice);
                    } catch (Throwable ignored) {}
                }
                if (a2dpProfile != null) {
                    try {
                        BluetoothDevice target = null;
                        if (endpoint.nativeDevice instanceof BluetoothDevice) {
                            target = (BluetoothDevice) endpoint.nativeDevice;
                        } else {
                            List<BluetoothDevice> connected = a2dpProfile.getConnectedDevices();
                            if (connected != null && !connected.isEmpty()) target = connected.get(0);
                        }
                        if (target != null) {
                            Method setActive = a2dpProfile.getClass().getMethod("setActiveDevice", BluetoothDevice.class);
                            setActive.invoke(a2dpProfile, target);
                        }
                    } catch (Throwable ignored) {}
                }
                try {
                    Method setParams = am.getClass().getMethod("setParameters", String.class);
                    setParams.invoke(am, "routing=128"); // AUDIO_DEVICE_OUT_BLUETOOTH_A2DP
                } catch (Throwable ignored) {}
                return true;
            } else {
                // Cambiar audio a Bocinas de la TV
                if (Build.VERSION.SDK_INT >= 31) {
                    try {
                        Method clearComm = am.getClass().getMethod("clearCommunicationDevice");
                        clearComm.invoke(am);
                    } catch (Throwable ignored) {}
                }
                if (a2dpProfile != null) {
                    try {
                        Method setActive = a2dpProfile.getClass().getMethod("setActiveDevice", BluetoothDevice.class);
                        setActive.invoke(a2dpProfile, (BluetoothDevice) null);
                    } catch (Throwable ignored) {}
                }
                try {
                    Method setParams = am.getClass().getMethod("setParameters", String.class);
                    setParams.invoke(am, "routing=2"); // AUDIO_DEVICE_OUT_SPEAKER
                } catch (Throwable ignored) {}
                return true;
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error switching audio route: " + t.getMessage(), t);
            return false;
        }
    }
}
