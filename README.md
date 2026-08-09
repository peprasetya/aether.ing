<p align="center">
  <img src="src/main/webapp/images/Aether.ing.png" alt="Aether" width="96" />
</p>

<h1 align="center">Aether</h1>

<p align="center">
  An AI-native file editor and IDE that runs in the browser, backed by a self-hosted Java server.
</p>

Aether is a triple-pane workspace — **Explorer**, **Editor**, and **AI Assistant** — for browsing, editing, and chatting with an LLM about files that live on your local disk, a remote server over SSH, or Google Drive.

There is no build framework, no ORM, and no bundler. It's plain Java, JSP, and JavaScript, deployed straight into a Jetty container.

## Features

- **Triple-pane workspace** — file explorer, CodeMirror-based code editor, and an AI chat pane, all in one page with no full reloads (a lightweight custom AJAX layer patches the DOM from server-rendered XML fragments).
- **Multiple storage backends** — a `FileProvider` abstraction unifies the local filesystem, SSH/SFTP servers, and Google Drive behind one interface, so the same explorer, editor, and AI tools work identically no matter where a file lives.
- **AI assistant with tool use** — agents (defined as JSON in `WEB-INF/agents/`) can read, write, patch, rename, move, copy, and search files, run commands, and ask the user for permission before any destructive action, streamed live over a WebSocket.
- **Bring your own model provider** — connect any number of OpenAI-compatible endpoints (Ollama, LM Studio, OpenRouter, Lemonade, hosted frontier APIs, ...) at once; every model from every provider shows up in one selector, labeled by provider.
- **Google Drive integration** — all your data (settings, chat sessions, and optionally the files themselves) can live in your own Google Drive.
- **Auto-save** — edits are debounced and buffered server-side in the session before being committed to the underlying file provider, so a dropped connection doesn't lose work.

## Tech stack

| Layer | Choice |
|---|---|
| Language | Java 25 |
| Web container | Jakarta EE 11 / Jetty 12.1 |
| Server-side views | JSP, rendered as XML fragments over a custom AJAX mechanism |
| Client | Vanilla JavaScript (`scriptlib.js`), CodeMirror for editing |
| Config/state | JSON files (`org.eclipse.jetty.util.ajax.JSON`), no database |
| SSH/SFTP | JSch 2.28.4 (mwiede fork) |
| AI backend | Any OpenAI-compatible `/v1/chat/completions` endpoint (Ollama, etc.) over a WebSocket bridge |

No Spring, no Maven/Gradle, no ORM.

## Architecture

Aether follows a small variant of the Front Controller / Command pattern:

1. **`Portal`** (a `WebFilter`) intercepts every request and routes it via **`CommandInitializer`**, which scans for `@CommandRegister` annotations on bean classes.
2. Each request is handled by a **`BeanObject`** subclass (e.g. `DocumentBean`, `BrowseBean`, `SettingBean`). Parameters are injected by `Portal` through public setters — beans never call `request.getParameter()` directly.
3. The response is JSP content wrapped as `text/xml`, containing `<replacehtml>` and `<command>` tags. `AjaxWrapper`/`AjaxModifier` handle CDATA-wrapping automatically.
4. The client (`scriptlib.js`) fires requests with `makeRequest(...)`, patches the returned fragments into the page, and manages view state (`showView('panel' | 'editor' | 'media')`).
5. File I/O goes through **`FileProvider`** implementations (`LocalFileProvider`, `SshFileProvider`, `GoogleDriveProvider`), resolved per-session via **`PathMap`** and the provider registry.
6. The AI assistant (**`AgentWorker`**) streams chat and tool calls from an OpenAI-compatible endpoint over **`AetherWebSocket`** (`/socket/aetherui`), executing tool calls with a permission-request round trip to the client before anything destructive happens.

### Data persistence, by tier

| Store | Contents | Lifetime |
|---|---|---|
| `HttpSession` | Login identity (email, account id, admin flag) | Survives context restarts (Jetty session persistence) |
| In-memory cache | Registered storage providers, cached settings | Cleared on context restart |
| `.vibed` files (encrypted, on disk) | OAuth access/refresh tokens only | Survives full server restarts |
| `aether.ing.setting.json` (in the user's Google Drive) | Storage provider list, generated SSH keypair | User-level, provider-agnostic |
| `AGENTS.md` + `aether.ing.json` (in the working directory) | Project overview fed to the AI, and chat sessions/config | Per-project |

## Getting started

There's no Maven/Gradle build — you'll need a Java 25 compiler and a Jakarta EE 11 compatible container.

1. Resolve the library dependencies referenced in `.classpath` (AspectJ RT, Jetty-home-12, JSch-2).
2. Build: compiled classes go to `src/main/webapp/WEB-INF/classes`.
3. Deploy the contents of `src/main/webapp` to a Jetty 12.1 (or other Jakarta EE 11) server. Runtime jars are expected on the server's own classpath, not bundled in `WEB-INF/lib`.
4. On first run, `SetupBean` walks you through initial admin configuration.
5. Add at least one OpenAI-compatible LLM provider (e.g. a local Ollama install) from **Settings → LLM Providers** to enable the AI assistant.

## License

MIT — see [LICENSE](LICENSE).
