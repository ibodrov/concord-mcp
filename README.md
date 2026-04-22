# Concord MCP Server Plugin

Concord MCP Server Plugin is a `concord-server` plugin that exposes Concord operations
through the Model Context Protocol (MCP), including process inspection, process log access,
process submission, and CRUD-style operations for Concord organizations, projects,
repositories, secrets, inventories, JSON stores, and related resources.

The plugin uses regular Concord authentication and authorization. MCP clients can only access
Concord resources allowed by the Concord user, API key, or session credentials used for the
request.

## Configuration

Optional system properties:

- `concord.mcp.allowedOrigins` - comma-separated origin allowlist.
- `concord.mcp.trustedForwardedProxies` - comma-separated proxy remote addresses whose
  `X-Forwarded-*` headers may be used for origin validation.
- `concord.mcp.maxConcurrentLogStreams` - maximum concurrent streaming log requests, default `8`.
- `concord.mcp.defaultStreamDurationMillis` - default follow duration, default `30000`.
- `concord.mcp.maxStreamDurationMillis` - maximum follow duration, default `60000`.
