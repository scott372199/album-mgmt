package top.chenyouyi.album.mgmt.web;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ManagementHomeController {
    private static final String HOME = """
            <!doctype html>
            <html lang="zh-CN">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>个人相册管理平台</title>
              <style>
                :root { color-scheme: light; font-family: system-ui, sans-serif; }
                body { max-width: 60rem; margin: 0 auto; padding: 3rem 1.25rem; color: #172033; background: #f7f8fb; }
                main { padding: 2rem; border: 1px solid #e3e7ef; border-radius: 1rem; background: white; box-shadow: 0 1rem 3rem #17203312; }
                h1 { margin-top: 0; } a { color: #2457c5; } pre { overflow: auto; padding: 1rem; border-radius: .6rem; background: #f1f3f7; }
              </style>
            </head>
            <body>
              <main>
                <h1>个人相册管理平台</h1>
                <p>V5 read-only management surface / 只读管理平台</p>
                <p><a href="/actuator/health">Health</a> · <a href="/api/management/snapshot">Management snapshot</a></p>
                <pre id="snapshot">Loading snapshot...</pre>
              </main>
              <script>
                fetch("/api/management/snapshot").then(r => r.json())
                  .then(data => { document.querySelector("#snapshot").textContent = JSON.stringify(data, null, 2); })
                  .catch(() => { document.querySelector("#snapshot").textContent = "Snapshot unavailable"; });
              </script>
            </body>
            </html>
            """;

    @GetMapping(value = {"/", "/index.html"}, produces = MediaType.TEXT_HTML_VALUE)
    public String home() {
        return HOME;
    }
}
