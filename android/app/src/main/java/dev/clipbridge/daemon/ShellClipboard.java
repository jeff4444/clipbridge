package dev.clipbridge.daemon;

import android.content.ClipData;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Talks to the system clipboard service directly, as the shell package.
 *
 * Ordinary apps cannot read the clipboard or receive change events unless they are in the
 * foreground. The shell identity holds READ_CLIPBOARD_IN_BACKGROUND, so a process started with
 * `app_process` over adb gets real change callbacks and can read at any time (while unlocked).
 *
 * IClipboard is a hidden interface whose signatures drift between Android releases, so calls are
 * resolved by name and arguments are filled in by parameter type.
 */
final class ShellClipboard {
    interface Listener {
        void onClipboardChanged();
    }

    private static final String PACKAGE = "com.android.shell";
    private static final String LISTENER_DESCRIPTOR = "android.content.IOnPrimaryClipChangedListener";

    private volatile Object service;
    private Listener listener;
    private Object listenerProxy;

    ShellClipboard() throws Exception {
        connect();
    }

    private void connect() throws Exception {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        IBinder binder = (IBinder) sm.getMethod("getService", String.class).invoke(null, "clipboard");
        if (binder == null) throw new IllegalStateException("clipboard service not available");
        service = Class.forName("android.content.IClipboard$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, binder);
        binder.linkToDeath(this::onServiceDied, 0);
    }

    /** system_server restarted (soft reboot): reconnect and re-register. */
    private void onServiceDied() {
        Log.i("clipboard service died, reconnecting");
        new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(3000);
                    connect();
                    if (listenerProxy != null) call("addPrimaryClipChangedListener", listenerProxy);
                    Log.i("clipboard service reconnected");
                    return;
                } catch (Throwable t) {
                    // keep trying
                }
            }
        }, "clipboard-reconnect").start();
    }

    ClipData get() {
        try {
            return (ClipData) call("getPrimaryClip");
        } catch (Throwable t) {
            Log.e("getPrimaryClip failed", t);
            return null;
        }
    }

    boolean set(ClipData clip) {
        try {
            call("setPrimaryClip", clip);
            return true;
        } catch (Throwable t) {
            Log.e("setPrimaryClip failed", t);
            return false;
        }
    }

    void listen(Listener l) throws Exception {
        listener = l;
        final Binder binder = new Binder() {
            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                if (code == IBinder.FIRST_CALL_TRANSACTION) { // dispatchPrimaryClipChanged()
                    Listener cb = listener;
                    if (cb != null) cb.onClipboardChanged();
                    return true;
                }
                return super.onTransact(code, data, reply, flags);
            }
        };
        binder.attachInterface(null, LISTENER_DESCRIPTOR);
        Class<?> iface = Class.forName(LISTENER_DESCRIPTOR);
        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "asBinder":
                    return binder;
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                case "toString":
                    return "ClipBridgeListener";
                default:
                    return null;
            }
        };
        listenerProxy = Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, handler);
        call("addPrimaryClipChangedListener", listenerProxy);
    }

    private Object call(String name, Object... special) throws Exception {
        Object svc = service;
        Method target = null;
        for (Method m : svc.getClass().getMethods()) {
            if (m.getName().equals(name)) {
                target = m;
                break;
            }
        }
        if (target == null) throw new NoSuchMethodException("IClipboard." + name);
        Class<?>[] types = target.getParameterTypes();
        Object[] args = new Object[types.length];
        int strings = 0;
        for (int i = 0; i < types.length; i++) {
            Class<?> t = types[i];
            Object match = null;
            for (Object s : special) {
                if (s != null && !(s instanceof String) && t.isInstance(s)) match = s;
            }
            if (match != null) args[i] = match;
            else if (t == String.class) args[i] = (strings++ == 0) ? PACKAGE : null; // package, then attribution tag
            else if (t == int.class) args[i] = 0; // user 0, default device
            else if (t == boolean.class) args[i] = false;
            else args[i] = null;
        }
        try {
            return target.invoke(svc, args);
        } catch (InvocationTargetException e) {
            Throwable c = e.getCause();
            if (c instanceof Exception) throw (Exception) c;
            throw e;
        }
    }
}
