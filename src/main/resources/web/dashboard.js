(function () {
    var target = function () {
        return document.getElementById('dashboard');
    };
    var expanded = {};

    function applyExpanded() {
        var rows = document.querySelectorAll('tr.detail');
        for (var i = 0; i < rows.length; i++) {
            var row = rows[i];
            var open = expanded[row.getAttribute('data-endpoint')] === true;
            if (open) row.classList.add('open'); else row.classList.remove('open');
        }
        var links = document.querySelectorAll('a[data-endpoint]');
        for (var j = 0; j < links.length; j++) {
            var link = links[j];
            link.setAttribute('aria-expanded', expanded[link.getAttribute('data-endpoint')] === true);
        }
    }

    function swap(html) {
        var current = target();
        if (!current) return;
        current.outerHTML = html;
        var updated = target();
        if (updated && updated.dataset.title) document.title = updated.dataset.title;
        applyExpanded();
    }

    document.addEventListener('click', function (event) {
        var link = event.target.closest && event.target.closest('a[data-endpoint]');
        if (!link) return;
        event.preventDefault();
        var id = link.getAttribute('data-endpoint');
        expanded[id] = expanded[id] !== true;
        applyExpanded();
    });

    function load(url, options) {
        return fetch(url, options).then(function (response) {
            return response.text();
        }).then(swap);
    }

    function refresh() {
        if (document.hidden) return Promise.resolve();
        return load('/fragment', {headers: {'X-Fragment': '1'}}).catch(function () {
        });
    }

    function refreshSeconds() {
        var root = target();
        var seconds = root ? parseInt(root.getAttribute('data-refresh'), 10) : NaN;
        return isNaN(seconds) ? 5 : Math.max(2, seconds);
    }

    setInterval(refresh, refreshSeconds() * 1000);

    document.addEventListener('submit', function (event) {
        if (!event.target.hasAttribute('data-check')) return;
        event.preventDefault();
        load('/check', {method: 'POST', headers: {'X-Fragment': '1'}}).catch(function () {
        });
    });

    try {
        var protocol = window.location.protocol === 'https:' ? 'wss://' : 'ws://';
        var socket = new WebSocket(protocol + window.location.host + '/ws/health');
        socket.onmessage = refresh;
    } catch (ignored) { /* the timer still applies */
    }

    applyExpanded();
})();
