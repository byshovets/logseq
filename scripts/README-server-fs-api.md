# Logseq Filesystem API Server

This server provides filesystem API endpoints for Logseq web application when running in server mode.

## Prerequisites

- Node.js (v18 or later)
- The `express` and `fs-extra` npm packages

## Installation

Install dependencies:

```bash
npm install express fs-extra
```

Or add to your `package.json`:

```json
{
  "dependencies": {
    "express": "^4.18.0",
    "fs-extra": "^11.0.0"
  }
}
```

## Usage

### Start the server:

```bash
LOGSEQ_SERVER_GRAPH_DIR=/path/to/your/graphs node scripts/server-fs-api.mjs
```

Or with environment variable:

```bash
export LOGSEQ_SERVER_GRAPH_DIR=/path/to/your/graphs
node scripts/server-fs-api.mjs
```

The server will start on port 3000 by default, or the port specified in the `PORT` environment variable.

### Configure the web application

Make sure the Logseq web application is configured to use the server:

1. Set `LOGSEQ_SERVER_GRAPH_DIR` in your environment or inject it into `public/index.html`
2. The frontend will automatically detect server mode and use HTTP filesystem backend
3. The server must be accessible at the same origin as the web app, or CORS must be configured

### Proxy Configuration

If the server runs on a different port than the web application, configure a reverse proxy (e.g., nginx) to forward `/api/fs/*` requests to this server.

Example nginx configuration:

```nginx
location /api/fs/ {
    proxy_pass http://localhost:3000/api/fs/;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
}
```

### Docker Deployment

You can run this server in a Docker container:

```dockerfile
FROM node:18-alpine
WORKDIR /app
COPY scripts/server-fs-api.mjs .
RUN npm install express fs-extra
ENV LOGSEQ_SERVER_GRAPH_DIR=/data/graphs
EXPOSE 3000
CMD ["node", "server-fs-api.mjs"]
```

## API Endpoints

All endpoints accept POST requests with JSON body:

- `POST /api/fs/mkdir` - Create directory
- `POST /api/fs/mkdir-recur` - Create directory recursively
- `POST /api/fs/readdir` - List directory contents recursively
- `POST /api/fs/readFile` - Read file content
- `POST /api/fs/writeFile` - Write file content
- `POST /api/fs/stat` - Get file/directory stats
- `POST /api/fs/unlink` - Delete file (moves to recycle)
- `POST /api/fs/rename` - Rename file/directory
- `POST /api/fs/copyFile` - Copy file
- `POST /api/fs/getFiles` - Get all files in directory with content
- `POST /api/fs/openDir` - Open directory (not supported in server mode)
- `POST /api/fs/backupDbFile` - Backup database file

## Security Notes

- The server restricts all file operations to paths within `LOGSEQ_SERVER_GRAPH_DIR`
- No authentication is implemented (as per requirements)
- For production use, you should:
  - Add authentication/authorization
  - Use HTTPS
  - Configure proper CORS policies
  - Set up rate limiting
  - Review and harden path validation

## Health Check

GET `/health` - Returns server status and graph directory information

