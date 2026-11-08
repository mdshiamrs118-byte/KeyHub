# KeyHub (test build 0.6)

OpenRouter + Gemini. Local proxy on 127.0.0.1:8787, multi-key failover per provider,
usage history, balances, model browser.

Build: push to GitHub, Actions tab -> "Android Build" -> download the KeyHub-debug-apk artifact.

Use in other apps:
  Base URL: http://127.0.0.1:8787/v1          (model gemini-* -> Gemini, others -> OpenRouter)
  Gemini-only URL: http://127.0.0.1:8787/gemini/v1   (also native /v1beta/... paths)
  API key : the universal key shown on the Home tab
