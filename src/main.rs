#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::cell::Cell;
use std::net::{TcpStream, ToSocketAddrs};
use std::path::PathBuf;
use std::rc::Rc;
use std::time::{Duration, Instant};

use tao::{
    dpi::LogicalSize,
    event::{Event, WindowEvent},
    event_loop::{ControlFlow, EventLoop},
    window::{Fullscreen, WindowBuilder},
};
use wry::http::{Request, Uri};
use wry::{PageLoadEvent, WebViewBuilder};

const DEFAULT_PORT: u16 = 8765;
const PORT_FILE: &str = "foton-api-bridge.port";
const ICON_SIZE: u32 = 64;
const TITLE: &str = "foton frontend shell";
// What startup may spend proving the server is there, and how long a load may
// run before the window title admits it is still waiting.
const PROBE_BUDGET: Duration = Duration::from_secs(2);
const SLOW_LOAD_AFTER: Duration = Duration::from_secs(10);

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
  // ESC leaves native fullscreen through the same off path as the page's own
  // exitFullscreen. Handled in page JS because on Windows the WebView2 control
  // holds keyboard focus, so the tao window never sees the ESC key event.
  document.addEventListener('keydown', function (e) {
    if (active && e.key === 'Escape') {
      active = false;
      emitChange();
      window.ipc.postMessage('foton-fullscreen:off');
    }
  });
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
    // Per-origin least privilege: only the origin the operator configured gets
    // the IPC bridge. The address is chosen at runtime by design, so this gates
    // on that choice instead of on a host allow-list.
    let origin = origin_of(&url);
    let label = origin
        .as_ref()
        .map(|(scheme, authority)| format!("{scheme}://{authority}"))
        .unwrap_or_else(|| url.clone());

    let event_loop = EventLoop::new();
    let window = Rc::new(
        WindowBuilder::new()
            .with_title(TITLE)
            .with_inner_size(LogicalSize::new(1280.0, 720.0))
            .with_min_inner_size(LogicalSize::new(640.0, 360.0))
            .with_window_icon(Some(window_icon()))
            .build(&event_loop)
            .expect("failed to create window"),
    );
    window.set_background_color(Some((0, 0, 0, 255)));

    // wry 0.56 reports only Started and Finished, never a failure, so a dead
    // server is caught by probing the socket before the webview asks for the
    // page, and a server that accepts but never answers by the title suffix.
    let reachable = probe(&url);

    let load_started = Rc::new(Cell::new(None));
    let reported_slow = Rc::new(Cell::new(false));
    let started_cell = load_started.clone();
    let slow_cell = reported_slow.clone();

    let win = window.clone();
    let builder = WebViewBuilder::new()
        .with_initialization_script(FULLSCREEN_SCRIPT)
        .with_on_page_load_handler(move |event, _url| match event {
            PageLoadEvent::Started => {
                started_cell.set(Some(Instant::now()));
                slow_cell.set(false);
            }
            PageLoadEvent::Finished => started_cell.set(None),
        })
        .with_ipc_handler(move |req: Request<String>| {
            if !from_origin(&origin, &req) {
                return;
            }
            match req.body().as_str() {
                "foton-fullscreen:on" => win.set_fullscreen(Some(Fullscreen::Borderless(None))),
                "foton-fullscreen:off" => win.set_fullscreen(None),
                _ => {}
            }
        });

    let builder = if reachable {
        builder.with_url(url)
    } else {
        builder.with_html(error_page(&url, "Server unreachable"))
    };

    #[cfg(not(target_os = "linux"))]
    let _webview = builder.build(&*window)?;

    #[cfg(target_os = "linux")]
    let _webview = {
        use tao::platform::unix::WindowExtUnix;
        use wry::WebViewBuilderExtUnix;
        builder.build_gtk(window.gtk_window())?
    };

    let slow_title = format!("{TITLE} - still waiting for {label}");
    let title_window = window.clone();
    event_loop.run(move |event, _target, control_flow| {
        *control_flow = ControlFlow::Wait;
        if let Event::WindowEvent {
            event: WindowEvent::CloseRequested,
            ..
        } = event
        {
            *control_flow = ControlFlow::Exit;
            return;
        }
        // Wait, not Poll: an idle shell never wakes. A load in flight wakes the
        // loop once at the threshold and then once a second until it lands.
        match load_started.get() {
            None => {
                if reported_slow.replace(false) {
                    title_window.set_title(TITLE);
                }
            }
            Some(started) => {
                let elapsed = started.elapsed();
                if elapsed >= SLOW_LOAD_AFTER {
                    if !reported_slow.replace(true) {
                        title_window.set_title(&slow_title);
                    }
                    *control_flow = ControlFlow::WaitUntil(Instant::now() + Duration::from_secs(1));
                } else {
                    *control_flow =
                        ControlFlow::WaitUntil(Instant::now() + (SLOW_LOAD_AFTER - elapsed));
                }
            }
        }
    });
}

