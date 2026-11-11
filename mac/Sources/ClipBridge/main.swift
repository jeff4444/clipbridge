import AppKit

// Used by uninstall.sh: only the app itself can remove its own login item.
if CommandLine.arguments.contains("--unregister-login-item") {
    LoginItem.unregister()
    Log.flush()
    exit(0)
}

let app = NSApplication.shared
let delegate = AppDelegate()
app.delegate = delegate
app.setActivationPolicy(.accessory) // menu-bar only: no Dock icon, no window to close
app.run()
