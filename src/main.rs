#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::cell::Cell;
use std::path::PathBuf;
use std::rc::Rc;

use tao::{
    dpi::LogicalSize,
    event::{ElementState, Event, WindowEvent},
    event_loop::{ControlFlow, EventLoop},
    keyboard::Key,
    window::{Fullscreen, WindowBuilder},
};
use wry::http::Request;
use wry::WebViewBuilder;

const DEFAULT_PORT: u16 = 8765;
const PORT_FILE: &str = "foton-api-bridge.port";
const ICON_SIZE: u32 = 64;

// wry does not propagate the DOM Fullscreen API to the native window on Windows or
// Linux (tauri-apps/wry#1113). This script shims the API: it keeps the page's own
// fullscreenElement/fullscreenchange logic working and forwards the request to the
// host over IPC, which flips the tao window into native fullscreen.
const FULLSCREEN_SCRIPT: &str = r#"
(function () {
  if (window.__fotonFullscreenShim) { return; }
  window.__fotonFullscreenShim = true;
  var active = false;
  function emitChange() {
    document.dispatchEvent(new Event('fullscreenchange'));
  }
  Element.prototype.requestFullscreen = function () {
    active = true;
    emitChange();
    window.ipc.postMessage('foton-fullscreen:on');
    return Promise.resolve();
  };
  Document.prototype.exitFullscreen = function () {
    active = false;
    emitChange();
    window.ipc.postMessage('foton-fullscreen:off');
    return Promise.resolve();
  };
  Object.defineProperty(document, 'fullscreenElement', {
    configurable: true,
    get: function () { return active ? document.documentElement : null; }
  });
  Object.defineProperty(document, 'fullscreenEnabled', {
    configurable: true,
    get: function () { return true; }
  });
})();
"#;

fn main() -> wry::Result<()> {
    let url = resolve_url();

    let event_loop = EventLoop::new();
    let window = Rc::new(
        WindowBuilder::new()
            .with_title("foton frontend shell")
            .with_inner_size(LogicalSize::new(1280.0, 720.0))
            .with_min_inner_size(LogicalSize::new(640.0, 360.0))
            .with_window_icon(window_icon())
            .build(&event_loop)
            .expect("failed to create window"),
    );
    window.set_background_color(Some((0, 0, 0, 255)));

    let win = window.clone();
    // Native fullscreen state, kept in step with the DOM shim. Owned by the
    // IPC handler; the event loop reads it to decide whether ESC should exit.
    let is_fullscreen = Rc::new(Cell::new(false));
    let fs_signal = is_fullscreen.clone();
    let builder = WebViewBuilder::new()
        .with_url(url)
        .with_initialization_script(FULLSCREEN_SCRIPT)
        .with_ipc_handler(move |req: Request<String>| match req.body().as_str() {
            "foton-fullscreen:on" => {
                win.set_fullscreen(Some(Fullscreen::Borderless(None)));
                fs_signal.set(true);
            }
            "foton-fullscreen:off" => {
                win.set_fullscreen(None);
                fs_signal.set(false);
            }
            _ => {}
        });

    #[cfg(not(target_os = "linux"))]
    let webview = builder.build(&*window)?;

    #[cfg(target_os = "linux")]
    let webview = {
        use tao::platform::unix::WindowExtUnix;
        use wry::WebViewBuilderExtUnix;
        builder.build_gtk(window.gtk_window())?
    };

    event_loop.run(move |event, _window_target, control_flow| {
        *control_flow = ControlFlow::Wait;
        // ESC exits native fullscreen through the page's own exitFullscreen
        // shim, so the DOM fullscreen state (toggle icon) never desyncs.
        if let Event::WindowEvent {
            event:
                WindowEvent::KeyboardInput {
                    event: ref key,
                    is_synthetic: false,
                    ..
                },
            ..
        } = event
        {
            if is_fullscreen.get() && key.state == ElementState::Pressed && key.logical_key == Key::Escape
            {
                let _ = webview.evaluate_script("document.exitFullscreen()");
            }
        }
        if let Event::WindowEvent { event: WindowEvent::CloseRequested, .. } = event {
            *control_flow = ControlFlow::Exit;
        }
    });
}

fn window_icon() -> Option<tao::window::Icon> {
    const RGBA: &[u8] = include_bytes!("../assets/icon.rgba");
    tao::window::Icon::from_rgba(RGBA.to_vec(), ICON_SIZE, ICON_SIZE).ok()
}

fn resolve_url() -> String {
    let args: Vec<String> = std::env::args().collect();

    if let Some(i) = args.iter().position(|a| a == "--url") {
        if let Some(u) = args.get(i + 1) {
            return u.clone();
        }
    }
    if let Some(i) = args.iter().position(|a| a == "--port") {
        if let Some(p) = args.get(i + 1).and_then(|p| p.parse::<u16>().ok()) {
            return localhost(p);
        }
    }
    if let Some(dir) = std::env::current_exe().ok().and_then(|p| p.parent().map(PathBuf::from)) {
        if let Ok(txt) = std::fs::read_to_string(dir.join(PORT_FILE)) {
            if let Ok(p) = txt.trim().parse::<u16>() {
                return localhost(p);
            }
        }
    }
    localhost(DEFAULT_PORT)
}

fn localhost(port: u16) -> String {
    format!("http://127.0.0.1:{port}/")
}
