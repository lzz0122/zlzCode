# CodeAgent

CodeAgent is being refactored into a single Git repository with independent application packages. Stage 0 currently contains the React web package; the Spring Boot backend is integrated through its own backend task branch.

## Web

Requirements: Node.js and pnpm 11.7.0.

```powershell
cd web
pnpm install --frozen-lockfile
pnpm build
pnpm dev
```

The Vite development server listens on `http://127.0.0.1:5173`.
