# CodeAgent

CodeAgent is being refactored into a single Git repository with two independent application packages:

- `backend`: Java 21, Spring Boot, and Maven.
- `web`: React, TypeScript, Vite, and pnpm.

The packages keep separate dependency, build, and test entry points. This repository does not use Git submodules, Maven multi-modules, or a root pnpm workspace.

## Web

Requirements: Node.js and pnpm 11.7.0.

```powershell
cd web
pnpm install --frozen-lockfile
pnpm build
pnpm dev
```

The Vite development server listens on `http://127.0.0.1:5173`.

## Backend

Requirements: Java 21. Maven is provided by the repository wrapper.

```powershell
cd backend
.\mvnw.cmd clean package
.\mvnw.cmd spring-boot:run
```

The backend listens on `http://127.0.0.1:8000`. Its stage 0 health endpoint is:

```text
GET http://127.0.0.1:8000/health
{"status":"ok"}
```

Session files are stored by default in the repository root `sessions/` directory. Set
`CODEAGENT_SESSION_DIRECTORY` before starting the backend to use another directory.

Stage 1 adds the OpenAI-compatible model and single-turn endpoints. The Vite development server proxies API requests to `http://127.0.0.1:8000` by default; set `VITE_AGENT_API_URL` to that address when calling the backend directly.
