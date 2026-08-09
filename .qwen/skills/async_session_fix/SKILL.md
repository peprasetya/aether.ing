---
name: Async Session Tracking in Background Threads
description: Capture HttpSession outside async scope and re-fetch via SessionTracker inside background thread before session detaches
source: auto-skill
extracted_at: '2026-05-28T09:58:29.145Z'
---

## Problem
In `AgentWorker.java`, asynchronous workflows use `CompletableFuture.runAsync()` with sessions passed directly to background threads. When the parent request thread ends, the session detaches or becomes `null`, causing authorization and provider lookups to fail.

## Solution
### Two-Phase Session Handling
1. **Capture `session.getId()`** as a final local variable on the main thread (thread-safe since it's a String)
2. **Inside the async block**, fetch the live session context using `SessionTracker.getSessionById(httpSessionId)` - never use `session` directly in async code

### Pattern
```java
final String httpSessionId = session.getId();
CompletableFuture.runAsync(() -> {
  try {
    HttpSession liveSession = SessionTracker.getSessionById(httpSessionId);
    if (liveSession != null) {
      // use liveSession for all operations
    }
  } catch (Exception e) {
    e.printStackTrace();
  }
});
```

### Required Imports
```java
import jakarta.servlet.http.HttpSession;
```

### Apply To
- **`process()` method** (line ~269): `saveAiConfig()`
- **`generateTitle()` method** (line ~500): `updateSessionTitle()`
- **`runPsychologist()` method** (line ~1129): `updatePersonality()`

### Why This Works
- `session.getId()` is a thread-safe String getter
- `SessionTracker` is a static utility that holds sessions during their lifetime
- Background threads cannot hold a reference to the HTTP session - they must request context by ID
- Null checks prevent crashes if the session has already expired

### Don't Do
- ❌ `CompletableFuture.runAsync(() -> { saveAiConfig(session, ...); })`
- ❌ Keep references to `session` in static fields across threads
- ❌ Access `request.getSession()` inside async code

## Verification
After changes:
1. Compile with `../eclipseCompile`
2. Check for missing `SessionTracker` import
3. Test with session expiration scenarios