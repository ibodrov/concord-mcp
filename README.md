# Concord MCP Server Plugin

Concord MCP Server Plugin is a `concord-server` plugin that exposes Concord operations
through the Model Context Protocol (MCP), including process inspection, process log access,
process submission, and CRUD-style operations for Concord organizations, projects,
repositories, secrets, inventories, JSON stores, and related resources.

The plugin uses regular Concord authentication and authorization. MCP clients can only access
Concord resources allowed by the Concord user, API key, or session credentials used for the
request.
