fn main() {
    #[cfg(windows)]
    {
        const VERSION: &str = "0.1.4.0";
        let mut res = winres::WindowsResource::new();
        res.set_icon("assets/icon.ico");
        res.set("ProductName", "foton-frontend-shell");
        res.set("FileDescription", "foton frontend shell");
        res.set("CompanyName", "foton");
        res.set("LegalCopyright", "MIT");
        res.set("OriginalFilename", "foton-frontend-shell.exe");
        res.set("ProductVersion", VERSION);
        res.set("FileVersion", VERSION);
        res.compile().expect("failed to compile Windows resources");
    }
}