const ICON_RGBA: &[u8] = include_bytes!("../assets/icon.rgba");
// tools/make_icon.py writes icon.rgba as WINDOW_SIZE * WINDOW_SIZE * 4 bytes and
// refuses to write a size the Rust const disagrees with, so a divergence is a
// compile error instead of a window that silently ships with no icon.
const _: () = assert!(
    ICON_RGBA.len() == (ICON_SIZE * ICON_SIZE * 4) as usize,
    "assets/icon.rgba does not match ICON_SIZE"
);

fn window_icon() -> tao::window::Icon {
    tao::window::Icon::from_rgba(ICON_RGBA.to_vec(), ICON_SIZE, ICON_SIZE)
        .expect("icon.rgba length is asserted at compile time")
}

// Per-origin least privilege: only the origin the operator configured gets the
// IPC bridge. The address is chosen at runtime by design, so this gates on that
// choice instead of on a host allow-list. wry hands the handler the sending
// document's URL on WebView2 (WebMessageReceivedEventArgs::Source), WKWebView
// (frameInfo.request().URL) and webkit2gtk (WebView::uri), so comparing scheme
// and authority is enough. On webkit2gtk that is the main frame's URL, so an
// iframe of another origin is reported as the main frame; the only thing it can
// reach is the fullscreen toggle.
fn origin_of(url: &str) -> Option<(String, String)> {
    let uri: Uri = url.parse().ok()?;
    let scheme = uri.scheme_str()?.to_ascii_lowercase();
    let authority = uri.authority()?.as_str().to_ascii_lowercase();
    Some((scheme, authority))
}

fn from_origin(origin: &Option<(String, String)>, req: &Request<String>) -> bool {
    let (Some((scheme, authority)), Some(req_scheme), Some(req_authority)) =
        (origin, req.uri().scheme_str(), req.uri().authority())
    else {
        return false;
    };
    scheme.eq_ignore_ascii_case(req_scheme)
        && authority.eq_ignore_ascii_case(req_authority.as_str())
}

fn socket_of(url: &str) -> Option<(String, u16)> {
    let uri: Uri = url.parse().ok()?;
    let authority = uri.authority()?;
    let port = authority.port_u16().unwrap_or(match uri.scheme_str()? {
        "https" => 443,
        _ => 80,
    });
    Some((authority.host().trim_matches(['[', ']']).to_string(), port))
}

fn probe(url: &str) -> bool {
    let Some((host, port)) = socket_of(url) else {
        return false;
    };
    let Ok(addrs) = (host.as_str(), port).to_socket_addrs() else {
        return false;
    };
    let deadline = Instant::now() + PROBE_BUDGET;
    for addr in addrs {
        let left = deadline.saturating_duration_since(Instant::now());
        if left.is_zero() {
            break;
        }
        if TcpStream::connect_timeout(&addr, left).is_ok() {
            return true;
        }
    }
    false
}

fn esc(text: &str) -> String {
    text.replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
}

