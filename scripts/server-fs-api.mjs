#!/usr/bin/env node
/**
 * Logseq Filesystem API Server
 * 
 * This server provides filesystem API endpoints for Logseq web application
 * when running in server mode with LOGSEQ_SERVER_GRAPH_DIR set.
 * 
 * Usage:
 *   LOGSEQ_SERVER_GRAPH_DIR=/path/to/graphs node scripts/server-fs-api.mjs
 * 
 * Or with environment variable:
 *   export LOGSEQ_SERVER_GRAPH_DIR=/path/to/graphs
 *   node scripts/server-fs-api.mjs
 */

import express from 'express';
import fs from 'fs';
import fsExtra from 'fs-extra';
import path from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const app = express();
const PORT = process.env.PORT || 3000;
const GRAPH_DIR = process.env.LOGSEQ_SERVER_GRAPH_DIR;

if (!GRAPH_DIR) {
  console.error('Error: LOGSEQ_SERVER_GRAPH_DIR environment variable is not set');
  process.exit(1);
}

// Ensure graph directory exists
if (!fs.existsSync(GRAPH_DIR)) {
  console.warn(`Warning: Graph directory does not exist: ${GRAPH_DIR}`);
  try {
    fsExtra.ensureDirSync(GRAPH_DIR);
    console.log(`Created graph directory: ${GRAPH_DIR}`);
  } catch (error) {
    console.error(`Error creating graph directory: ${error.message}`);
    process.exit(1);
  }
}

// Middleware
app.use(express.json());
app.use(express.raw({ type: 'application/octet-stream', limit: '100mb' }));

