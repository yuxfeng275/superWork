import AppKit

// 菜单栏常驻程序：没有 Dock 图标、没有主窗口，只有 NSStatusItem。
let application = NSApplication.shared
let delegate = AppDelegate()
application.delegate = delegate
application.setActivationPolicy(.accessory)
application.run()
