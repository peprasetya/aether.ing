<p align="center">
  <img src="src/main/webapp/images/Aether.ing.png" alt="Aether" width="96" />
</p>

<h1 align="center">Aether</h1>

<p align="center">
  An AI-native file editor and IDE that runs in the browser, backed by a self-hosted Java server.
</p>

Aether is a triple-pane workspace — **Explorer**, **Editor**, and **AI Assistant** — for browsing, editing, and chatting with an LLM about files that live on your local disk, a remote server over SSH, or Google Drive. Beyond text and code it opens images, video and 3D models in their own viewers, and its agents can generate all three.

There is no build framework, no ORM, and no bundler. It's plain Java, JSP, and JavaScript, deployed straight into a Jetty container.

## Features

- **Triple-pane workspace** — file explorer, CodeMirror-based code editor, and an AI chat pane, all in one page with no full reloads (a lightweight custom AJAX layer patches the DOM from server-rendered XML fragments).
- **Multiple storage backends** — a `FileProvider` abstraction unifies the local filesystem, SSH/SFTP servers, and Google Drive behind one interface, so the same explorer, editor, and AI tools work identically no matter where a file lives.
- **AI assistant with tool use** — agents (defined as JSON in `WEB-INF/agents/`) can read, write, patch, rename, move, copy, and search files, run commands, and ask the user for permission before any destructive action, streamed live over a WebSocket.
- **Background agent runs** — every AI turn is a run owned by the user, not by the browser tab: it keeps working when the tab is closed, can be followed, steered, paused or answered from any device, survives a server restart, and can notify you by Web Push. Autonomous task agents only finish through stop gates (todos done, the project's verify command, a reviewer model).
- **Image studio** — a layered image viewer (move, scale, rotate, merge) with AI generation and editing. Ticked images and the open canvas are numbered Image 1..N, shown to the chat model and sent to the image model in that order; `@file` mentions in the prompt resolve to those numbers.
- **Video and 3D studios** — agents that plan with you, refine the source picture first, then order a video clip or a 3D mesh from a model server such as hemera. A built-in player handles `mp4`/`m4v`/`webm`/`mov`; a three.js viewer opens `.glb` with PBR lighting, single-map views (base colour, normal, roughness, metallic, occlusion), vertex colours, a UV checker, a UV layout panel and wireframe.
- **Creative co-writer** — paragraph, variation, rephrase and suggestion commands for long-form writing. The cast is read from the project's `AGENTS.md` by a model and offered as a dropdown, so a passage can dwell on one character without changing the narration.
- **Bring your own model provider** — connect any number of OpenAI-compatible endpoints (Ollama, LM Studio, OpenRouter, Lemonade, hosted frontier APIs, ...) at once; every model from every provider shows up in a selector for its kind (text, image, video, 3D), labeled by provider. Settings for media models are generated from the parameter list the model server publishes.
- **Google Drive integration** — all your data (settings, chat sessions, and optionally the files themselves) can live in your own Google Drive.
- **Auto-save** — edits are debounced and buffered server-side in the session before being committed to the underlying file provider, so a dropped connection doesn't lose work.

## Tech stack

| Layer | Choice |
|---|---|
| Language | Java 25 |
| Web container | Jakarta EE 11 / Jetty 12.1 |
| Server-side views | JSP, rendered as XML fragments over a custom AJAX mechanism |
| Client | Vanilla JavaScript (`scriptlib.js`), CodeMirror for editing, three.js for the 3D viewer (both from a CDN) |
| Config/state | JSON files (`org.eclipse.jetty.util.ajax.JSON`), no database |
| SSH/SFTP | JSch 2.28.4 (mwiede fork) |
| AI backend | Any OpenAI-compatible `/v1/chat/completions` endpoint (Ollama, etc.) over a WebSocket bridge |
| Media backend | OpenAI-style `/v1/images`, `/v1/videos` and a matching `/v1/3d/generations` job API |

No Spring, no Maven/Gradle, no ORM.

## Architecture

Aether follows a small variant of the Front Controller / Command pattern:

1. **`Portal`** (a `WebFilter`) intercepts every request and routes it via **`CommandInitializer`**, which scans for `@CommandRegister` annotations on bean classes.
2. Each request is handled by a **`BeanObject`** subclass (e.g. `DocumentBean`, `BrowseBean`, `SettingBean`). Parameters are injected by `Portal` through public setters — beans never call `request.getParameter()` directly.
3. The response is JSP content wrapped as `text/xml`, containing `<replacehtml>` and `<command>` tags. `AjaxWrapper`/`AjaxModifier` handle CDATA-wrapping automatically.
4. The client (`scriptlib.js`) fires requests with `makeRequest(...)`, patches the returned fragments into the page, and manages view state (`showView('browser' | 'editor' | 'image' | 'video' | 'model')`).
5. File I/O goes through **`FileProvider`** implementations (`LocalFileProvider`, `SshFileProvider`, `GoogleDriveProvider`), resolved per-session via **`PathMap`** and the provider registry.
6. The AI assistant (**`AgentWorker`**) streams chat and tool calls from an OpenAI-compatible endpoint over **`AetherWebSocket`** (`/socket/aetherui`), executing tool calls with a permission-request round trip to the client before anything destructive happens. Each turn runs inside an **`AgentRun`** managed by **`RunManager`**, with a detached session so it outlives the browser.
7. Media goes through **`ImageWorker`** (synchronous image generation and editing, layer documents) and **`MediaWorker`** (asynchronous video and 3D jobs: start, poll, download, cancel). Results are saved into the project through the file provider; raw bytes reach the viewers through the `image` and `media` commands, the latter with HTTP byte ranges for seeking.

### Data persistence, by tier

| Store | Contents | Lifetime |
|---|---|---|
| `HttpSession` | Login identity (email, account id, admin flag) | Survives context restarts (Jetty session persistence) |
| In-memory cache | Registered storage providers, cached settings | Cleared on context restart |
| `.vibed` files (encrypted, on disk) | OAuth access/refresh tokens only | Survives full server restarts |
| `aether.ing.setting.json` (in the user's Google Drive) | Storage provider list, generated SSH keypair | User-level, provider-agnostic |
| `.vibedlog` files (encrypted, on disk) | Append-only event log of each agent run, for replay on any device and resume after a restart | 30 days after the run's last event |
| `AGENTS.md` + `aether.ing.json` (in the working directory) | Project overview fed to the AI; chat sessions, AI config and the extracted character list | Per-project |
| `<image>.layers.json` (beside an image) | Layer document of a layered image | Per-image |

## Getting started

There's no Maven/Gradle build — you'll need a Java 25 compiler and a Jakarta EE 11 compatible container.

1. Resolve the library dependencies referenced in `.classpath` (AspectJ RT, Jetty-home-12, JSch-2).
2. Build: compiled classes go to `src/main/webapp/WEB-INF/classes`.
3. Deploy the contents of `src/main/webapp` to a Jetty 12.1 (or other Jakarta EE 11) server. Runtime jars are expected on the server's own classpath, not bundled in `WEB-INF/lib`.
4. On first run, `SetupBean` walks you through initial admin configuration.
5. Add at least one OpenAI-compatible LLM provider (e.g. a local Ollama install) from **Settings → LLM Providers** to enable the AI assistant.
6. Optional: image, video and 3D generation need a provider that serves those models. A provider that reports each model's kind in `/v1/models` gets its models sorted into the right selector; otherwise the kind is guessed from the model name.

## License

MIT — see [LICENSE](LICENSE).