fn error_page(url: &str, headline: &str) -> String {
    format!(
        "<!doctype html><meta charset=\"utf-8\"><title>{TITLE}</title>\
<style>body{{margin:0;height:100vh;display:flex;align-items:center;\
justify-content:center;background:#111;color:#eee;font:16px/1.6 system-ui,sans-serif}}\
main{{max-width:32rem;padding:2rem}}h1{{font-size:1.2rem;margin:0 0 .8rem}}\
code{{background:#222;padding:.1rem .35rem;border-radius:3px}}\
p{{color:#bbb;margin:.6rem 0}}</style>\
<main><h1>{headline}</h1><p>The shell could not load a page from</p>\
<p><code>{url}</code></p><p>Start the foton server, or aim the shell somewhere else with \
<code>--url &lt;url&gt;</code>, <code>--port &lt;n&gt;</code>, or a <code>{PORT_FILE}</code> \
file holding the port next to the exe.</p></main>",
        headline = esc(headline),
        url = esc(url),
    )
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
    if let Some(dir) = std::env::current_exe()
        .ok()
        .and_then(|p| p.parent().map(PathBuf::from))
    {
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

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::TcpListener;

    fn req(url: &str) -> Request<String> {
        Request::builder()
            .uri(url)
            .body(String::new())
            .unwrap_or_else(|e| panic!("test uri {url} should parse: {e:?}"))
    }

    #[test]
    fn origin_is_scheme_and_authority_lowercased() {
        assert_eq!(
            origin_of("http://LocalHost:5173/app"),
            Some(("http".into(), "localhost:5173".into()))
        );
        assert_eq!(
            origin_of("https://box.lan/"),
            Some(("https".into(), "box.lan".into()))
        );
    }

    #[test]
    fn origin_needs_scheme_and_authority() {
        assert_eq!(origin_of("/relative"), None);
        assert_eq!(origin_of("localhost:5173"), None);
        assert_eq!(origin_of(""), None);
    }

    #[test]
    fn ipc_allowed_only_for_the_configured_origin() {
        let origin = origin_of("http://localhost:5173/");
        let allowed = |u: &str| from_origin(&origin, &req(u));
        assert!(allowed("http://localhost:5173/"));
        assert!(allowed("http://localhost:5173/app/page?x=1"));
        assert!(allowed("http://LOCALHOST:5173/other"));
    }

    #[test]
    fn ipc_denied_for_other_origins() {
        let origin = origin_of("http://localhost:5173/");
        let denied = |u: &str| from_origin(&origin, &req(u));
        assert!(!denied("http://localhost:5174/"));
        assert!(!denied("https://localhost:5173/"));
        assert!(!denied("http://127.0.0.1:5173/"));
        assert!(!denied("http://evil.test/"));
        // same characters, different host: must not inherit the bridge
        assert!(!denied("http://localhost:5173.evil.test/"));
        assert!(!denied("http://localhost:5173@evil.test/"));
        assert!(!denied("http://evil.test/#http://localhost:5173/"));
    }

    #[test]
    fn senders_without_an_authority_never_reach_the_handler() {
        // wry builds the request with Request::builder().uri(source) and drops
        // it on error, so a non-hierarchical sender cannot become a request at
        // all. Asserted here so a future http crate change cannot turn this into
        // a silent gap in the gate below.
        for url in ["file:///etc/passwd", "data:text/html,<script>1</script>"] {
            assert!(
                Request::builder().uri(url).body(String::new()).is_err(),
                "{url} unexpectedly parsed as a request Uri"
            );
        }
    }

    #[test]
    fn ipc_denied_when_origin_is_unknown() {
        assert!(!from_origin(&None, &req("http://localhost:5173/")));
    }

    #[test]
    fn socket_defaults_per_scheme() {
        assert_eq!(socket_of("http://box.lan/"), Some(("box.lan".into(), 80)));
        assert_eq!(socket_of("https://box.lan/"), Some(("box.lan".into(), 443)));
        assert_eq!(
            socket_of("http://127.0.0.1:8080/x"),
            Some(("127.0.0.1".into(), 8080))
        );
        assert_eq!(socket_of("http://[::1]:8080/"), Some(("::1".into(), 8080)));
        assert_eq!(socket_of("nonsense"), None);
    }

    #[test]
    fn probe_finds_a_listening_socket() {
        let listener = TcpListener::bind("127.0.0.1:0").expect("bind loopback");
        let port = listener.local_addr().expect("addr").port();
        assert!(probe(&format!("http://127.0.0.1:{port}/")));
    }

    #[test]
    fn probe_reports_a_closed_port() {
        // Bind then drop, so the port is almost certainly free and refused
        // rather than filtered.
        let port = {
            let listener = TcpListener::bind("127.0.0.1:0").expect("bind loopback");
            listener.local_addr().expect("addr").port()
        };
        assert!(!probe(&format!("http://127.0.0.1:{port}/")));
    }

    #[test]
    fn probe_rejects_unusable_urls() {
        assert!(!probe("nonsense"));
        assert!(!probe("file:///etc/passwd"));
    }

    #[test]
    fn error_page_escapes_what_it_echoes() {
        let page = error_page("http://x/?a=1&b=<script>", "Server <unreachable>");
        assert!(page.contains("&amp;"));
        assert!(page.contains("&lt;script&gt;"));
        assert!(!page.contains("<script>"));
        assert!(page.contains("http://x/?a=1&amp;b=&lt;script&gt;"));
        assert!(page.contains("Server &lt;unreachable&gt;"));
        assert!(page.contains(PORT_FILE));
    }
}
