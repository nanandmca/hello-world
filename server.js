const express = require('express');
const { createProxyMiddleware } = require('http-proxy-middleware');

const app = express();
const PORT = process.env.PORT || 3000;

// Simple test route to verify the gateway service is working
app.get('/', (req, res) => {
    res.send("API Gateway is running!");
});

// Proxy route to target server
app.use('/api/auth/public-keys', createProxyMiddleware({
    target: 'http://localhost:3001',  // Targeting the test server at port 3001
    changeOrigin: true,               // Ensures the origin of the request is changed to the target
    pathRewrite: {
        '^/api/auth': '',  // Remove '/api/auth' prefix in the URL, forward to '/auth/public-keys'
    },
    onProxyReq: (proxyReq, req, res) => {
        console.log(`Proxying request to: ${proxyReq.href}`);
    },
    onError: (err, req, res) => {
        console.error(`Error in proxying request: ${err.message}`);
        res.status(502).json({ message: 'Bad Gateway: authentication service is down' });
    },
    onProxyRes: (proxyRes, req, res) => {
        console.log(`Received response with status ${proxyRes.statusCode}`);
    }
}));

app.listen(PORT, () => {
    console.log(`API Gateway running on port ${PORT}`);
});
