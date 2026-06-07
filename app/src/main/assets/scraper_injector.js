/**
 * Chorand API Scraper - JavaScript Injection Script
 *
 * Monkey-patches fetch() and XMLHttpRequest to intercept all network requests
 * and responses, bridging captured data back to Android via ScraperBridge.
 *
 * Also patches navigator.webdriver and window.chrome to reduce bot detection.
 */
(function () {
    'use strict';

    // ─── Anti-detection patches ────────────────────────────────────────────────

    // Suppress webdriver flag
    try {
        Object.defineProperty(navigator, 'webdriver', {
            get: () => false,
            configurable: true
        });
    } catch (e) {}

    // Stub window.chrome for sites that check it
    if (!window.chrome) {
        try {
            window.chrome = {
                app: { isInstalled: false },
                csi: function () {},
                loadTimes: function () {},
                runtime: {
                    PlatformOs: { MAC: 'mac', WIN: 'win', ANDROID: 'android', CROS: 'cros', LINUX: 'linux', OPENBSD: 'openbsd' },
                    PlatformArch: { ARM: 'arm', X86_32: 'x86-32', X86_64: 'x86-64' },
                    RequestUpdateCheckStatus: { THROTTLED: 'throttled', NO_UPDATE: 'no_update', UPDATE_AVAILABLE: 'update_available' },
                    OnInstalledReason: { INSTALL: 'install', UPDATE: 'update', CHROME_UPDATE: 'chrome_update', SHARED_MODULE_UPDATE: 'shared_module_update' },
                    OnRestartRequiredReason: { APP_UPDATE: 'app_update', OS_UPDATE: 'os_update', PERIODIC: 'periodic' }
                }
            };
        } catch (e) {}
    }

    // Stub plugins to appear as a normal browser
    try {
        Object.defineProperty(navigator, 'plugins', {
            get: () => {
                var plugins = [
                    { name: 'Chrome PDF Plugin', filename: 'internal-pdf-viewer', description: 'Portable Document Format' },
                    { name: 'Chrome PDF Viewer', filename: 'mhjfbmdgcfjbbpaeojofohoefgiehjai', description: '' },
                    { name: 'Native Client', filename: 'internal-nacl-plugin', description: '' }
                ];
                plugins.length = plugins.length;
                return plugins;
            }
        });
    } catch (e) {}

    // ─── Bridge helper ─────────────────────────────────────────────────────────

    function safeSend(method, payload) {
        try {
            if (window.ScraperBridge && typeof window.ScraperBridge[method] === 'function') {
                window.ScraperBridge[method](JSON.stringify(payload));
            }
        } catch (e) {
            // Silently fail — never break the page
        }
    }

    function truncateBody(body, maxLen) {
        if (!body) return null;
        var str = String(body);
        return str.length > maxLen ? str.substring(0, maxLen) + '...[TRUNCATED]' : str;
    }

    function headersToObject(headers) {
        if (!headers) return {};
        var obj = {};
        if (typeof headers.entries === 'function') {
            try {
                for (var pair of headers.entries()) {
                    obj[pair[0]] = pair[1];
                }
            } catch (e) {}
        } else if (typeof headers === 'object') {
            obj = Object.assign({}, headers);
        }
        return obj;
    }

    function extractRequestBody(body) {
        if (!body) return null;
        if (typeof body === 'string') return truncateBody(body, 8192);
        if (body instanceof FormData) {
            try {
                var parts = [];
                for (var pair of body.entries()) {
                    parts.push(pair[0] + '=' + pair[1]);
                }
                return parts.join('&');
            } catch (e) { return '[FormData]'; }
        }
        if (body instanceof URLSearchParams) return body.toString();
        if (body instanceof ArrayBuffer || ArrayBuffer.isView(body)) return '[Binary Data]';
        if (body instanceof Blob) return '[Blob]';
        return '[Unknown Body]';
    }

    // ─── Patch fetch() ─────────────────────────────────────────────────────────

    var _originalFetch = window.fetch;
    window.fetch = function (input, init) {
        var startTime = Date.now();
        var url = (input instanceof Request) ? input.url : String(input);
        var method = (init && init.method) || (input instanceof Request && input.method) || 'GET';

        // Merge custom headers
        init = init || {};
        var originalHeaders = (init.headers) || (input instanceof Request && input.headers);
        var requestHeaders = headersToObject(originalHeaders);
        if (window.ChorandCustomHeaders) {
            for (var key in window.ChorandCustomHeaders) {
                if (window.ChorandCustomHeaders.hasOwnProperty(key)) {
                    requestHeaders[key] = window.ChorandCustomHeaders[key];
                }
            }
        }
        init.headers = requestHeaders;

        var requestBody = extractRequestBody((init && init.body) || (input instanceof Request && input.body));

        // Report the outgoing request
        safeSend('onRequest', {
            initiator: 'fetch',
            url: url,
            method: method.toUpperCase(),
            requestHeaders: requestHeaders,
            requestBody: requestBody,
            timestamp: startTime
        });

        return _originalFetch.call(this, input, init).then(function (response) {
            var durationMs = Date.now() - startTime;
            var cloned = response.clone();
            var responseHeaders = headersToObject(cloned.headers);

            cloned.text().then(function (bodyText) {
                safeSend('onResponse', {
                    initiator: 'fetch',
                    url: url,
                    method: method.toUpperCase(),
                    status: cloned.status,
                    statusText: cloned.statusText,
                    responseHeaders: responseHeaders,
                    responseBody: truncateBody(bodyText, 16384),
                    durationMs: durationMs,
                    timestamp: startTime
                });
            }).catch(function () {
                safeSend('onResponse', {
                    initiator: 'fetch',
                    url: url,
                    method: method.toUpperCase(),
                    status: cloned.status,
                    statusText: cloned.statusText,
                    responseHeaders: responseHeaders,
                    responseBody: '[Could not read body]',
                    durationMs: durationMs,
                    timestamp: startTime
                });
            });

            return response;
        }).catch(function (error) {
            var durationMs = Date.now() - startTime;
            safeSend('onError', {
                initiator: 'fetch',
                url: url,
                method: method.toUpperCase(),
                error: String(error),
                durationMs: durationMs,
                timestamp: startTime
            });
            throw error;
        });
    };

    // ─── Patch XMLHttpRequest ──────────────────────────────────────────────────

    var _OriginalXHR = window.XMLHttpRequest;
    window.XMLHttpRequest = function () {
        var xhr = new _OriginalXHR();
        var _method = 'GET';
        var _url = '';
        var _requestHeaders = {};
        var _startTime = 0;

        var _originalOpen = xhr.open.bind(xhr);
        var _originalSetHeader = xhr.setRequestHeader.bind(xhr);
        var _originalSend = xhr.send.bind(xhr);

        xhr.open = function (method, url) {
            _method = (method || 'GET').toUpperCase();
            _url = String(url);
            _originalOpen.apply(this, arguments);
        };

        xhr.setRequestHeader = function (header, value) {
            _requestHeaders[header] = value;
            _originalSetHeader.apply(this, arguments);
        };

        xhr.send = function (body) {
            _startTime = Date.now();

            // Set custom headers on the actual XHR request
            if (window.ChorandCustomHeaders) {
                for (var key in window.ChorandCustomHeaders) {
                    if (window.ChorandCustomHeaders.hasOwnProperty(key)) {
                        _originalSetHeader(key, window.ChorandCustomHeaders[key]);
                        _requestHeaders[key] = window.ChorandCustomHeaders[key];
                    }
                }
            }

            var requestBody = extractRequestBody(body);

            safeSend('onRequest', {
                initiator: 'xhr',
                url: _url,
                method: _method,
                requestHeaders: _requestHeaders,
                requestBody: requestBody,
                timestamp: _startTime
            });

            xhr.addEventListener('loadend', function () {
                var durationMs = Date.now() - _startTime;
                var responseHeaders = {};
                try {
                    var rawHeaders = xhr.getAllResponseHeaders();
                    if (rawHeaders) {
                        rawHeaders.trim().split(/[\r\n]+/).forEach(function (line) {
                            var parts = line.split(': ');
                            var key = parts.shift();
                            responseHeaders[key] = parts.join(': ');
                        });
                    }
                } catch (e) {}

                if (xhr.status > 0) {
                    safeSend('onResponse', {
                        initiator: 'xhr',
                        url: _url,
                        method: _method,
                        status: xhr.status,
                        statusText: xhr.statusText,
                        responseHeaders: responseHeaders,
                        responseBody: truncateBody(xhr.responseText || '', 16384),
                        durationMs: durationMs,
                        timestamp: _startTime
                    });
                }
            });

            xhr.addEventListener('error', function () {
                var durationMs = Date.now() - _startTime;
                safeSend('onError', {
                    initiator: 'xhr',
                    url: _url,
                    method: _method,
                    error: 'Network Error',
                    durationMs: durationMs,
                    timestamp: _startTime
                });
            });

            xhr.addEventListener('timeout', function () {
                var durationMs = Date.now() - _startTime;
                safeSend('onError', {
                    initiator: 'xhr',
                    url: _url,
                    method: _method,
                    error: 'Request Timeout',
                    durationMs: durationMs,
                    timestamp: _startTime
                });
            });

            _originalSend.apply(this, arguments);
        };

        return xhr;
    };

    // Copy static properties from original XHR
    Object.keys(_OriginalXHR).forEach(function (key) {
        try { window.XMLHttpRequest[key] = _OriginalXHR[key]; } catch (e) {}
    });
    window.XMLHttpRequest.prototype = _OriginalXHR.prototype;

    console.log('[Chorand] API interceptor active');

})();