// CORS middleware for cross-origin requests
app.use((req, res, next) => {
  res.header('Access-Control-Allow-Origin', '*');
  res.header('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  res.header('Access-Control-Allow-Headers', 'Content-Type');
  if (req.method === 'OPTIONS') {
    return res.sendStatus(200);
  }
  next();
});

// Error handler for unsupported methods
app.use('/api/fs', (req, res, next) => {
  if (req.method !== 'POST') {
    return res.status(405).json({ 
      error: 'Method not allowed', 
      message: `${req.method} is not supported. Use POST for all /api/fs endpoints.`,
      requestedMethod: req.method,
      path: req.path
    });
  }
  next();
});

// Helper functions
function normalizePath(inputPath) {
  if (!inputPath) return null;
  
  // Handle absolute paths - if it's already within GRAPH_DIR, use it directly
  // Otherwise, resolve relative to GRAPH_DIR
  let resolved;
  const graphDirResolved = path.resolve(GRAPH_DIR);
  
  if (path.isAbsolute(inputPath)) {
    resolved = path.resolve(inputPath);
  } else {
    resolved = path.resolve(graphDirResolved, inputPath);
  }
  
  // Normalize paths for comparison (handle trailing slashes, etc.)
  const normalizedResolved = path.normalize(resolved) + (resolved.endsWith(path.sep) ? '' : '');
  const normalizedGraphDir = path.normalize(graphDirResolved) + path.sep;
  
  // Ensure the resolved path is within GRAPH_DIR for security
  if (!normalizedResolved.startsWith(normalizedGraphDir) && normalizedResolved !== path.normalize(graphDirResolved)) {
    throw new Error(`Path outside graph directory: ${inputPath} (resolved: ${resolved}, graphDir: ${graphDirResolved})`);
  }
  
  return resolved;
}

function fixWinPath(filePath) {
  // Convert Windows backslashes to forward slashes (similar to Electron handler)
  if (process.platform === 'win32') {
    return filePath.replace(/\\/g, '/');
  }
  return filePath;
}

function fsStatToClj(filePath) {
  try {
    const stats = fs.statSync(filePath);
    return {
      type: stats.isDirectory() ? 'dir' : 'file',
      size: stats.size,
      mtime: stats.mtimeMs,
      ctime: stats.ctimeMs
    };
  } catch (error) {
    throw new Error(`stat failed: ${error.message}`);
  }
}

function readFileContent(filePath) {
  try {
    if (!fs.existsSync(filePath)) {
      throw new Error('File does not exist');
    }
    return fs.readFileSync(filePath, 'utf8');
  } catch (error) {
    throw new Error(`readFile failed: ${error.message}`);
  }
}

function getFiles(dirPath) {
  const result = [];
  
  function traverse(currentPath) {
    try {
      const entries = fs.readdirSync(currentPath, { withFileTypes: true });
      
      for (const entry of entries) {
        const fullPath = path.join(currentPath, entry.name);
        const stat = fs.statSync(fullPath);
        
        if (stat.isDirectory()) {
          traverse(fullPath);
        } else {
          try {
            const content = readFileContent(fullPath);
            result.push({
              path: fixWinPath(fullPath),
              content: content,
              stat: {
                type: 'file',
                size: stat.size,
                mtime: stat.mtimeMs
              }
            });
          } catch (error) {
            // Skip files that can't be read
            console.warn(`Skipping file ${fullPath}: ${error.message}`);
          }
        }
      }
    } catch (error) {
      throw new Error(`getFiles failed: ${error.message}`);
    }
  }
  
  traverse(dirPath);
  return result;
}

function readdirRecursive(dirPath) {
  const result = [];
  
  function traverse(currentPath) {
    try {
      const entries = fs.readdirSync(currentPath, { withFileTypes: true });
      
      for (const entry of entries) {
        // Skip hidden files and symbolic links (similar to Electron behavior)
        if (entry.name.startsWith('.') || entry.isSymbolicLink()) {
          continue;
        }
        
        const fullPath = path.join(currentPath, entry.name);
        
        if (entry.isDirectory()) {
          traverse(fullPath);
        } else {
          result.push(fixWinPath(fullPath));
        }
      }
    } catch (error) {
      throw new Error(`readdir failed: ${error.message}`);
    }
  }
  
  traverse(dirPath);
  return result;
}

// API Routes

app.post('/api/fs/mkdir', (req, res) => {
  try {
    const { dir } = req.body;
    const dirPath = normalizePath(dir);
    fs.mkdirSync(dirPath, { recursive: false });
    res.json({ success: true });
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

app.post('/api/fs/mkdir-recur', (req, res) => {
  try {
    const { dir } = req.body;
    const dirPath = normalizePath(dir);
    fsExtra.ensureDirSync(dirPath);
    res.json({ success: true });
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

app.post('/api/fs/readdir', (req, res) => {
  try {
    const { dir } = req.body;
    const dirPath = normalizePath(dir);
    const files = readdirRecursive(dirPath);
    res.json(files);
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

app.post('/api/fs/readFile', (req, res) => {
  try {
    const { path: filePath } = req.body;
    const normalizedPath = normalizePath(filePath);
    const content = readFileContent(normalizedPath);
    // Return content as JSON string (frontend handles both string and object formats)
    res.json(content);
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

app.post('/api/fs/writeFile', (req, res) => {
  try {
    const { repo, path: filePath, content } = req.body;
    const normalizedPath = normalizePath(filePath);
    
    // Ensure parent directory exists
    const parentDir = path.dirname(normalizedPath);
    fsExtra.ensureDirSync(parentDir);
    
    // Write file
    fs.writeFileSync(normalizedPath, content, 'utf8');
    
    // Get file stats for response
    const stats = fsStatToClj(normalizedPath);
    res.json(stats);
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

app.post('/api/fs/stat', (req, res) => {
  try {
    const { path: filePath } = req.body;
    const normalizedPath = normalizePath(filePath);
    const stats = fsStatToClj(normalizedPath);
    res.json(stats);
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

app.post('/api/fs/unlink', (req, res) => {
  try {
    const repoDir = req.body['repo-dir'];
    const filePath = req.body.path;
    const normalizedPath = normalizePath(filePath);
    const normalizedRepoDir = normalizePath(repoDir);
    
    // Move to recycle directory instead of deleting
    const recycleDir = path.join(normalizedRepoDir, 'logseq', '.recycle');
    fsExtra.ensureDirSync(recycleDir);
    
    const fileName = path.basename(normalizedPath).replace(/[/\\]/g, '_');
    const recyclePath = path.join(recycleDir, fileName);
    
    fs.renameSync(normalizedPath, recyclePath);
    res.json({ success: true, recyclePath });
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

app.post('/api/fs/rename', (req, res) => {
  try {
    const oldPath = req.body['old-path'];
    const newPath = req.body['new-path'];
    const normalizedOldPath = normalizePath(oldPath);
    const normalizedNewPath = normalizePath(newPath);
    
    // Ensure parent directory exists
    const parentDir = path.dirname(normalizedNewPath);
    fsExtra.ensureDirSync(parentDir);
    
    fs.renameSync(normalizedOldPath, normalizedNewPath);
    res.json({ success: true });
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

app.post('/api/fs/copyFile', (req, res) => {
  try {
    const repo = req.body.repo;
    const oldPath = req.body['old-path'];
    const newPath = req.body['new-path'];
    const normalizedOldPath = normalizePath(oldPath);
    const normalizedNewPath = normalizePath(newPath);
    
    // Ensure parent directory exists
    const parentDir = path.dirname(normalizedNewPath);
    fsExtra.ensureDirSync(parentDir);
    
    fsExtra.copySync(normalizedOldPath, normalizedNewPath, { overwrite: true });
    res.json({ success: true });
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

app.post('/api/fs/getFiles', (req, res) => {
  try {
    const { path: dirPath } = req.body;
    const normalizedDirPath = normalizePath(dirPath);
    const files = getFiles(normalizedDirPath);
    res.json({ path: fixWinPath(normalizedDirPath), files });
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

app.post('/api/fs/openDir', (req, res) => {
  try {
    // For server mode, we don't have a dialog, so we return an error
    // or use a default directory. The frontend should handle this.
    res.status(400).json({ error: 'openDir not supported in server mode' });
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

app.post('/api/fs/backupDbFile', (req, res) => {
  try {
    const repoDir = req.body['repo-dir'];
    const filePath = req.body.path;
    const dbContent = req.body['db-content'];
    const newContent = req.body.content;
    
    // Simple backup logic - store in backup directory
    if (dbContent && newContent && dbContent !== newContent) {
      const normalizedRepoDir = normalizePath(repoDir);
      const backupDir = path.join(normalizedRepoDir, 'logseq', 'backup');
      fsExtra.ensureDirSync(backupDir);
      
      const fileName = path.basename(filePath).replace(/[/\\]/g, '_');
      const backupPath = path.join(backupDir, fileName);
      
      fs.writeFileSync(backupPath, dbContent, 'utf8');
      res.json({ success: true, backupPath });
    } else {
      res.json({ success: true });
    }
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

// Health check endpoint
app.get('/health', (req, res) => {
  res.json({ 
    status: 'ok', 
    graphDir: GRAPH_DIR,
    exists: fs.existsSync(GRAPH_DIR)
  });
});

// Test endpoint to verify server is working (for debugging)
app.get('/api/fs/test', (req, res) => {
  res.json({ 
    message: 'Logseq Filesystem API Server is running',
    graphDir: GRAPH_DIR,
    note: 'All /api/fs/* endpoints require POST method with JSON body',
    example: {
      endpoint: '/api/fs/getFiles',
      method: 'POST',
      body: { path: GRAPH_DIR }
    }
  });
});

// Start server
app.listen(PORT, () => {
  console.log(`Logseq Filesystem API Server running on port ${PORT}`);
  console.log(`Graph directory: ${GRAPH_DIR}`);
  console.log(`Access the API at http://localhost:${PORT}/api/fs/*`);
});

