package org.carplaytocarlife.iccoa;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;
import android.view.Surface;

/**
 * Clean-room boundary for the CarWith/CarLife source side.
 *
 * The descriptor and transaction numbers mirror the public Binder contract
 * observed in the supplied reference APK. Media conversion belongs outside
 * this class so the CarPlay output can be tested independently.
 */
public final class CarWithSourceAdapter implements AutoCloseable {
    private static final String TAG = "MiPlay-CarWith";
    private static final String HOST_PACKAGE = "com.miui.carlink";
    private static final String HOST_SERVICE = "com.xiaomi.ucar.carlife.CarlifeConnService";
    private static final String MANAGER_DESCRIPTOR =
            "com.baidu.carlife.mixing.aidl.ICarLifeCastManager";
    private static final String CALLBACK_DESCRIPTOR =
            "com.baidu.carlife.mixing.aidl.ICarLifeCastCallback";
    private static final String AUDIO_CALLBACK_DESCRIPTOR =
            "com.baidu.carlife.mixing.aidl.ISystemAudioCallback";

    public interface Listener {
        void onState(String state);

        void onAudio(byte[] pcm, int source, int sampleRate, int channels, int format);

        void onAudioState(int source, int state);
    }

    private final Context context;
    private final Listener listener;
    private final Binder castCallback = new CastCallbackBinder();
    private final Binder audioCallback = new AudioCallbackBinder();
    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            manager = service;
            try {
                if (!MANAGER_DESCRIPTOR.equals(service.getInterfaceDescriptor())) {
                    notifyState("host-descriptor-mismatch");
                    return;
                }
                service.linkToDeath(deathRecipient, 0);
                setCallback(4, castCallback);
                setCallback(3, audioCallback);
                notifyState("host-bound");
            } catch (Throwable error) {
                notifyState("host-bind-error:" + error.getClass().getSimpleName());
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            manager = null;
            notifyState("host-disconnected");
        }

        @Override
        public void onBindingDied(ComponentName name) {
            manager = null;
            notifyState("host-binding-died");
        }

        @Override
        public void onNullBinding(ComponentName name) {
            manager = null;
            notifyState("host-null-binding");
        }
    };
    private final IBinder.DeathRecipient deathRecipient = () -> {
        manager = null;
        notifyState("host-binder-died");
    };
    private volatile IBinder manager;
    private volatile boolean bound;

    public CarWithSourceAdapter(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public synchronized boolean bind() {
        if (bound) {
            notifyState(manager == null ? "host-binding" : "host-bound");
            return true;
        }
        Intent intent = new Intent().setComponent(new ComponentName(HOST_PACKAGE, HOST_SERVICE));
        try {
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            notifyState(bound ? "host-binding" : "host-bind-rejected");
            return bound;
        } catch (Throwable error) {
            notifyState("host-bind-error:" + error.getClass().getSimpleName());
            return false;
        }
    }

    public synchronized boolean isConnected() {
        return manager != null;
    }

    /** Sends CastConfig using the reference APK's Parcelable field order. */
    public boolean prepareCast(Surface surface, int width, int height, int dpi) {
        IBinder host = manager;
        if (host == null || surface == null || width <= 0 || height <= 0 || dpi <= 0) {
            return false;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(MANAGER_DESCRIPTOR);
            data.writeInt(1);
            data.writeInt(width);
            data.writeInt(height);
            data.writeInt(dpi);
            surface.writeToParcel(data, 0);
            host.transact(1, data, reply, 0);
            reply.readException();
            notifyState("cast-prepared");
            return true;
        } catch (Throwable error) {
            notifyState("cast-prepare-error:" + error.getClass().getSimpleName());
            return false;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    public void stopCast() {
        IBinder host = manager;
        if (host == null) {
            return;
        }
        transactNoArgs(host, 2);
        notifyState("cast-stopped");
    }

    @Override
    public synchronized void close() {
        IBinder host = manager;
        manager = null;
        if (host != null) {
            try {
                host.unlinkToDeath(deathRecipient, 0);
            } catch (Throwable ignored) {
                // The host may already have died.
            }
            transactNoArgs(host, 2);
        }
        if (bound) {
            context.unbindService(connection);
            bound = false;
        }
        notifyState("host-closed");
    }

    private void setCallback(int transaction, IBinder callback) throws RemoteException {
        IBinder host = manager;
        if (host == null) {
            return;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(MANAGER_DESCRIPTOR);
            data.writeStrongBinder(callback);
            host.transact(transaction, data, reply, 0);
            reply.readException();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static void transactNoArgs(IBinder host, int transaction) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(MANAGER_DESCRIPTOR);
            host.transact(transaction, data, reply, 0);
            reply.readException();
        } catch (Throwable ignored) {
            // Shutdown must remain best effort when the remote process exits.
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void notifyState(String state) {
        Log.i(TAG, state);
        if (listener != null) {
            listener.onState(state);
        }
    }

    private final class CastCallbackBinder extends Binder {
        CastCallbackBinder() {
            attachInterface(null, CALLBACK_DESCRIPTOR);
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(CALLBACK_DESCRIPTOR);
                return true;
            }
            if (code >= 1 && code <= 13) {
                data.enforceInterface(CALLBACK_DESCRIPTOR);
            }
            if (code == 1) {
                notifyState("carwith-service-ready");
            } else if (code == 2) {
                notifyState("cast-prepared:" + data.readInt());
            } else if (code == 10) {
                notifyState("carwith-service-destroyed");
            } else if (code == 11) {
                notifyState("audio-state:" + data.readInt());
            }
            if (reply != null) {
                reply.writeNoException();
            }
            return true;
        }
    }

    private final class AudioCallbackBinder extends Binder {
        AudioCallbackBinder() {
            attachInterface(null, AUDIO_CALLBACK_DESCRIPTOR);
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(AUDIO_CALLBACK_DESCRIPTOR);
                return true;
            }
            if (code == 1) {
                data.enforceInterface(AUDIO_CALLBACK_DESCRIPTOR);
                byte[] pcm = data.createByteArray();
                int source = data.readInt();
                int sampleRate = data.readInt();
                int channels = data.readInt();
                int format = data.readInt();
                if (listener != null) {
                    listener.onAudio(pcm, source, sampleRate, channels, format);
                }
            } else if (code == 2) {
                data.enforceInterface(AUDIO_CALLBACK_DESCRIPTOR);
                int source = data.readInt();
                int state = data.readInt();
                if (listener != null) {
                    listener.onAudioState(source, state);
                }
            } else {
                return super.onTransact(code, data, reply, flags);
            }
            if (reply != null) {
                reply.writeNoException();
            }
            return true;
        }
    }
}
